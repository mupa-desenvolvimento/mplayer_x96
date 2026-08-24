package com.mupa.player.enterprise.ui

import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.load
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.R
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.databinding.ViewQueueFullscreenBinding
import com.mupa.player.enterprise.queue.QueueBranding
import com.mupa.player.enterprise.queue.QueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Renderização da camada de fila (Queue Overlay Engine, ARQUITETURA §4.1).
 *
 * A apresentação da chamada é **tela cheia por alguns segundos, depois sai** — decidido com o
 * Antunes em 2026-08-17 a partir do mockup da farmácia. Entre chamadas a TV volta a ser só mídia:
 * não há cabeçalho nem rodapé permanentes.
 *
 * Cobrir não é interromper. Nada aqui fala com o `PlayerEngine`: o vídeo segue tocando atrás e
 * reaparece de onde estava quando a tela sai. É a regra da especificação §12, garantida por
 * construção — não por disciplina de quem editar isto depois.
 *
 * Esta classe **não conhece rede nem regra de fila**: recebe estado pronto e desenha. Toda a
 * semântica de cursor vive no Queue Engine, no servidor (ARQUITETURA §5.2).
 */
class QueueOverlayRenderer(
    private val binding: ViewQueueFullscreenBinding,
    private val scope: CoroutineScope,
) {
    private var hideScreenJob: Job? = null
    private var hideToastJob: Job? = null

    /** Instante (relógio monotônico) em que a tela está agendada para sair. Ver [holdAtLeast]. */
    private var hideAtElapsedMs: Long = 0L

    private var sectorName: String = ""
    private var lastState: QueueState? = null
    private var stale: Boolean = false
    private var brandImageRequested = false
    private var branding: QueueBranding = QueueBranding.NONE

    private val brandImageLoader: ImageLoader by lazy {
        ImageLoader.Builder(binding.root.context)
            .okHttpClient { TlsCompat.newClient() }
            .build()
    }

    private val historySlots by lazy {
        listOf(
            binding.queueScreenHistory1,
            binding.queueScreenHistory2,
            binding.queueScreenHistory3,
            binding.queueScreenHistory4,
            binding.queueScreenHistory5,
        )
    }

    init {
        val res = binding.root.context
        binding.queueFeature1.featureText.text = res.getString(R.string.queue_screen_feature_1)
        binding.queueFeature2.featureText.text = res.getString(R.string.queue_screen_feature_2)
        binding.queueFeature3.featureText.text = res.getString(R.string.queue_screen_feature_3)

        binding.queueStep1.stepNumber.text = "1"
        binding.queueStep2.stepNumber.text = "2"
        binding.queueStep3.stepNumber.text = "3"
        binding.queueStep1.stepText.text = res.getString(R.string.queue_screen_step_1)
        binding.queueStep2.stepText.text = res.getString(R.string.queue_screen_step_2)
        binding.queueStep3.stepText.text = res.getString(R.string.queue_screen_step_3)
    }

    /**
     * Aplica a identidade visual da loja (`queue_branding`, especificação §26).
     *
     * Cada cor ausente **mantém o tema padrão** em vez de virar transparente: configuração parcial
     * é o caso normal, e uma loja que preencheu só a cor primária não pode acabar com texto
     * invisível. Por isso nada aqui usa valor "vazio" como se fosse cor.
     *
     * Os painéis são tingidos via `mutate()` + `setTint` porque o fundo vem de um drawable
     * compartilhado — sem `mutate()`, tingir um painel mudaria todos os que usam o mesmo recurso,
     * inclusive em outras telas.
     */
    fun applyBranding(newBranding: QueueBranding) {
        if (newBranding == branding) return
        branding = newBranding
        if (newBranding.isEmpty) return

        val ctx = binding.root.context
        val primary = newBranding.primaryColor ?: ContextCompat.getColor(ctx, R.color.queue_blue)
        val accent = newBranding.accentColor

        newBranding.backgroundColor?.let { binding.queueScreen.setBackgroundColor(it) }

        // Número, título e setor seguem a cor primária: são o que precisa ser lido de longe.
        binding.queueScreenNumber.setTextColor(primary)
        binding.queueScreenTitle.setTextColor(accent ?: primary)
        binding.queueScreenCounter.setTextColor(primary)
        binding.queueBrandCaption.setTextColor(primary)

        tintPanel(binding.queuePanelHistory, primary)
        tintPanel(binding.queuePanelHow, primary)

        // Os elementos secundários também: tema pela metade — número vermelho com selo azul —
        // parece defeito de renderização, não identidade da loja.
        binding.queueQrText.setTextColor(primary)
        // Dentro do painel já tingido, os detalhes claros vêm do branco com alfa — assim funcionam
        // sobre qualquer cor primária que a loja escolher, clara ou escura.
        binding.queueHistoryDivider.setBackgroundColor(withAlpha(android.graphics.Color.WHITE, DIVIDER_ALPHA))
        binding.queueScreenWaiting.setTextColor(withAlpha(android.graphics.Color.WHITE, MUTED_ALPHA))
        binding.queueScreenDivider.setBackgroundColor(withAlpha(accent ?: primary, DIVIDER_ALPHA))

        listOf(binding.queueFeature1, binding.queueFeature2, binding.queueFeature3).forEach { item ->
            item.featureText.setTextColor(primary)
            item.featureIcon.background?.mutate()?.setTint(withAlpha(primary, ICON_BG_ALPHA))
        }
        listOf(binding.queueStep1, binding.queueStep2, binding.queueStep3).forEach { item ->
            // O badge fica sobre o painel já tingido de primária: usar a acentuada o mantém legível.
            item.stepNumber.background?.mutate()?.setTint(accent ?: withAlpha(primary, BADGE_ALPHA))
        }

        newBranding.footerText?.let { binding.queueBrandCaption.text = it }

        // O logo da loja tem precedência sobre a imagem fixa da build — era exatamente a dívida
        // registrada em ENTREGA_06_X96.md §5 ("imagem do produto fixa em BuildConfig").
        newBranding.logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            brandImageRequested = true
            binding.queueBrandImage.load(url, brandImageLoader) {
                placeholder(R.drawable.ic_mupa_logo)
                error(R.drawable.ic_mupa_logo)
                crossfade(true)
            }
        }
    }

    private fun tintPanel(view: View, color: Int) {
        view.background?.mutate()?.setTint(color)
    }

    /** Mesma cor com opacidade menor — evita exigir da loja uma paleta com cinco tons. */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * Habilita a camada.
     *
     * Deixa apenas a raiz visível — a tela da chamada só aparece em [showCall]. Sem isto, um
     * aparelho pareado mostraria um painel de fila permanente sobre a mídia, que não é o que o
     * produto pede.
     */
    fun show() {
        binding.root.visibility = View.VISIBLE
        loadBrandImageOnce()
    }

    /**
     * Baixa a imagem do espaço de marca uma única vez por instância.
     *
     * Feita aqui e não na construção porque um X96 que não é de fila nunca chama [show] — e não
     * deve gastar rede com isto. O Coil mantém cache em disco, então depois da primeira vez a
     * imagem aparece mesmo com a loja offline; se a busca falhar, o `error` deixa o logo no lugar
     * e a tela nunca fica com um buraco.
     *
     * O `ImageLoader` é próprio, com o cliente do [TlsCompat]: no flavor `legacy` (minSdk 21) o
     * cliente padrão do Coil não conhece a raiz ISRG Root X1 e o download falharia em silêncio.
     */
    private fun loadBrandImageOnce() {
        if (brandImageRequested) return
        brandImageRequested = true

        val url = BuildConfig.QUEUE_BRAND_IMAGE_URL.trim()
        if (url.isBlank()) return

        binding.queueBrandImage.load(url, brandImageLoader) {
            placeholder(R.drawable.ic_mupa_logo)
            error(R.drawable.ic_mupa_logo)
            crossfade(true)
        }
    }

    fun hide() {
        hideScreenJob?.cancel()
        hideToastJob?.cancel()
        binding.queueScreen.visibility = View.GONE
        binding.queueToast.visibility = View.GONE
        binding.root.visibility = View.GONE
    }

    fun setSectorName(name: String) {
        sectorName = name
        binding.queueScreenCounter.text = name
    }

    /**
     * Marca a fila como desatualizada.
     *
     * Sem cabeçalho permanente, o aviso vive no rodapé do painel de últimas chamadas — só é visto
     * durante uma chamada, que é exatamente quando importa saber se a informação está velha
     * (ARQUITETURA §10).
     */
    fun setStale(isStale: Boolean) {
        stale = isStale
        renderWaitingLine()
    }

    fun renderState(state: QueueState) {
        lastState = state
        val calls = state.previousCalls.take(historySlots.size)
        historySlots.forEachIndexed { index, view ->
            val call = calls.getOrNull(index)
            if (call == null) {
                // INVISIBLE e não GONE: as cinco linhas têm peso igual, e escondê-las de vez faria
                // as restantes esticarem e mudarem de tamanho a cada chamada.
                view.visibility = View.INVISIBLE
            } else {
                view.text = call.number
                view.visibility = View.VISIBLE
            }
        }
        renderWaitingLine()
    }

    private fun renderWaitingLine() {
        val context = binding.root.context
        binding.queueScreenWaiting.text = when {
            stale -> context.getString(R.string.queue_stale_badge)
            else -> context.getString(
                R.string.queue_waiting_count,
                lastState?.waitingCount ?: 0,
            )
        }
    }

    /**
     * Exibe a tela da chamada e a retira sozinha depois de [durationMs].
     *
     * A duração vem de `queue_sector_config.overlay_duration_ms` — configurável por setor, como
     * pede a especificação §15.5.
     */
    fun showCall(number: String, subtitle: String, isPriority: Boolean, durationMs: Long) {
        hideToast()
        binding.queueScreenNumber.text = number
        binding.queueScreenCounter.text = subtitle.ifBlank { sectorName }
        binding.queueScreenTitle.setText(
            if (isPriority) R.string.queue_screen_current_priority else R.string.queue_screen_current,
        )

        val screen = binding.queueScreen
        hideScreenJob?.cancel()
        screen.animate().cancel()

        if (screen.visibility != View.VISIBLE) {
            screen.alpha = 0f
            screen.visibility = View.VISIBLE
            screen.animate().alpha(1f).setDuration(ENTER_DURATION_MS).start()
        } else {
            screen.alpha = 1f
        }

        scheduleHide(durationMs)
    }

    /**
     * Rechamada da senha atual (PLAY).
     *
     * Se a tela já está no ar com o mesmo número, dá um pulso — reentrar com animação completa
     * numa tela que não saiu pareceria falha de renderização. Se ela **já saiu**, exibe de novo,
     * inteira.
     *
     * Esse segundo caso é o que importa e é o que estava quebrado: como a tela sai sozinha em
     * poucos segundos, o estado normal quando o atendente aperta PLAY é justamente ela estar
     * escondida. Rechamar existe porque o cliente não viu nem ouviu da primeira vez — não trazer a
     * tela de volta esvazia a função inteira.
     */
    fun repeatCall(number: String, subtitle: String, isPriority: Boolean, durationMs: Long) {
        val screenVisible = binding.queueScreen.visibility == View.VISIBLE
        val sameNumber = binding.queueScreenNumber.text?.toString() == number

        if (!screenVisible || !sameNumber) {
            showCall(number, subtitle, isPriority, durationMs)
            return
        }

        val numberView = binding.queueScreenNumber
        numberView.animate().cancel()
        numberView.animate()
            .scaleX(PULSE_SCALE).scaleY(PULSE_SCALE)
            .setDuration(PULSE_DURATION_MS)
            .withEndAction {
                numberView.animate().scaleX(1f).scaleY(1f).setDuration(PULSE_DURATION_MS).start()
            }
            .start()
        scheduleHide(durationMs)
    }

    /**
     * Prolonga a permanência da tela até pelo menos [extraMs] a partir de agora.
     *
     * Chamado quando a fala termina: a senha precisa continuar legível por alguns segundos depois
     * do áudio, senão o cliente ouve o número e olha para uma tela que já voltou à publicidade.
     *
     * **Só estende, nunca encurta.** Um áudio curto — ou que falhou e voltou na hora — não pode
     * fazer a tela sair antes do tempo configurado em `overlay_duration_ms`.
     */
    fun holdAtLeast(extraMs: Long) {
        if (binding.queueScreen.visibility != View.VISIBLE) return
        val target = SystemClock.elapsedRealtime() + extraMs
        if (target <= hideAtElapsedMs) return
        scheduleHide(extraMs)
    }

    private fun scheduleHide(durationMs: Long) {
        hideScreenJob?.cancel()
        hideAtElapsedMs = SystemClock.elapsedRealtime() + durationMs
        hideScreenJob = scope.launch {
            delay(durationMs)
            if (!isActive) return@launch
            binding.queueScreen.animate()
                .alpha(0f)
                .setDuration(EXIT_DURATION_MS)
                .withEndAction { binding.queueScreen.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Retorno curto ao atendente (fila vazia, início do histórico, falha de rede).
     *
     * Deliberadamente **não** usa a tela cheia: "nenhuma senha aguardando" é informação para quem
     * está no balcão, e não justifica tirar a publicidade do ar.
     */
    fun showMessage(text: String) {
        hideToastJob?.cancel()
        binding.queueToast.animate().cancel()
        binding.queueToast.text = text
        binding.queueToast.alpha = 1f
        binding.queueToast.visibility = View.VISIBLE
        hideToastJob = scope.launch {
            delay(TOAST_DURATION_MS)
            if (!isActive) return@launch
            hideToast()
        }
    }

    private fun hideToast() {
        hideToastJob?.cancel()
        binding.queueToast.animate()
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .withEndAction { binding.queueToast.visibility = View.GONE }
            .start()
    }

    companion object {
        private const val PULSE_SCALE = 1.08f
        private const val ENTER_DURATION_MS = 240L
        private const val EXIT_DURATION_MS = 300L
        private const val PULSE_DURATION_MS = 160L
        private const val TOAST_DURATION_MS = 3_000L

        private const val DIVIDER_ALPHA = 0x66
        private const val ICON_BG_ALPHA = 0x22
        private const val BADGE_ALPHA = 0xCC
        private const val MUTED_ALPHA = 0x99
    }
}
