package com.mupa.player.enterprise.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.R
import com.mupa.player.enterprise.databinding.ActivityQueueSetupBinding
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.network.QueueApiClient
import com.mupa.player.enterprise.network.QueueApiResult
import com.mupa.player.enterprise.queue.QueueAction
import com.mupa.player.enterprise.queue.QueueDeviceConfig
import com.mupa.player.enterprise.queue.QueueKeymap
import com.mupa.player.enterprise.queue.QueueStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Configuração do módulo de fila no X96: pareamento, teclas do controle e PIN.
 *
 * Protegida por PIN — mitigação 2 de ARQUITETURA §8.1. Sem isso, qualquer pessoa com um controle
 * genérico compraria acesso à configuração do aparelho no meio da loja.
 *
 * O aprendizado de tecla é por **captura**, não por lista fixa de keycodes: controles de X96 variam
 * entre lotes e fornecedores, e uma lista em código exigiria build nova a cada controle
 * desconhecido (ARQUITETURA §8).
 */
class QueueSetupActivity : ComponentActivity() {

    private lateinit var binding: ActivityQueueSetupBinding
    private val store by lazy { QueueStore(applicationContext) }
    private val api by lazy { QueueApiClient { store.credentials() } }

    /** Ação aguardando captura de tecla. `null` = operação normal da tela. */
    private var capturing: QueueAction? = null
    private var captureTimeoutJob: Job? = null

