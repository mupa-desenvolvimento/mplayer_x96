package com.mupa.player.enterprise.queue

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.mupa.player.enterprise.R
import com.mupa.player.enterprise.network.QueueApiClient
import com.mupa.player.enterprise.network.QueueApiResult
import com.mupa.player.enterprise.network.RealtimeCommandChannel
import com.mupa.player.enterprise.ui.QueueOverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Módulo MUPA Queue no player X96 — entrega 6 de ARQUITETURA §12.
 *
 * Junta as três partes que a especificação separa de propósito:
 *
 * 1. **Controle remoto** ([dispatchKeyEvent]) — traduz tecla física em intenção e chama a
 *    `queue-api`. Não implementa lógica de fila (especificação §8): quem decide o que é "próxima"
 *    é o Queue Engine, no servidor.
 * 2. **Tempo real** — Supabase Realtime notifica, REST é a fonte da verdade (ARQUITETURA §3, D3).
 *    O evento recebido nunca carrega o estado; ele só dispara um `GET /queue`.
 * 3. **Overlay** — delegado ao [QueueOverlayRenderer], que jamais toca no player.
 *
 * O módulo é inerte sem credencial: um X96 que não é de fila nunca sobe socket, nunca faz polling
 * e nunca responde a tecla.
 */
class QueueController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val renderer: QueueOverlayRenderer,
    private val isOnline: () -> Boolean,
) {
    private val store = QueueStore(context)
    private val api = QueueApiClient { store.credentials() }
    private val announcer = QueueAnnouncer(context)

    /** Serializa as ações de fila: dois NEXT simultâneos pulariam uma senha (ARQUITETURA §5.3). */
    private val actionMutex = Mutex()

    /** Impede que polling e push processem a mesma atualização em paralelo. */
    private val stateMutex = Mutex()

    private val jobs = mutableListOf<Job>()
    private var config: QueueDeviceConfig? = null
    private var keymap: QueueKeymap = QueueKeymap()
    private var lastActionAtMs: Long = 0L
    private var lastShownPosition: Int? = null

    /**
     * `false` até a primeira leitura bem-sucedida do estado.
     *
     * Existe para o player **não** exibir o card gigante ao subir: a senha corrente pode ter sido
     * chamada há uma hora, e anunciá-la de novo porque a TV religou mandaria o cliente errado ao
     * balcão. A partir do segundo estado, toda troca de posição é chamada de verdade.
     */
    private var hasStateBaseline = false
    private var active = false

    /** `true` quando este X96 está pareado e habilitado a chamar senha. */
    val canCall: Boolean get() = active && config?.canCall == true && keymap.isComplete

    /** Provisionamento com que o módulo vigente foi construído. Ver [QueueStore.revision]. */
    private var startedRevision: Int? = null

    /**
     * Sobe o módulo, se houver credencial. **Idempotente.**
     *
     * Chamada a cada ciclo STARTED da Activity, e isso acontece muito mais do que parece: no X96 o
     * ARGOS Agent relança o player pelo autostart a cada ~63 s (verificado em hardware em
     * 2026-08-17). Reconstruir tudo a cada chamada derrubaria o socket do Realtime uma vez por
     * minuto — a mesma armadilha que o `RealtimeCommandChannel` documenta para `device_commands`.
     *
     * Por isso só reconstrói quando o provisionamento muda: sair da tela de configuração incrementa
     * a revisão, e é isso que faz o módulo reler credencial e teclas sem reiniciar o app.
     */
    suspend fun start() {
        val revision = store.revision()
        // Compara com a revisão já avaliada, e não com `active`: um aparelho sem credencial
        // também precisa ser avaliado uma vez só, senão o log repete a cada relançamento.
        if (startedRevision == revision) return

        stop()
        startedRevision = revision

        // As teclas capturadas são lidas ANTES da checagem de credencial, e não junto com a
        // configuração do servidor. Sem isto, um aparelho com as três teclas gravadas mas ainda
        // sem vínculo tem keymap vazio, `actionFor` devolve null, e a tecla é ignorada em
        // silêncio — o operador aperta e não acontece nada, sem nenhuma pista do motivo.
        // Foi exatamente o que aconteceu no X96 de bancada em 2026-08-17.
        keymap = store.localKeymap()

        val creds = store.credentials()
        if (creds == null || !api.isConfigured) {
            Log.i(TAG, "queue_disabled: dispositivo sem credencial de fila")
            renderer.hide()
            return
        }

        active = true
        hasStateBaseline = false
        lastShownPosition = null

        // Configuração em cache primeiro: o X96 costuma voltar de um reboot antes do link da loja,
        // e cabeçalho e rodapé corretos valem mais que uma tela vazia esperando a rede.
        val cached = store.cachedConfig()
        if (cached != null) applyConfig(cached, fromCache = true)

        renderer.show()

        jobs += scope.launch { configLoop() }
        jobs += scope.launch { stateLoop() }
        jobs += scope.launch { realtimeLoop() }
    }

    fun stop() {
        active = false
        startedRevision = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        renderer.hide()
    }

    // -------------------------------------------------------------------------------------------
    // Controle remoto
    // -------------------------------------------------------------------------------------------

    /**
     * Traduz tecla em ação de fila. Retorna `true` quando o evento foi consumido.
     *
     * Três guardas antes de qualquer chamada de rede, todas de ARQUITETURA §8.1:
     * - só responde com vínculo `CALLER` ativo — um X96 de mídia comum ignora o controle;
     * - `repeatCount > 0` é tecla presa, não intenção nova;
     * - debounce de [DEBOUNCE_MS], porque IR reflete em parede e vidro e o mesmo toque chega duas
     *   vezes. Sem isso, um único toque em NEXT pularia uma senha — e o erro só apareceria com o
     *   cliente na frente do balcão.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A tecla é resolvida ANTES de checar se podemos chamar. A ordem importa: um aparelho com
        // teclas capturadas mas sem vínculo precisa dizer por que não chamou, em vez de engolir o
        // evento em silêncio — que foi exatamente o que aconteceu no X96 de bancada em
        // 2026-08-17, com o operador apertando o botão sem nenhum retorno na tela.
        val action = keymap.actionFor(event.keyCode) ?: return false

        if (!canCall) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                explainWhyItCannotCall()
            }
            // Consome mesmo assim: a tecla foi deliberadamente atribuída à fila neste aparelho, e
            // deixá-la passar faria o sistema tratá-la como navegação por cima da mídia.
            return true
        }

        // O ACTION_UP da mesma tecla também é consumido: deixá-lo passar faria o sistema tratar,
        // por exemplo, um DPAD_CENTER como clique na view focada.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastActionAtMs < DEBOUNCE_MS) {
            Log.i(TAG, "queue_key_debounced action=$action")
            return true
        }
        lastActionAtMs = now

        scope.launch { performAction(action) }
        return true
    }

    /**
     * Diz na tela por que a tecla não chamou senha.
     *
     * Cada motivo é uma ação diferente do instalador, então a mensagem precisa distingui-los: sem
     * credencial ele vai parear; com papel errado ele vai corrigir o vínculo no painel; com a fila
     * inativa ele vai reativar o setor. Uma mensagem genérica devolveria todos ao suporte.
     */
    private fun explainWhyItCannotCall() {
        val cfg = config
        val message = when {
            !active || cfg == null ->
                context.getString(R.string.queue_not_paired)

            cfg.role != QueueRole.CALLER ->
                context.getString(R.string.queue_role_not_caller, cfg.role.name)

            !cfg.isActive ->
                context.getString(R.string.queue_sector_inactive)

            !keymap.isComplete ->
                context.getString(R.string.queue_keymap_incomplete)

            else -> context.getString(R.string.queue_action_failed)
        }
        Log.w(TAG, "queue_key_ignorada: $message")
        renderer.show()
        renderer.showMessage(message)
    }

    private suspend fun performAction(action: QueueAction) = actionMutex.withLock {
        if (!isOnline()) {
            renderer.showMessage(context.getString(R.string.queue_offline_action))
            return@withLock
        }

        when (val result = api.call(action)) {
            is QueueApiResult.Ok -> handleOutcome(result.value)

            is QueueApiResult.Unauthorized -> {
                Log.w(TAG, "queue_action_unauthorized action=$action")
                renderer.showMessage(context.getString(R.string.queue_unauthorized))
            }

            is QueueApiResult.NetworkError -> {
                Log.w(TAG, "queue_action_network_error action=$action", result.cause)
                renderer.showMessage(context.getString(R.string.queue_offline_action))
                renderer.setStale(true)
            }

            is QueueApiResult.ApiError -> {
                Log.w(TAG, "queue_action_failed action=$action code=${result.code} ${result.message}")
                renderer.showMessage(
                    result.message.ifBlank { context.getString(R.string.queue_action_failed) },
                )
            }
        }
    }

    private suspend fun handleOutcome(outcome: QueueCallOutcome) {
        val cfg = config
        val sectorName = cfg?.sectorName.orEmpty()
        val duration = cfg?.overlayDurationMs ?: QueueDeviceConfig.DEFAULT_OVERLAY_MS

        when (outcome) {
            is QueueCallOutcome.Called -> {
                // Desenha com o que a própria resposta trouxe, sem esperar o GET /queue: o
                // atendente já apertou a tecla e o cliente está olhando para a TV. O refresh
                // seguinte só acerta o rodapé.
                lastShownPosition = outcome.position
                renderer.setStale(false)
                renderer.showCall(
                    number = outcome.number,
                    subtitle = subtitleFor(outcome.type, sectorName),
                    isPriority = isPriority(outcome.type),
                    durationMs = duration,
                )
                announce(outcome.number, sectorName)
                refreshState()
            }

            is QueueCallOutcome.Repeated -> {
                renderer.setStale(false)
                lastShownPosition = outcome.position
                // O renderizador decide entre pulso e reentrada conforme a tela esteja no ar ou
                // não — quem sabe disso é ele, e a tela sai sozinha sem avisar ninguém.
                renderer.repeatCall(
                    number = outcome.number,
                    subtitle = subtitleFor(outcome.type, sectorName),
                    isPriority = isPriority(outcome.type),
                    durationMs = duration,
                )
                // Rechamar existe justamente porque o cliente não ouviu da primeira vez — o áudio
                // repete, mesmo que o card só pulse.
                announce(outcome.number, sectorName)
                refreshState()
            }

            QueueCallOutcome.Empty ->
                renderer.showMessage(context.getString(R.string.queue_empty))

            QueueCallOutcome.AtStart ->
                renderer.showMessage(context.getString(R.string.queue_at_start))

            QueueCallOutcome.NoCurrent ->
                renderer.showMessage(context.getString(R.string.queue_no_current))

            is QueueCallOutcome.Unknown -> {
                Log.w(TAG, "queue_outcome_desconhecido status=${outcome.status}")
                refreshState()
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Gatilho de verificação em hardware
    // -------------------------------------------------------------------------------------------

    /**
     * Exibe uma chamada falsa, sem tocar na `queue-api`.
     *
     * Existe por um motivo específico e documentado: o risco nº 1 desta entrega é a composição da
     * camada de fila sobre `PlayerView` com `surface_type="texture_view"`, que se comporta
     * diferente em X96 real e **não é reproduzível no emulador**. Sem isto, validar esse risco
     * dependeria de ter a Edge Function deployada e um setor configurado — dependência que não tem
     * relação nenhuma com o que se quer medir.
     *
     * Nunca escreve nada, nunca usa a credencial e nunca mexe no cursor da fila. Quem controla se
     * ele está acessível é o [com.mupa.player.enterprise.ui.PlayerActivity], que só registra o
     * receiver em build depurável ou com devMode ligado.
     */
    fun showTestCall(
        number: String,
        sectorName: String,
        priority: Boolean,
        durationMs: Long,
        withAudio: Boolean = true,
        history: List<String> = emptyList(),
        waitingCount: Int = 0,
    ) {
        renderer.show()
        renderer.setSectorName(sectorName)
        if (history.isNotEmpty()) {
            renderer.renderState(QueueState.forPreview(number, history, waitingCount))
        }
        renderer.showCall(
            number = number,
            subtitle = subtitleFor(if (priority) "PREFERENCIAL" else "NORMAL", sectorName),
            isPriority = priority,
            durationMs = durationMs,
        )
        // Sem passar por [announce]: aqui não há `config`, e o ponto do gatilho é justamente
        // verificar o ding-dong e a fala num aparelho ainda não pareado.
        if (withAudio && announcer.isConfigured) {
            scope.launch {
                announcer.announce(number, sectorName)
                renderer.holdAtLeast(POST_SPEECH_HOLD_MS)
            }
        }
    }

    /** Devolve a camada ao estado real: escondida se o módulo estiver inerte. */
    fun hideTestOverlay() {
        if (active) renderer.show() else renderer.hide()
    }

    // -------------------------------------------------------------------------------------------
    // Configuração
    // -------------------------------------------------------------------------------------------

    /**
     * Reconsulta `GET /config` periodicamente.
     *
     * O intervalo é longo de propósito: o que muda ali (setor, prefixo, duração do overlay,
     * keymap) é configuração de provisionamento, não estado de operação. Estado vem do
     * [stateLoop] e do Realtime.
     */
    private suspend fun configLoop() {
        var backoffMs = CONFIG_RETRY_BASE_MS
        while (scope.isActive && active) {
            if (isOnline()) {
                when (val result = api.getConfig()) {
                    is QueueApiResult.Ok -> {
                        applyConfig(result.value, fromCache = false)
                        store.cacheConfig(result.value)
                        backoffMs = CONFIG_RETRY_BASE_MS
                        delay(CONFIG_REFRESH_MS)
                        continue
                    }

                    is QueueApiResult.Unauthorized -> {
                        // Credencial revogada ou rotacionada no painel. Para de tentar: insistir
                        // só gera ruído no log e no servidor até alguém reparear o aparelho.
                        Log.w(TAG, "queue_config_unauthorized — dispositivo precisa ser pareado de novo")
                        renderer.setStale(true)
                        return
                    }

                    is QueueApiResult.NetworkError, is QueueApiResult.ApiError -> {
                        Log.w(TAG, "queue_config_failed: $result")
                    }
                }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(CONFIG_RETRY_MAX_MS)
        }
    }

    private suspend fun applyConfig(newConfig: QueueDeviceConfig, fromCache: Boolean) {
        config = newConfig
        // Captura local vence o mapa do painel: quem capturou estava com o controle na mão, na
        // frente desta TV. O painel continua servindo para provisionar em lote.
        val local = store.localKeymap()
        keymap = QueueKeymap(
            play = local.play ?: newConfig.keymap.play,
            next = local.next ?: newConfig.keymap.next,
            back = local.back ?: newConfig.keymap.back,
        )

        renderer.setSectorName(newConfig.sectorName)

        if (!fromCache && newConfig.role == QueueRole.CALLER && keymap.isEmpty) {
            Log.w(
                TAG,
                "queue_keymap_ausente sector=${newConfig.sectorName} — o controle não responde até " +
                    "as teclas serem capturadas nas Configurações",
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Estado da fila
    // -------------------------------------------------------------------------------------------

    /**
     * Rede de segurança do push. O caminho rápido é o Realtime; este loop cobre socket caído,
     * Realtime desabilitado no backend e a carga inicial.
     */
    private suspend fun stateLoop() {
        while (scope.isActive && active) {
            refreshState()
            delay(STATE_POLL_MS)
        }
    }

    /**
     * Canal de push. Assina `queue_events` e, a cada INSERT, dispara a varredura REST — o mesmo
     * padrão já validado no `device_commands` (ARQUITETURA D3).
     *
     * O filtro por setor é feito no `GET /queue`, que resolve o setor pela credencial: o evento em
     * si é só um "algo mudou". Isso custa uma requisição extra quando o evento é de outra loja e,
     * em troca, não depende de o payload do Realtime carregar `sector_id` corretamente.
     */
    private suspend fun realtimeLoop() {
        RealtimeCommandChannel(
            table = QUEUE_EVENTS_TABLE,
            onCommandInserted = {
                scope.launch {
                    runCatching { refreshState() }
                        .onFailure { Log.w(TAG, "queue_realtime_sweep_failed", it) }
                }
            },
        ).run()
    }

    private suspend fun refreshState() = stateMutex.withLock {
        if (!isOnline()) {
            renderer.setStale(true)
            return@withLock
        }

        when (val result = api.getState()) {
            is QueueApiResult.Ok -> {
                val state = result.value
                renderer.setStale(false)
                renderer.renderState(state)
                announceIfNewCall(state)
            }

            is QueueApiResult.Unauthorized -> {
                Log.w(TAG, "queue_state_unauthorized")
                renderer.setStale(true)
            }

            is QueueApiResult.NetworkError -> renderer.setStale(true)

            is QueueApiResult.ApiError ->
                Log.w(TAG, "queue_state_failed code=${result.code} ${result.message}")
        }
    }

    /**
     * Exibe o card quando a senha corrente mudou sem ter passado por este controle.
     *
     * Cobre dois casos reais e ignorados se a TV só reagisse à própria tecla: a chamada feita pelo
     * painel do Mupa Connect (que fala direto com as RPCs, CONTRATO §3) e o X96 com papel
     * `DISPLAY`, que exibe a fila mas nunca chama. É o que justifica o canal Realtime existir para
     * uma TV que não tem controle nenhum.
     */
    private fun announceIfNewCall(state: QueueState) {
        val current = state.current
        if (current == null) {
            lastShownPosition = null
            hasStateBaseline = true
            return
        }
        if (!hasStateBaseline) {
            lastShownPosition = current.position
            hasStateBaseline = true
            return
        }
        if (current.position == lastShownPosition) return

        lastShownPosition = current.position
        val cfg = config
        renderer.showCall(
            number = current.number,
            subtitle = subtitleFor(current.type, cfg?.sectorName.orEmpty()),
            isPriority = current.isPriority,
            durationMs = cfg?.overlayDurationMs ?: QueueDeviceConfig.DEFAULT_OVERLAY_MS,
        )
        announce(current.number, cfg?.sectorName.orEmpty())
    }

    /**
     * Dispara o ding-dong e a fala, sem bloquear quem chamou.
     *
     * O card já está na tela quando isto começa. Se o áudio demorar ou falhar, o cliente continua
     * vendo a senha — a degradação é do aviso sonoro, nunca da chamada.
     *
     * Respeita `audio_enabled` de `queue_sector_config`: setor com áudio desligado (uma TV em
     * corredor silencioso, por exemplo) não fala.
     */
    private fun announce(number: String, sectorName: String) {
        val cfg = config
        if (cfg != null && !cfg.audioEnabled) return
        if (!announcer.isConfigured) return
        scope.launch {
            announcer.announce(number, sectorName)
            // A tela fica mais [POST_SPEECH_HOLD_MS] DEPOIS que a fala termina, e não um prazo
            // fixo contado da entrada. O áudio (ding-dong + senha + setor) passa de 6 s, então um
            // overlay de 5 s saía com a locução ainda no ar: o cliente ouvia o número e olhava
            // para a publicidade de volta. `holdAtLeast` só estende, nunca encurta.
            renderer.holdAtLeast(POST_SPEECH_HOLD_MS)
        }
    }

    private fun isPriority(type: String): Boolean =
        type.equals("PREFERENCIAL", ignoreCase = true) || type.equals("PRIORIDADE", ignoreCase = true)

    private fun subtitleFor(type: String, sectorName: String): String =
        if (isPriority(type)) "$sectorName • PREFERENCIAL" else sectorName

    companion object {
        private const val TAG = "MPlayerQueue"

        /** Tabela de eventos do MUPA Queue na publication `supabase_realtime`. */
        private const val QUEUE_EVENTS_TABLE = "queue_events"

        /**
         * Janela de debounce do controle. 600 ms cobre repique de IR e tecla presa sem atrapalhar
         * o atendente que chama duas senhas em seguida — chamar duas em menos de 600 ms não é um
         * caso real de balcão.
         */
        private const val DEBOUNCE_MS = 600L

        /**
         * Quanto a tela da chamada permanece **depois** que a fala termina.
         *
         * Definido com o Antunes em 2026-08-17 a partir do comportamento em loja: o cliente ouve o
         * número e só então levanta a cabeça para conferir na tela. Três segundos é o tempo desse
         * movimento.
         */
        private const val POST_SPEECH_HOLD_MS = 3_000L

        private const val CONFIG_REFRESH_MS = 15 * 60 * 1000L
        private const val CONFIG_RETRY_BASE_MS = 15 * 1000L
        private const val CONFIG_RETRY_MAX_MS = 5 * 60 * 1000L

        private const val STATE_POLL_MS = 30 * 1000L
    }
}
