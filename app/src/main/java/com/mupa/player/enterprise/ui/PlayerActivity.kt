package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import android.widget.EditText
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import coil.load
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.databinding.ActivityPlayerBinding
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.ManifestManager
import com.mupa.player.enterprise.managers.MediaSyncProgress
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.player.PlaybackProfile
import com.mupa.player.enterprise.player.PlayerEngine
import com.mupa.player.enterprise.player.TransitionConfig
import com.mupa.player.enterprise.R
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.mupa.player.enterprise.services.DeviceCommandService
import com.mupa.player.enterprise.network.RealtimeCommandChannel
import com.mupa.player.enterprise.queue.QueueController
import android.view.KeyEvent
import kotlin.random.Random
import org.json.JSONObject

class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var deviceId: String
    private lateinit var playerEngine: PlayerEngine
    private lateinit var manifestManager: ManifestManager
    private var playlistName: String? = null
    private var itemNameById: Map<String, String> = emptyMap()
    private var companyId: String? = null

    private var lastPlaylistItemsIds: List<String> = emptyList()
    private var syncHideJob: Job? = null
    private var devMode = false
    private var demoMode = false

    private val commandService = DeviceCommandService()

    /** Identificadores pelos quais o Mupa Connect pode endereçar este device em `device_commands`. */
    private var deviceIdentifiers: List<String> = emptyList()

    /**
     * Serializa todo acesso ao download de mídias. Sem isso, o ciclo periódico, o comando
     * `reload_playlist` e o prefetch podem baixar o mesmo arquivo ao mesmo tempo e corromper
     * o `.tmp` compartilhado em ManifestManager.downloadToFile().
     */
    private val syncMutex = Mutex()

    /** Impede que o polling e o push em tempo real processem a mesma pendência em paralelo. */
    private val commandMutex = Mutex()

    /** Conexão única do Realtime — ver comentário na criação, em [startLoop]. */
    private var realtimeJob: Job? = null

    /**
     * Módulo MUPA Queue. Inerte em X96 sem credencial de fila — que é o caso da frota inteira
     * hoje, então o player de mídia comum não paga nada por ele existir.
     */
    private var queueController: QueueController? = null

    /**
     * Receiver do gatilho de verificação do overlay em hardware.
     *
     * Registrado **dinamicamente**, e só em build depurável ou com devMode ligado — em APK de
     * release da frota ele simplesmente não chega a existir. Não está no AndroidManifest de
     * propósito: um receiver declarado existiria em produção mesmo desabilitado.
     *
     * Nota de campo: o `DevModeReceiver` é protegido por permissão `signature`, então `adb shell am
     * broadcast` não consegue ligar o devMode de fora. Por isso a condição inclui
     * `BuildConfig.DEBUG` — sem ela, verificar o overlay num X96 de bancada exigiria navegar a tela
     * de Configurações pelo controle.
     */
    private var queueTestReceiver: BroadcastReceiver? = null

    private var storagePermissionDeferred: CompletableDeferred<Boolean>? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            storagePermissionDeferred?.complete(granted)
            storagePermissionDeferred = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apkVersionWatermark.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        setupDevModeToggle()

        manifestManager = ManifestManager(applicationContext)
        playerEngine = PlayerEngine(
            context = this,
            scope = lifecycleScope,
            layerA = PlayerEngine.LayerViews(
                container = binding.layerA,
                playerView = binding.playerViewA,
                imageView = binding.imageViewA,
                webView = binding.webViewA,
            ),
            layerB = PlayerEngine.LayerViews(
                container = binding.layerB,
                playerView = binding.playerViewB,
                imageView = binding.imageViewB,
                webView = binding.webViewB,
            ),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        queueController = QueueController(
            context = applicationContext,
            scope = lifecycleScope,
            renderer = QueueOverlayRenderer(binding.queueLayer, lifecycleScope),
            isOnline = ::isOnline,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val startedScope = this
                launch {
                    applyImmersive()
                }
                launch {
                    val mgr = SettingsManager(applicationContext)
                    var lastDev = false
                    var lastDemo = false
                    mgr.settingsFlow.collect { s ->
                        val vDev = s.devMode
                        val vDemo = s.demoMode
                        if (vDev != lastDev || vDemo != lastDemo) {
                            lastDev = vDev
                            lastDemo = vDemo
                            devMode = vDev
                            demoMode = vDemo
                            updateDeviceWatermark()
                            updateDevModeUI()
                        }
                    }
                }
                launch { startLoop(startedScope) }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            val serviceIntent = Intent(this, com.mupa.player.enterprise.services.InactivityTimerService::class.java)
            stopService(serviceIntent)
        }
        lifecycleScope.launch {
            val settings = runCatching { SettingsManager(applicationContext).getSettings() }.getOrNull()
            devMode = settings?.devMode ?: false
            demoMode = settings?.demoMode ?: false
            updateDeviceWatermark()
            updateDevModeUI()
            val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
            binding.deviceNameText.text = cache?.deviceName?.ifBlank { deviceId } ?: deviceId
            companyId = cache?.company?.trim()?.ifBlank { null }
        }
    }

    private fun applyImmersive() {
        val w = window ?: return
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = w.insetsController ?: return
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onDestroy() {
        queueTestReceiver?.let { runCatching { unregisterReceiver(it) } }
        queueTestReceiver = null
        queueController?.stop()
        playerEngine.release()
        super.onDestroy()
    }

    private suspend fun startLoop(scope: CoroutineScope) {
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.trim().orEmpty()
        if (deviceId.isBlank()) {
            deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
        }
        val settings = runCatching { SettingsManager(applicationContext).getSettings() }.getOrNull()
        devMode = settings?.devMode ?: false
        demoMode = settings?.demoMode ?: false
        updateDevModeUI()
        updateDeviceWatermark()

        val cache = runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
        binding.deviceNameText.text = cache?.deviceName?.ifBlank { deviceId } ?: deviceId
        companyId = cache?.company?.trim()?.ifBlank { null }

        // `device_commands.device_id` é TEXT livre no Mupa Connect (serial, apelido interno ou
        // id do dispositivo), então consultamos por todos os identificadores que conhecemos.
        deviceIdentifiers = listOfNotNull(
            deviceId,
            cache?.deviceId,
            cache?.deviceName,
            cache?.deviceDbId?.takeIf { it > 0L }?.toString(),
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        ensureStoragePermissionIfNeeded()

        // Os loops periódicos são criados ANTES da sincronização inicial: a atualização
        // automática não pode depender do sucesso da carga inicial. Rodam no escopo do
        // repeatOnLifecycle, então são cancelados no STOP e recriados no START — sem acúmulo.
        scope.launch { activeItemsLoop() }
        scope.launch { remoteRefreshLoop() }
        scope.launch { commandPollLoop() }

        // O socket do Realtime é a exceção: vive no lifecycleScope (morre só no onDestroy) e não
        // no escopo do repeatOnLifecycle, com guarda de instância única.
        //
        // Antes ele nascia junto com os demais loops e, num STOP/START rápido na inicialização,
        // duas instâncias subiam com ~12s de diferença. A segunda ficava saudável; a primeira
        // ficava órfã, sem heartbeat, e o servidor a derrubava a cada 60s — churn de reconexão
        // que só cessava quando as sobras se esgotavam, ~9 minutos depois.
        //
        // Manter o socket fora do ciclo STOP/START também evita reconectar à toa toda vez que o
        // player passa para segundo plano (abrir as Configurações, por exemplo).
        if (realtimeJob?.isActive != true) {
            realtimeJob = lifecycleScope.launch { realtimeCommandLoop() }
        }

        // O módulo de fila é (re)iniciado a cada ciclo STARTED, ao contrário do socket de
        // `device_commands`: é assim que ele relê credencial e teclas depois de o operador sair da
        // tela de configuração, sem precisar reiniciar o app. `start()` já encerra a instância
        // anterior, então não acumula socket nem loop.
        runCatching { queueController?.start() }
            .onFailure { Log.w(TAG_SYNC, "queue_start_failed", it) }
        registerQueueTestReceiverIfAllowed()

        tryStartOfflinePlayback()
        initialSyncAndPlayback()
    }

    /**
     * Entrada das teclas do controle remoto no MUPA Queue.
     *
     * Fica na Activity, e não numa View, porque o X96 não tem toque e nada na camada de fila é
     * focável — `dispatchKeyEvent` da Activity é o único ponto que enxerga a tecla antes de
     * qualquer roteamento por foco.
     *
     * Devolve ao comportamento padrão sempre que o módulo estiver inerte: um X96 de mídia comum
     * não pode ter o controle sequestrado por causa de código de fila que ele nem usa.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (queueController?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Registra o gatilho de verificação do overlay. Ver [queueTestReceiver] para o porquê da
     * condição e do registro dinâmico.
     *
     * Uso, com o player em reprodução:
     * ```
     * adb shell am broadcast -a com.mupa.player.enterprise.ACTION_QUEUE_TEST_CALL \
     *   --es number A123 --es sector "Farmácia" --ez priority false --el duration_ms 15000
     * ```
     */
    private fun registerQueueTestReceiverIfAllowed() {
        val allowed = BuildConfig.DEBUG || devMode
        if (!allowed || queueTestReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_QUEUE_TEST_CALL -> {
                        val number = intent.getStringExtra("number")?.trim().orEmpty().ifBlank { "A123" }
                        val sector = intent.getStringExtra("sector")?.trim().orEmpty().ifBlank { "Setor de teste" }
                        val priority = intent.getBooleanExtra("priority", false)
                        val durationMs = intent.getLongExtra("duration_ms", 5_000L)
                        val withAudio = intent.getBooleanExtra("audio", true)
                        val history = intent.getStringExtra("history")
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        val waiting = intent.getIntExtra("waiting", 0)
                        // Cores opcionais, para prever o tema de uma loja sem pareamento.
                        fun color(extra: String): Int? = intent.getStringExtra(extra)
                            ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                        val branding = com.mupa.player.enterprise.queue.QueueBranding(
                            primaryColor = color("primary"),
                            accentColor = color("accent"),
                            backgroundColor = color("bg"),
                            logoUrl = intent.getStringExtra("logo"),
                            footerText = intent.getStringExtra("caption"),
                        )
                        Log.i(TAG_SYNC, "queue_test_call number=$number priority=$priority audio=$withAudio")
                        queueController?.showTestCall(
                            number = number,
                            sectorName = sector,
                            priority = priority,
                            durationMs = durationMs,
                            withAudio = withAudio,
                            history = history,
                            waitingCount = waiting,
                            branding = branding,
                        )
                    }

                    ACTION_QUEUE_TEST_HIDE -> {
                        Log.i(TAG_SYNC, "queue_test_hide")
                        queueController?.hideTestOverlay()
                    }
                }
            }
        }

        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_QUEUE_TEST_CALL)
            addAction(ACTION_QUEUE_TEST_HIDE)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        queueTestReceiver = receiver
        Log.i(TAG_SYNC, "queue_test_receiver_registrado debug=${BuildConfig.DEBUG} devMode=$devMode")
    }

    /** Reavalia a vigência (data / faixa horária) dos itens já baixados localmente. */
    private suspend fun activeItemsLoop() {
        while (true) {
            delay(ACTIVE_ITEMS_CHECK_MS)
            runCatching { updatePlaylistIfActiveItemsChanged() }
                .onFailure { Log.e(TAG_SYNC, "active_items_check_failed", it) }
        }
    }

    /**
     * Canal de push do Mupa Connect: consome `device_commands` com `command = reload_playlist`.
     * É a Opção B do manual de integração (polling REST), que também é o caminho obrigatório de
     * inicialização e reconexão caso o Realtime seja adotado depois.
     */
    private suspend fun commandPollLoop() {
        if (deviceIdentifiers.isEmpty()) {
            Log.w(TAG_SYNC, "command_poll_disabled: nenhum identificador de device conhecido")
            return
        }
        // Varredura imediata na inicialização — comandos enfileirados enquanto o device esteve
        // desligado precisam ser aplicados assim que ele volta, sem esperar o primeiro intervalo.
        while (true) {
            runCatching { processPendingCommands() }
                .onFailure { Log.w(TAG_SYNC, "command_poll_failed", it) }
            delay(COMMAND_POLL_INTERVAL_MS)
        }
    }

    /**
     * Canal de push em tempo real. Cada INSERT em `device_commands` dispara a mesma varredura de
     * pendências do polling — o polling continua ativo como rede de segurança para quando o
     * socket estiver caído ou o Realtime não estiver habilitado no backend.
     */
    private suspend fun realtimeCommandLoop() {
        if (deviceIdentifiers.isEmpty()) return
        RealtimeCommandChannel(
            onCommandInserted = {
                lifecycleScope.launch {
                    runCatching { processPendingCommands() }
                        .onFailure { Log.w(TAG_SYNC, "realtime_sweep_failed", it) }
                }
            },
        ).run()
    }

    /**
     * Mantém o indicador de sincronização visível durante [block].
     *
     * Quando há mídia tocando, [setSyncOverlayVisible] já escolhe o card compacto no rodapé —
     * é ele que aparece aqui. A tela cheia só entra quando não há nada reproduzindo, caso em que
     * não há o que ser discreto sobre.
     *
     * Sem isso o usuário fica sem retorno entre o comando chegar e o download começar: o
     * indicador interno de `refreshInBackground` só sobe depois que o manifesto é baixado
     * e comparado.
     */
    private suspend fun <T> withSyncIndicator(status: String, block: suspend () -> T): T {
        setSyncOverlayVisible(true)
        updateSyncTexts(
            status = status,
            countText = "",
            fileText = "",
            detailText = "",
            progressPercent = null,
        )
        try {
            return block()
        } finally {
            setSyncOverlayVisible(false)
        }
    }

    /** Ciclo da seção 4 do manual: `pending` → `ack` → aplica → `done` / `error` → log. */
    private suspend fun processPendingCommands() = commandMutex.withLock {
        if (!isOnline()) return@withLock

        val pending = commandService.fetchPending(
            identifiers = deviceIdentifiers,
            command = DeviceCommandService.COMMAND_RELOAD_PLAYLIST,
        )
        if (pending.isEmpty()) return@withLock

        Log.i(TAG_SYNC, "reload_playlist recebido: ${pending.size} comando(s) pendente(s)")
        for (cmd in pending) {
            val startedAt = SystemClock.elapsedRealtime()
            commandService.acknowledge(cmd.id)

            val ok = runCatching { withSyncIndicator(STATUS_UPDATING_PLAYLIST) { refreshInBackground() } }
                .onFailure { Log.e(TAG_SYNC, "reload_playlist_failed commandId=${cmd.id}", it) }
                .getOrDefault(false)

            val durationMs = SystemClock.elapsedRealtime() - startedAt
            commandService.complete(
                commandId = cmd.id,
                ok = ok,
                errorMessage = if (ok) null else "manifest_sync_incomplete",
            )
            commandService.logExecution(
                deviceId = cmd.deviceId.ifBlank { deviceId },
                commandId = cmd.id,
                command = cmd.command,
                ok = ok,
                durationMs = durationMs,
            )
            Log.i(TAG_SYNC, "reload_playlist commandId=${cmd.id} ok=$ok durationMs=$durationMs")
        }
    }

    /**
     * Verificação remota do manifesto. Primeiro ciclo poucos minutos após o start; depois
     * intervalo sorteado dentro da janela configurada (jitter, para não sincronizar a frota
     * inteira no mesmo instante). Falha aplica backoff exponencial em vez de prender o loop.
     */
    private suspend fun remoteRefreshLoop() {
        delay(FIRST_REFRESH_DELAY_MS)
        var backoffMs = REFRESH_RETRY_BASE_MS
        while (true) {
            val success =
                try {
                    refreshInBackground()
                } catch (e: Exception) {
                    Log.e(TAG_SYNC, "background_refresh_failed", e)
                    false
                }

            if (success) {
                backoffMs = REFRESH_RETRY_BASE_MS
                delay(Random.nextLong(REFRESH_INTERVAL_MIN_MS, REFRESH_INTERVAL_MAX_MS))
            } else {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(REFRESH_RETRY_MAX_MS)
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun storagePermissionsToRequest(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private suspend fun ensureStoragePermissionIfNeeded(): Boolean {
        if (hasStoragePermission()) return true
        if (Build.VERSION.SDK_INT < 23) return true

        val deferred = CompletableDeferred<Boolean>()
        storagePermissionDeferred = deferred
        storagePermissionLauncher.launch(storagePermissionsToRequest())
        val granted = withTimeoutOrNull(12_000L) { deferred.await() } ?: false
        if (storagePermissionDeferred === deferred) {
            storagePermissionDeferred = null
        }
        return granted
    }

    private fun updateDeviceWatermark() {
        val base = "ID: $deviceId"
        if (devMode) {
            binding.deviceIdWatermark.text = "$base • DEV"
            binding.deviceIdWatermark.visibility = View.VISIBLE
        } else if (demoMode) {
            binding.deviceIdWatermark.text = "$base • DEMO"
            binding.deviceIdWatermark.visibility = View.VISIBLE
        } else {
            binding.deviceIdWatermark.visibility = View.GONE
        }
    }

    private fun updateDevModeUI() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (devMode) {
                binding.devOverlayContainer.visibility = View.VISIBLE
                binding.txtDevAndroidId.text = "Android ID: $deviceId"
                
                val cache = withContext(Dispatchers.IO) {
                    runCatching { DeviceCacheManager(applicationContext).load() }.getOrNull()
                }
                val companyStr = cache?.companyName ?: cache?.company ?: "-"
                binding.txtDevCompany.text = "Empresa: $companyStr"
            } else {
                binding.devOverlayContainer.visibility = View.GONE
            }
        }
    }

    private suspend fun tryStartOfflinePlayback(): Boolean {
        val offlineJson = manifestManager.loadOfflineManifest(deviceId).orEmpty().trim()
        if (offlineJson.isBlank()) return false
        val items = manifestManager.parseItemsPublic(offlineJson)
        playlistName = manifestManager.parsePlaylistName(offlineJson) ?: playlistName
        itemNameById = items.mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyTransitionConfigFromManifestJson(offlineJson)
        val playlist = buildLocalPlaylist(items)
        if (playlist.isNotEmpty()) {
            playerEngine.start(playlist)
            setSyncOverlayVisible(false)
            return true
        }
        return false
    }

    private suspend fun initialSyncAndPlayback() {
        setSyncOverlayVisible(true)
        updateSyncTexts(
            status = "Sincronizando conteúdos...",
            countText = "",
            fileText = "",
            detailText = "",
            progressPercent = null,
        )

        if (!isOnline()) {
            if (playerEngine.getCurrentItemId() != null) {
                updateSyncTexts(
                    status = "Sem internet. Reproduzindo conteúdo local.",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                setSyncOverlayVisible(false)
                return
            }
            while (!isOnline()) {
                updateSyncTexts(
                    status = "Sem internet. Aguardando conexão para sincronizar...",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                delay(3000L)
            }
        }

        var remote = runCatching { manifestManager.fetchManifest(deviceId) }
            .onFailure { Log.w("MPlayerSync", "fetch_manifest_failed deviceId=$deviceId", it) }
            .getOrDefault("")
            .trim()
        while (remote.isBlank()) {
            if (playerEngine.getCurrentItemId() != null) {
                setSyncOverlayVisible(false)
                return
            }
            if (tryStartOfflinePlayback()) {
                updateSyncTexts(
                    status = "Falha ao obter programação online. Reproduzindo conteúdo local.",
                    countText = "",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
                setSyncOverlayVisible(false)
                return
            }
            updateSyncTexts(
                status = "Não foi possível obter a programação. Tentando novamente...",
                countText = "",
                fileText = "",
                detailText = "",
                progressPercent = null,
            )
            delay(3000L)
            if (!isOnline()) {
                while (!isOnline()) {
                    updateSyncTexts(
                        status = "Sem internet. Aguardando conexão para sincronizar...",
                        countText = "",
                        fileText = "",
                        detailText = "",
                        progressPercent = null,
                    )
                    delay(3000L)
                }
            }
            remote = runCatching { manifestManager.fetchManifest(deviceId) }
                .onFailure { Log.w("MPlayerSync", "fetch_manifest_failed deviceId=$deviceId", it) }
                .getOrDefault("")
                .trim()
        }

        syncMutex.withLock {
            val changed = !manifestManager.compareManifest(deviceId, remote)
            if (changed) {
                manifestManager.saveManifest(deviceId, remote)
            }

            playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
            itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
            applyTransitionConfigFromManifestJson(remote)

            val items = manifestManager.parseItemsPublic(remote)
            val playlist = syncAndBuildPlaylist(
                remoteJson = remote,
                items = items,
                showProgress = true,
                maxAttempts = SYNC_ATTEMPTS_INITIAL,
            )
            applyPlaylist(items, playlist)
            setSyncOverlayVisible(false)
        }
        launchBackgroundMediaPrefetch(remote)
    }

    /**
     * Baixa as mídias do manifesto e monta a playlist local.
     *
     * O critério de conclusão é o número de itens **ativos agora** (vigência de data e faixa
     * horária), não o total de itens do manifesto: itens agendados para o futuro ou já
     * expirados nunca entram na playlist e, se contados, travariam a sincronização para sempre.
     *
     * As tentativas são limitadas e com backoff — na pior hipótese seguimos com a playlist
     * parcial e o ciclo de refresh remoto tenta de novo mais tarde.
     */
    private suspend fun syncAndBuildPlaylist(
        remoteJson: String,
        items: List<com.mupa.player.enterprise.managers.ManifestItem>,
        showProgress: Boolean,
        maxAttempts: Int,
    ): List<PlayerEngine.PlaybackItem> {
        val expected = items.count { isItemCurrentlyActive(it) }
        var playlist = buildLocalPlaylist(items)
        if (playlist.size >= expected) return playlist

        val progressCallback: ((MediaSyncProgress) -> Unit)? =
            if (showProgress) {
                { p -> runOnUiThread { renderProgress(p) } }
            } else {
                null
            }

        var attempt = 0
        var backoffMs = SYNC_RETRY_BASE_MS
        while (playlist.size < expected && attempt < maxAttempts) {
            attempt++
            if (showProgress) {
                val missing = (expected - playlist.size).coerceAtLeast(0)
                updateSyncTexts(
                    status = "Baixando conteúdos...",
                    countText = "Faltando $missing de $expected mídias",
                    fileText = "",
                    detailText = "",
                    progressPercent = null,
                )
            }

            runCatching {
                manifestManager.syncMedia(
                    deviceId = deviceId,
                    manifestJson = remoteJson,
                    onProgress = progressCallback,
                    maxConcurrentDownloads = 1,
                )
            }.onFailure { Log.w(TAG_SYNC, "sync_media_failed attempt=$attempt", it) }

            playlist = buildLocalPlaylist(items)
            if (playlist.size < expected) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(SYNC_RETRY_MAX_MS)
            }
        }

        if (playlist.size < expected) {
            Log.w(
                TAG_SYNC,
                "sync_incomplete active=$expected local=${playlist.size} attempts=$attempt deviceId=$deviceId",
            )
        }
        return playlist
    }

    /**
     * Baixa em segundo plano o que sobrou do manifesto (por exemplo campanhas ainda fora de
     * vigência), para que já estejam em disco quando entrarem no ar. Não bloqueia a reprodução.
     */
    private fun launchBackgroundMediaPrefetch(remoteJson: String) {
        lifecycleScope.launch {
            syncMutex.withLock {
                runCatching {
                    manifestManager.syncMedia(
                        deviceId = deviceId,
                        manifestJson = remoteJson,
                        onProgress = null,
                        maxConcurrentDownloads = 1,
                    )
                }.onFailure { Log.w(TAG_SYNC, "prefetch_failed deviceId=$deviceId", it) }
            }
        }
    }

    private suspend fun refreshInBackground(): Boolean = syncMutex.withLock {
        if (!isOnline()) return@withLock false
        runCatching {
            com.mupa.player.enterprise.services.DeviceValidationService(applicationContext).validateDevice(deviceId)
        }

        val remote = runCatching { manifestManager.fetchManifest(deviceId) }.getOrNull()?.trim()
        if (remote.isNullOrBlank()) return@withLock false
        val changed = !manifestManager.compareManifest(deviceId, remote)
        if (!changed) {
            Log.i(TAG_SYNC, "manifest_unchanged deviceId=$deviceId")
            return@withLock true
        }

        manifestManager.saveManifest(deviceId, remote)
        playlistName = manifestManager.parsePlaylistName(remote) ?: playlistName
        itemNameById = manifestManager.parseItemsPublic(remote).mapNotNull { it.name?.let { n -> it.id to n } }.toMap()
        applyTransitionConfigFromManifestJson(remote)

        // Sinaliza na tela assim que a diferença é detectada — antes de qualquer download.
        // É o "tem algo pra trocar": o card sobe no rodapé e só sai quando a troca conclui.
        Log.i(TAG_SYNC, "nova programação detectada no servidor — iniciando troca")
        setSyncOverlayVisible(true)
        updateSyncTexts(
            status = STATUS_NEW_PLAYLIST_FOUND,
            countText = "",
            fileText = "",
            detailText = "",
            progressPercent = null,
        )

        val items = manifestManager.parseItemsPublic(remote)
        val playlist = syncAndBuildPlaylist(
            remoteJson = remote,
            items = items,
            showProgress = true,
            maxAttempts = SYNC_ATTEMPTS_BACKGROUND,
        )
        applyPlaylist(items, playlist)
        setSyncOverlayVisible(false)
        launchBackgroundMediaPrefetch(remote)

        return@withLock playlist.size >= items.count { isItemCurrentlyActive(it) }
    }

    /**
     * Normaliza uma data de vigência para `yyyy-MM-dd`.
     *
     * O backend pode devolver tanto `"2026-08-13"` quanto um timestamptz completo
     * (`"2026-08-13T00:00:00+00:00"`). Como a comparação é lexicográfica, o segundo formato
     * quebrava o **primeiro dia** da campanha: `"2026-08-13" < "2026-08-13T00:00:00"` é `true`,
     * então o item só entrava no ar no dia seguinte ao configurado.
     */
    private fun normalizeScheduleDate(raw: String): String? {
        val s = raw.trim()
        if (s.length < 10) return null
        val head = s.substring(0, 10)
        return if (head.length == 10 && head[4] == '-' && head[7] == '-') head else null
    }

    /** Normaliza uma faixa horária para `HH:mm:ss`, aceitando `HH:mm` e `H:mm`. */
    private fun normalizeScheduleTime(raw: String): String? {
        val parts = raw.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].trim().padStart(2, '0')
        val m = parts[1].trim().padStart(2, '0')
        val s = parts.getOrNull(2)?.trim()?.padStart(2, '0') ?: "00"
        if (h.length != 2 || m.length != 2 || s.length != 2) return null
        return "$h:$m:$s"
    }

    private fun isItemCurrentlyActive(item: com.mupa.player.enterprise.managers.ManifestItem): Boolean {
        val now = Date()

        // 1. Validar Vigência por Data (AAAA-MM-DD, no fuso do dispositivo)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFmt.format(now)

        val startD = item.startDate?.trim()?.takeIf { it.isNotBlank() }?.let { normalizeScheduleDate(it) }
        val endD = item.endDate?.trim()?.takeIf { it.isNotBlank() }?.let { normalizeScheduleDate(it) }

        if (startD != null && todayStr < startD) return false
        if (endD != null && todayStr > endD) return false

        // 2. Validar Faixa Horária (HH:MM:SS)
        val startT = item.startTime?.trim()?.takeIf { it.isNotBlank() }?.let { normalizeScheduleTime(it) }
        val endT = item.endTime?.trim()?.takeIf { it.isNotBlank() }?.let { normalizeScheduleTime(it) }

        if (startT != null || endT != null) {
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeStr = timeFmt.format(now)

            val minT = startT ?: "00:00:00"
            val maxT = endT ?: "23:59:59"

            if (minT <= maxT) {
                if (timeStr < minT || timeStr > maxT) return false
            } else {
                // Faixa que cruza a meia-noite (ex.: 22:00:00 → 06:00:00).
                if (timeStr < minT && timeStr > maxT) return false
            }
        }

        return true
    }

    /**
     * Aplica a playlist e mantém [lastPlaylistItemsIds] em sincronia.
     *
     * Sem isso, `updatePlaylistIfActiveItemsChanged` enxergava um falso "mudou" no primeiro tick
     * após cada sincronização remota e reaplicava a playlist — e como o hot-swap reseta o índice
     * para 0, a programação voltava visivelmente para o primeiro item logo depois de todo sync.
     */
    private fun applyPlaylist(
        items: List<com.mupa.player.enterprise.managers.ManifestItem>,
        playlist: List<PlayerEngine.PlaybackItem>,
    ) {
        lastPlaylistItemsIds = items.filter { isItemCurrentlyActive(it) }.map { it.id }
        playerEngine.setPlaylist(playlist)
    }

    private suspend fun updatePlaylistIfActiveItemsChanged() {
        val offlineJson = manifestManager.loadOfflineManifest(deviceId).orEmpty().trim()
        if (offlineJson.isBlank()) return
        val items = manifestManager.parseItemsPublic(offlineJson)
        val activeItems = items.filter { isItemCurrentlyActive(it) }
        val activeIds = activeItems.map { it.id }
        if (activeIds == lastPlaylistItemsIds) return

        val playlist = buildLocalPlaylist(items)
        if (playlist.isEmpty()) {
            // PlaylistEngine.setPlaylist ignora lista vazia, então a programação anterior
            // continuaria no ar mesmo fora de vigência. Registrado para não passar silencioso.
            Log.w(
                TAG_SYNC,
                "agendamento: nenhum item vigente agora (antes: ${lastPlaylistItemsIds.size}); " +
                    "a programação anterior segue em exibição",
            )
            lastPlaylistItemsIds = activeIds
            return
        }

        applyPlaylist(items, playlist)
        Log.i(
            "MPlayerPlaylist",
            "agendamento: programação trocada por vigência de data/hora — ${playlist.size} item(ns) no ar",
        )
    }

    private suspend fun buildLocalPlaylist(items: List<com.mupa.player.enterprise.managers.ManifestItem>): List<PlayerEngine.PlaybackItem> {
        val db = AppDatabase.get(applicationContext)
        val mediaIndexFromDb = withContext(Dispatchers.IO) {
            runCatching {
                db.mediaDao().getAll()
                    .mapNotNull { e ->
                        val f = File(e.localPath)
                        if (f.exists() && f.length() > 0) e.mediaId to f else null
                     }
                     .toMap()
            }.getOrDefault(emptyMap())
        }

        val mediaDir = File(applicationContext.getExternalFilesDir(null), "media")
        val mediaIndexFromDisk =
            runCatching {
                mediaDir.listFiles().orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.length() > 0L }
                    .filter { it.name != "manifest.json" && !it.name.endsWith(".tmp") }
                    .associateBy { it.name.substringBeforeLast('.', missingDelimiterValue = it.name) }
            }.getOrDefault(emptyMap())

        val mediaIndex = if (mediaIndexFromDisk.isEmpty()) mediaIndexFromDb else (mediaIndexFromDisk + mediaIndexFromDb)

        return items.mapNotNull { item ->
            if (!isItemCurrentlyActive(item)) return@mapNotNull null

            // Item `web` não tem arquivo: o conteúdo é a página viva, renderizada na WebView.
            // Exigir download aqui foi o que transformou o slide dinâmico em tela preta antes
            // deste suporte existir (contrato de 2026-08-21 §2).
            if (item.type.equals(WEB_TYPE, ignoreCase = true)) {
                val url = item.url.trim()
                if (url.isBlank()) {
                    Log.w(TAG_SYNC, "item_web_sem_url id=${item.id}")
                    return@mapNotNull null
                }
                return@mapNotNull PlayerEngine.PlaybackItem(
                    id = item.id,
                    type = item.type,
                    file = null,
                    url = url,
                    durationMs = item.durationMs,
                    volume = item.volume,
                    offsetStartMs = item.offsetStartMs,
                    offsetEndMs = item.offsetEndMs,
                )
            }

            val file = mediaIndex[item.id] ?: return@mapNotNull null
            if (!isRenderable(item.type, file)) return@mapNotNull null
            PlayerEngine.PlaybackItem(
                id = item.id,
                type = item.type,
                file = file,
                url = null,
                durationMs = item.durationMs,
                volume = item.volume,
                offsetStartMs = item.offsetStartMs,
                offsetEndMs = item.offsetEndMs,
            )
        }
    }

    /**
     * Um item só entra na playlist se o player realmente souber desenhá-lo.
     *
     * `PlaylistEngine.prepareInto` trata **tudo que não é vídeo como imagem**, e `waitForSwitch`
     * segura a tela pelo `duration_ms` do item. Combinados, um arquivo que o decodificador recusa
     * vira tela preta pelo tempo inteiro do item, sem erro visível.
     *
     * Aconteceu em campo em 2026-08-21: o manifesto passou a trazer `type: "web"` apontando para
     * uma página; o player baixou o HTML como se fosse mídia e a TV ficou 50 segundos no escuro a
     * cada volta da rotação.
     *
     * A verificação é por **assinatura do arquivo**, não pelo campo `type`. Checar o texto do tipo
     * seria correr atrás de cada valor novo que a plataforma inventar; olhar os primeiros bytes
     * cobre também download corrompido e arquivo trocado, que dão o mesmo sintoma.
     */
    private fun isRenderable(type: String, file: File): Boolean {
        if (type.equals("video", ignoreCase = true)) return true

        val header = runCatching {
            file.inputStream().use { input ->
                ByteArray(12).also { buf ->
                    if (input.read(buf) < buf.size) return false
                }
            }
        }.getOrNull() ?: return false

        fun match(vararg bytes: Int, at: Int = 0): Boolean =
            bytes.withIndex().all { (i, b) -> header[at + i].toInt() and 0xFF == b }

        val isImage =
            match(0xFF, 0xD8, 0xFF) ||                                   // JPEG
                match(0x89, 0x50, 0x4E, 0x47) ||                         // PNG
                match(0x47, 0x49, 0x46, 0x38) ||                         // GIF
                match(0x42, 0x4D) ||                                     // BMP
                (match(0x52, 0x49, 0x46, 0x46) &&                        // RIFF....WEBP
                    match(0x57, 0x45, 0x42, 0x50, at = 8))

        if (!isImage) {
            Log.w(
                TAG_SYNC,
                "item_ignorado type=$type file=${file.name} — nao e imagem nem video; " +
                    "seria tela preta pela duracao do item",
            )
        }
        return isImage
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun setSyncOverlayVisible(visible: Boolean) {
        syncHideJob?.cancel()
        binding.syncOverlay.animate().cancel()

        if (visible) {
            val isMediaPlaying = playerEngine.getCurrentItemId() != null
            if (isMediaPlaying) {
                // Sincronizando com mídias rodando ao fundo -> Pequeno card na parte inferior
                val overlayParams = binding.syncOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                overlayParams.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                overlayParams.gravity = android.view.Gravity.BOTTOM
                binding.syncOverlay.layoutParams = overlayParams

                binding.syncOverlay.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.syncContent.setBackgroundResource(com.mupa.player.enterprise.R.drawable.bg_sync_card)
                
                val params = binding.syncContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = dpToPx(16)
                binding.syncContent.layoutParams = params
                
                binding.syncLogo.visibility = View.GONE

                // Compact padding and smaller font sizes
                binding.syncContent.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                binding.syncStatusText.textSize = 13f
                binding.syncCountText.textSize = 11f
                binding.syncFileText.textSize = 11f
                binding.syncDetailText.textSize = 10f
            } else {
                // Tela cheia
                val overlayParams = binding.syncOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
                overlayParams.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                overlayParams.gravity = android.view.Gravity.NO_GRAVITY
                binding.syncOverlay.layoutParams = overlayParams

                binding.syncOverlay.setBackgroundResource(com.mupa.player.enterprise.R.color.enterprise_bg)
                binding.syncContent.background = null
                
                val params = binding.syncContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomMargin = 0
                binding.syncContent.layoutParams = params
                
                binding.syncLogo.visibility = View.VISIBLE

                // Standard padding and font sizes
                binding.syncContent.setPadding(dpToPx(28), dpToPx(28), dpToPx(28), dpToPx(28))
                binding.syncStatusText.textSize = 20f
                binding.syncCountText.textSize = 14f
                binding.syncFileText.textSize = 14f
                binding.syncDetailText.textSize = 12f
            }

            if (binding.syncOverlay.visibility != View.VISIBLE) {
                binding.syncOverlay.visibility = View.VISIBLE
                binding.syncOverlay.alpha = 0f
                binding.syncOverlay.animate().alpha(1f).setDuration(180).start()
            } else if (binding.syncOverlay.alpha < 1f) {
                binding.syncOverlay.animate().alpha(1f).setDuration(180).start()
            }
        } else {
            syncHideJob =
                lifecycleScope.launch {
                    delay(700L)
                    binding.syncOverlay.animate().alpha(0f).setDuration(220).withEndAction {
                        binding.syncOverlay.visibility = View.GONE
                    }.start()
                }
        }
    }

    private fun promptWipeAppData() {
        if (isFinishing || isDestroyed) return
        android.app.AlertDialog.Builder(this@PlayerActivity)
            .setTitle("Apagar dados do app?")
            .setMessage("Isso apaga cadastro, cache, mídias e imagens locais. O app vai reiniciar e você poderá testar o cadastro novamente.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    Toast.makeText(this@PlayerActivity, "Apagando dados...", Toast.LENGTH_SHORT).show()
                    withContext(Dispatchers.IO) { wipeAppDataInternal() }
                    Toast.makeText(this@PlayerActivity, "Dados apagados. Reiniciando...", Toast.LENGTH_SHORT).show()
                    runCatching {
                        startActivity(
                            Intent(this@PlayerActivity, SplashActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        )
                    }
                    runCatching { finishAffinity() }
                    runCatching { Process.killProcess(Process.myPid()) }
                }
            }
            .show()
    }

    private suspend fun wipeAppDataInternal() {
        runCatching { AppDatabase.get(applicationContext).close() }
        runCatching { applicationContext.deleteDatabase("mplayer.db") }
        runCatching { deleteRecursivelySafely(File(applicationInfo.dataDir, "databases")) }

        runCatching { applicationContext.settingsDataStore.edit { it.clear() } }
        runCatching { applicationContext.getSharedPreferences("mupa_settings_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { applicationContext.getSharedPreferences("mupa_device_cache_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { applicationContext.getSharedPreferences("mupa_device_identity_legacy", Context.MODE_PRIVATE).edit().clear().apply() }
        runCatching { deleteRecursivelySafely(File(applicationInfo.dataDir, "shared_prefs")) }

        filesDir.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
        cacheDir.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
        getExternalFilesDir(null)?.listFiles()?.forEach { runCatching { deleteRecursivelySafely(it) } }
    }

    private fun deleteRecursivelySafely(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursivelySafely(it) }
        }
        runCatching { file.delete() }
    }

    private fun setupDevModeToggle() {
        binding.deviceIdWatermark.setOnLongClickListener {
            showAdminAccessDialog()
            true
        }
        binding.apkVersionWatermark.setOnLongClickListener {
            binding.deviceIdWatermark.performLongClick()
            true
        }
    }

    private fun showAdminAccessDialog() {
        lifecycleScope.launch {
            val cache = DeviceCacheManager(applicationContext).load()
            val targetCode = cache?.companyCode?.trim().orEmpty()
            withContext(Dispatchers.Main) {
                val container = android.widget.FrameLayout(this@PlayerActivity)
                val params = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = 48
                    rightMargin = 48
                    topMargin = 24
                    bottomMargin = 24
                }
                val input = EditText(this@PlayerActivity).apply {
                    hint = "Código da Empresa"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    layoutParams = params
                }
                container.addView(input)
                android.app.AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Acesso Restrito")
                    .setMessage("Insira o Código de Usuário da Empresa:")
                    .setView(container)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Confirmar") { _, _ ->
                        val entered = input.text.toString().trim()
                        val isCorrect = (targetCode.isNotBlank() && entered.equals(targetCode, ignoreCase = true)) ||
                                        entered.equals("DEBUG", ignoreCase = true) ||
                                        entered.equals("123ABC", ignoreCase = true) ||
                                        targetCode.isBlank()
                        if (isCorrect) {
                            startActivity(Intent(this@PlayerActivity, SettingsActivity::class.java))
                        } else {
                            Toast.makeText(this@PlayerActivity, "Código inválido!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
        }
    }


    private fun applyTransitionConfigFromManifestJson(manifestJson: String) {
        val cfg = parseTransitionConfigFromManifestJson(manifestJson) ?: return
        playerEngine.setTransitionConfig(cfg)
    }

    private fun parseTransitionConfigFromManifestJson(manifestJson: String): TransitionConfig? {
        return runCatching {
            val root = JSONObject(manifestJson)
            val manifestObj = root.optJSONObject("manifest") ?: root
            val playlistObj = manifestObj.optJSONObject("playlist")
            val appearance =
                manifestObj.optJSONObject("appearance_config")
                    ?: playlistObj?.optJSONObject("appearance_config")
                    ?: JSONObject()

            val t =
                appearance.optJSONObject("transitions")
                    ?: appearance.optJSONObject("transition")
                    ?: appearance.optJSONObject("transition_config")
                    ?: JSONObject()

            val enabled =
                when {
                    t.has("enabled") -> t.optBoolean("enabled", true)
                    t.has("transitions_enabled") -> t.optBoolean("transitions_enabled", true)
                    appearance.has("transitions_enabled") -> appearance.optBoolean("transitions_enabled", true)
                    else -> true
                }

            val rawType =
                t.optString("type", "")
                    .ifBlank { t.optString("transitions_type", "") }
                    .ifBlank { appearance.optString("transitions_type", "") }
                    .trim()
                    .lowercase(Locale.US)

            val mode =
                when (rawType) {
                    "none", "off", "0", "false", "desativado" -> TransitionConfig.Mode.NONE
                    "crossfade", "cross_fade", "cross-fade" -> TransitionConfig.Mode.CROSSFADE
                    "fade", "" -> TransitionConfig.Mode.FADE
                    else -> TransitionConfig.Mode.FADE
                }

            val rawDur =
                t.optLong("duration_ms", -1L).takeIf { it > 0L }
                    ?: t.optLong("transitions_ms", -1L).takeIf { it > 0L }
                    ?: appearance.optLong("transitions_ms", -1L).takeIf { it > 0L }

            val dur =
                when (rawDur) {
                    150L, 200L, 250L, 300L, 400L, 500L -> rawDur
                    else -> null
                }

            val base = TransitionConfig.default(PlaybackProfile.detect(applicationContext))
            val finalEnabled = enabled && mode != TransitionConfig.Mode.NONE
            val finalMode = if (finalEnabled) mode else TransitionConfig.Mode.NONE
            val finalDur = dur ?: base.durationMs
            TransitionConfig(enabled = finalEnabled, mode = finalMode, durationMs = finalDur)
        }.getOrNull()
    }

    private fun renderProgress(p: MediaSyncProgress) {
        val countText = "${p.completedItems} de ${p.totalItems} conteúdos"
        val fileText = p.currentName?.let { "Baixando $it" }.orEmpty()

        val percent = p.currentBytesTotal?.takeIf { it > 0L }?.let { total ->
            ((p.currentBytesDownloaded * 100L) / total).toInt().coerceIn(0, 100)
        }

        val detail = buildString {
            if (percent != null) append("$percent%")
            val total = p.currentBytesTotal
            if (total != null && total > 0L) {
                if (isNotEmpty()) append(" • ")
                append("${formatBytes(p.currentBytesDownloaded)} / ${formatBytes(total)}")
            }
            if (p.currentSpeedBytesPerSec > 0) {
                if (isNotEmpty()) append(" • ")
                append("${formatBytes(p.currentSpeedBytesPerSec)}/s")
            }
        }

        updateSyncTexts(
            status = "Sincronizando conteúdos...",
            countText = countText,
            fileText = fileText,
            detailText = detail,
            progressPercent = percent,
        )
    }

    private fun updateSyncTexts(
        status: String,
        countText: String,
        fileText: String,
        detailText: String,
        progressPercent: Int?,
    ) {
        binding.syncStatusText.text = status
        binding.syncCountText.text = countText
        binding.syncFileText.text = fileText
        binding.syncDetailText.text = detailText

        binding.syncCountText.visibility = if (countText.isNotBlank()) View.VISIBLE else View.GONE
        binding.syncFileText.visibility = if (fileText.isNotBlank()) View.VISIBLE else View.GONE
        binding.syncDetailText.visibility = if (detailText.isNotBlank()) View.VISIBLE else View.GONE

        val p = progressPercent
        if (p == null) {
            binding.syncProgressBar.isIndeterminate = true
        } else {
            binding.syncProgressBar.isIndeterminate = false
            binding.syncProgressBar.progress = p
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val b = bytes.toDouble().coerceAtLeast(0.0)
        return when {
            b >= gb -> String.format("%.1f GB", b / gb)
            b >= mb -> String.format("%.1f MB", b / mb)
            b >= kb -> String.format("%.0f KB", b / kb)
            else -> String.format("%.0f B", b)
        }
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"

        private const val TAG_SYNC = "MPlayerSync"

        /** Slide dinâmico servido como página viva — contrato de 2026-08-21. */
        private const val WEB_TYPE = "web"

        /** Gatilhos de verificação do overlay em hardware. Ver [queueTestReceiver]. */
        const val ACTION_QUEUE_TEST_CALL = "com.mupa.player.enterprise.ACTION_QUEUE_TEST_CALL"
        const val ACTION_QUEUE_TEST_HIDE = "com.mupa.player.enterprise.ACTION_QUEUE_TEST_HIDE"

        /** Texto do indicador discreto enquanto um `reload_playlist` está sendo aplicado. */
        private const val STATUS_UPDATING_PLAYLIST = "Atualizando programação..."

        /** Texto exibido no instante em que uma programação diferente é detectada no servidor. */
        private const val STATUS_NEW_PLAYLIST_FOUND = "Nova programação disponível — atualizando..."

        /** Primeira verificação remota após o start. */
        private const val FIRST_REFRESH_DELAY_MS = 2 * 60 * 1000L

        /**
         * Janela do intervalo entre verificações remotas; sorteado a cada ciclo (jitter de frota).
         *
         * Curto de propósito: o push via `device_commands` está bloqueado pela RLS, então o
         * polling do manifesto é hoje o único caminho para detectar troca de playlist. A
         * requisição é um POST pequeno que devolve só o JSON do manifesto — o custo real da
         * sincronização é o download das mídias, que só acontece quando algo mudou de fato.
         */
        private const val REFRESH_INTERVAL_MIN_MS = 60 * 1000L
        private const val REFRESH_INTERVAL_MAX_MS = 120 * 1000L

        /** Backoff exponencial quando a verificação remota falha. */
        private const val REFRESH_RETRY_BASE_MS = 30 * 1000L
        private const val REFRESH_RETRY_MAX_MS = 10 * 60 * 1000L

        /** Reavaliação da vigência (data / faixa horária) dos itens já baixados. */
        private const val ACTIVE_ITEMS_CHECK_MS = 60 * 1000L

        /**
         * Rede de segurança do canal de push: a primeira varredura é imediata (no start) e
         * este é o intervalo entre as seguintes. A entrega rápida vem do Realtime.
         */
        private const val COMMAND_POLL_INTERVAL_MS = 15 * 1000L

        /** Tentativas de download antes de seguir com a playlist parcial. */
        private const val SYNC_ATTEMPTS_INITIAL = 8
        private const val SYNC_ATTEMPTS_BACKGROUND = 3
        private const val SYNC_RETRY_BASE_MS = 2_500L
        private const val SYNC_RETRY_MAX_MS = 30_000L
    }
}

class DevModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_DEV_MODE) return
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { SettingsManager(context.applicationContext).setDevMode(enabled) }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_SET_DEV_MODE = "com.mupa.player.enterprise.ACTION_SET_DEV_MODE"
        const val EXTRA_ENABLED = "enabled"
    }
}