    private var keymap = QueueKeymap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQueueSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnQueueClose.setOnClickListener { finish() }
        askPin()
    }

    // -------------------------------------------------------------------------------------------
    // PIN
    // -------------------------------------------------------------------------------------------

    private fun askPin() {
        val input = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Acesso restrito")
            .setMessage("Informe o PIN de configuração da fila.")
            .setView(input)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setPositiveButton("Entrar", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        lifecycleScope.launch {
                            if (input.text.toString().trim() == store.pin()) {
                                dialog.dismiss()
                                loadScreen()
                            } else {
                                Toast.makeText(
                                    this@QueueSetupActivity,
                                    "PIN incorreto",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    // -------------------------------------------------------------------------------------------
    // Carga da tela
    // -------------------------------------------------------------------------------------------

    private fun loadScreen() {
        lifecycleScope.launch {
            val creds = store.credentials()
            val serialSugerido = creds?.serial
                ?: DeviceIdentityManager(applicationContext).getPersistentId().trim()

            binding.editQueueSerial.setText(serialSugerido)
            binding.txtPinWarning.visibility =
                if (store.isDefaultPin()) android.view.View.VISIBLE else android.view.View.GONE

            keymap = store.localKeymap()
            val cached = store.cachedConfig()
            if (cached != null) mergeServerKeymap(cached)
            renderKeymap()
            renderStatus(cached, paired = creds != null)

            setupListeners()
        }
    }

    /**
     * O mapa do painel preenche só o que a captura local não definiu — a mesma precedência do
     * [com.mupa.player.enterprise.queue.QueueController], para a tela não mostrar uma coisa e o
     * player obedecer outra.
     */
    private fun mergeServerKeymap(config: QueueDeviceConfig) {
        keymap = QueueKeymap(
            play = keymap.play ?: config.keymap.play,
            next = keymap.next ?: config.keymap.next,
            back = keymap.back ?: config.keymap.back,
        )
    }

    private fun renderStatus(config: QueueDeviceConfig?, paired: Boolean) {
        binding.txtQueueStatus.text = buildString {
            append("Pareado: ").append(if (paired) "sim" else "não").append('\n')
            if (config == null) {
                append("Nenhuma configuração recebida do servidor ainda.")
            } else {
                append("Setor: ").append(config.sectorName.ifBlank { "-" }).append('\n')
                append("Papel: ").append(config.role.name).append('\n')
                append("Prefixo: ").append(config.prefix.ifBlank { "-" }).append('\n')
                append("Fila ativa: ").append(if (config.isActive) "sim" else "não").append('\n')
                append("Overlay: ").append(config.overlayDurationMs).append(" ms").append('\n')
                append("Data de serviço: ").append(config.serviceDate.ifBlank { "-" })
            }
        }
    }

    private fun renderKeymap() {
        binding.txtKeyPlay.text = keyLabel("PLAY — rechamar a senha atual", keymap.play)
        binding.txtKeyNext.text = keyLabel("NEXT — próxima senha", keymap.next)
        binding.txtKeyBack.text = keyLabel("BACK — voltar uma senha", keymap.back)
    }

    private fun keyLabel(title: String, keyCode: Int?): String =
        if (keyCode == null) "$title\nnenhuma tecla capturada"
        else "$title\n${KeyEvent.keyCodeToString(keyCode)} (código $keyCode)"

    // -------------------------------------------------------------------------------------------
    // Ações
    // -------------------------------------------------------------------------------------------

    private fun setupListeners() {
        binding.btnCapturePlay.setOnClickListener { startCapture(QueueAction.PLAY) }
        binding.btnCaptureNext.setOnClickListener { startCapture(QueueAction.NEXT) }
        binding.btnCaptureBack.setOnClickListener { startCapture(QueueAction.BACK) }

        binding.btnClearKeymap.setOnClickListener {
            lifecycleScope.launch {
                store.saveLocalKeymap(QueueKeymap())
                keymap = QueueKeymap()
                store.cachedConfig()?.let { mergeServerKeymap(it) }
                renderKeymap()
                toast("Captura local removida. O mapeamento do painel volta a valer.")
            }
        }

        binding.btnQueuePair.setOnClickListener {
            val serial = binding.editQueueSerial.text.toString().trim()
            val secret = binding.editQueueSecret.text.toString().trim()
            if (serial.isBlank() || secret.isBlank()) {
                toast("Informe serial e segredo.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                store.saveCredentials(serial, secret)
                binding.editQueueSecret.setText("")
                validate()
            }
        }

        binding.btnQueueUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remover pareamento?")
                .setMessage("O X96 deixa de exibir e de chamar senha até ser pareado de novo. As teclas capturadas são mantidas.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover") { _, _ ->
                    lifecycleScope.launch {
                        store.clearCredentials()
                        renderStatus(null, paired = false)
                        toast("Pareamento removido.")
                    }
                }
                .show()
        }

        binding.btnQueueTest.setOnClickListener { lifecycleScope.launch { validate() } }

        binding.btnQueueSavePin.setOnClickListener {
            val pin = binding.editQueuePin.text.toString().trim()
            if (pin.length != QueueStore.PIN_LENGTH || pin.any { !it.isDigit() }) {
                toast("O PIN precisa ter ${QueueStore.PIN_LENGTH} dígitos.")
                return@setOnClickListener
            }
            if (pin == QueueStore.DEFAULT_PIN) {
                toast("Escolha um PIN diferente do padrão.")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                store.savePin(pin)
                binding.editQueuePin.setText("")
                binding.txtPinWarning.visibility = android.view.View.GONE
                toast("PIN atualizado.")
            }
        }
    }

    /** Confirma a credencial contra o servidor e guarda a configuração devolvida. */
    private suspend fun validate() {
        binding.txtQueueStatus.text = "Consultando o servidor..."
        when (val result = api.getConfig()) {
            is QueueApiResult.Ok -> {
                store.cacheConfig(result.value)
                mergeServerKeymap(result.value)
                renderKeymap()
                renderStatus(result.value, paired = true)
                toast("Credencial válida.")
            }

            is QueueApiResult.Unauthorized -> {
                renderStatus(null, paired = store.credentials() != null)
                toast("Credencial recusada pelo servidor.")
            }

            is QueueApiResult.NetworkError -> {
                renderStatus(store.cachedConfig(), paired = store.credentials() != null)
                toast("Sem conexão com o servidor.")
            }

            is QueueApiResult.ApiError -> {
                renderStatus(store.cachedConfig(), paired = store.credentials() != null)
                toast("Erro do servidor: ${result.code}")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Captura de tecla
    // -------------------------------------------------------------------------------------------

    /**
     * Entra em modo de captura por [CAPTURE_TIMEOUT_MS].
     *
     * O aviso é **inline**, não um diálogo. Um `AlertDialog` tem janela própria e recebe as teclas
     * antes da Activity — o `dispatchKeyEvent` daqui nunca seria chamado e a captura não capturaria
     * nada. O estado aparece no próprio rótulo da ação.
     *
     * O timeout é a única saída: em captura **toda** tecla é candidata, inclusive VOLTAR — que num
     * controle de X96 é uma tecla legítima para a fila. Reservar VOLTAR para cancelar tornaria
     * impossível mapeá-la.
     */
    private fun startCapture(action: QueueAction) {
        capturing = action
        setCaptureButtonsEnabled(false)
        labelFor(action).text = "${action.name} — ${getString(R.string.queue_setup_capture_hint)}"

        captureTimeoutJob?.cancel()
        captureTimeoutJob = lifecycleScope.launch {
            delay(CAPTURE_TIMEOUT_MS)
            if (capturing != null) {
                endCapture()
                // A causa mais provável não é o operador ter desistido: o ARGOS Agent intercepta
                // teclas antes de qualquer app (`ArgosAccessibilityService.onKeyEvent`), e uma
                // tecla vinculada lá simplesmente nunca chega aqui. Sem esta dica, o sintoma é
                // mudo e o instalador fica tentando a mesma tecla.
                toast(
                    "Nenhuma tecla capturada. Se o botão não responde, ele pode estar vinculado " +
                        "em Atalhos do Controle do ARGOS — libere lá e tente de novo.",
                )
            }
        }
    }

    private fun endCapture() {
        capturing = null
        captureTimeoutJob?.cancel()
        captureTimeoutJob = null
        setCaptureButtonsEnabled(true)
        renderKeymap()
    }

    private fun labelFor(action: QueueAction) = when (action) {
        QueueAction.PLAY -> binding.txtKeyPlay
        QueueAction.NEXT -> binding.txtKeyNext
        QueueAction.BACK -> binding.txtKeyBack
    }

    /**
     * Desliga os botões durante a captura.
     *
     * Não é enfeite: com um botão focado, o OK do controle seria capturado como tecla **e**
     * dispararia o clique dele. Desabilitados, eles saem da navegação por foco.
     */
    private fun setCaptureButtonsEnabled(enabled: Boolean) {
        binding.btnCapturePlay.isEnabled = enabled
        binding.btnCaptureNext.isEnabled = enabled
        binding.btnCaptureBack.isEnabled = enabled
        binding.btnClearKeymap.isEnabled = enabled
        binding.btnQueuePair.isEnabled = enabled
        binding.btnQueueUnpair.isEnabled = enabled
        binding.btnQueueTest.isEnabled = enabled
        binding.btnQueueSavePin.isEnabled = enabled
        binding.btnQueueClose.isEnabled = enabled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = capturing ?: return super.dispatchKeyEvent(event)

        // Consome DOWN e UP: deixar o UP passar faria o sistema tratar a tecla logo após a captura.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true

        val conflict = keymap.conflictFor(event.keyCode, action)
        if (conflict != null) {
            endCapture()
            toast("Essa tecla já está em ${conflict.name}. Escolha outra.")
            return true
        }

        keymap = keymap.with(action, event.keyCode)
        endCapture()
        toast("${action.name}: ${KeyEvent.keyCodeToString(event.keyCode)}")
        lifecycleScope.launch { store.saveLocalKeymap(keymap) }
        return true
    }

    override fun onDestroy() {
        endCapture()
        super.onDestroy()
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 15_000L
    }
}
