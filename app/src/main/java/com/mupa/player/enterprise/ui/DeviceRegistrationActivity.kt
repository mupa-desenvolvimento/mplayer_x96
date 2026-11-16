package com.mupa.player.enterprise.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.mupa.player.enterprise.databinding.ActivityDeviceRegistrationBinding
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Cadastro do dispositivo em passo a passo: uma pergunta por vez (empresa → apelido → filial → tags).
 * As regras (ordem, validação, pré-seleção) ficam em [RegistrationWizard] e no ViewModel; aqui só
 * se desenha o passo atual.
 */
class DeviceRegistrationActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivityDeviceRegistrationBinding
    private val viewModel: DeviceRegistrationViewModel by viewModels()

    private var lastOverlayState: Boolean = false
    private var handledSuccess: Boolean = false

    private var renderedStep: RegistrationStep? = null
    private var renderedTags: List<WizardTag>? = null

    /** True enquanto a Activity mesma mexe no texto/chips — evita eco de volta pro ViewModel. */
    private var programmatic: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val cachedId = DeviceIdentityManager(applicationContext).getCachedId().trim()
        binding.deviceIdWatermark.text = if (cachedId.isNotBlank()) "ID: $cachedId" else "ID: -"

        applyAlwaysOnScreen()

        lifecycleScope.launch {
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            if (deviceId.isBlank()) {
                // Identificação automática falhou: a primeira pergunta passa a ser o ID manual.
                viewModel.setNeedsDeviceId(true)
            } else {
                viewModel.setDeviceId(deviceId)
            }
        }

        binding.answerEdit.addTextChangedListener(
            SimpleTextWatcher { text -> if (!programmatic) onAnswerChanged(text) },
        )
        binding.answerEdit.setOnEditorActionListener { _, _, _ ->
            binding.continueButton.performClick()
            true
        }
        binding.continueButton.setOnClickListener { viewModel.onContinue() }
        binding.backButton.setOnClickListener { viewModel.onBack() }

        // Voltar volta um passo; no primeiro passo mantém o comportamento de antes (sair da tela).
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!viewModel.onBack()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        // O overlay "Reconectando..." cobre a tela inteira quando não há rede — sem esse
        // botão um box ainda não cadastrado e sem WiFi não tinha como sair dali.
        binding.offlineWifiButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                val offline = !isOnline()
                setOfflineUiVisible(offline)
                updateContinueEnabled(viewModel.uiState.value)
                delay(1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyAlwaysOnScreen()
    }

    private fun onAnswerChanged(text: String) {
        when (viewModel.uiState.value.currentStep) {
            RegistrationStep.DEVICE_ID -> viewModel.setDeviceId(text)
            RegistrationStep.COMPANY -> viewModel.setCompanyCode(text)
            RegistrationStep.NICKNAME -> viewModel.setNickname(text)
            RegistrationStep.FILIAL -> viewModel.setFilial(text)
            RegistrationStep.TAGS -> Unit
        }
    }

    private fun render(state: DeviceRegistrationUiState) {
        val total = state.steps.size
        val position = state.stepIndex.coerceIn(0, total - 1) + 1
        binding.stepCounter.text = RegistrationWizard.progressLabel(position, total)
        binding.stepProgress.setProgressCompat(position * 100 / total, true)
        binding.backButton.visibility = if (state.stepIndex > 0) View.VISIBLE else View.INVISIBLE
        binding.deviceIdWatermark.text = "ID: ${state.deviceId.trim().ifBlank { "-" }}"

        if (state.currentStep != renderedStep) showStep(state)

        // Nome da empresa encontrada, só enquanto a pergunta é sobre ela.
        val company = state.company
        binding.companyNameText.visibility =
            if (state.currentStep == RegistrationStep.COMPANY && company != null) View.VISIBLE else View.GONE
        binding.companyNameText.text = company?.name?.let { "✓ $it" }.orEmpty()

        binding.errorText.text = state.stepError.orEmpty()
        binding.errorText.visibility = if (state.stepError.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.loading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.statusText.text = state.status
        binding.statusText.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        if (state.currentStep == RegistrationStep.TAGS) renderTags(state)
        updateContinueEnabled(state)

        if (!state.isLoading && state.status == "OK" && !handledSuccess) {
            handledSuccess = true
            lifecycleScope.launch {
                // O cadastro concluiu, mas as tags não: avisa antes de seguir (não bloqueia o player).
                state.tagsWarning?.let {
                    Toast.makeText(this@DeviceRegistrationActivity, it, Toast.LENGTH_LONG).show()
                    delay(3000)
                }
                openPlayerAndFinish(state.deviceId.trim())
            }
        }
    }

    /** Troca a pergunta, o tipo de teclado e o foco. Só roda quando o passo muda. */
    private fun showStep(state: DeviceRegistrationUiState) {
        val step = state.currentStep
        renderedStep = step

        binding.question.text = when (step) {
            RegistrationStep.DEVICE_ID -> "Qual é o ID do dispositivo?"
            RegistrationStep.COMPANY -> "Qual é o código da sua empresa?"
            RegistrationStep.NICKNAME -> "Como quer chamar este dispositivo?"
            RegistrationStep.FILIAL -> "Qual é o número da loja/filial?"
            RegistrationStep.TAGS -> "Quais tags este dispositivo deve ter?"
        }

        val isText = step != RegistrationStep.TAGS
        binding.answerEdit.visibility = if (isText) View.VISIBLE else View.GONE
        binding.tagsScroll.visibility = View.GONE
        binding.tagsStatusText.visibility = if (isText) View.GONE else View.VISIBLE
        binding.continueButton.text = if (step == RegistrationStep.TAGS) "Cadastrar dispositivo" else "Continuar →"

        if (isText) {
            programmatic = true
            binding.answerEdit.apply {
                hint = when (step) {
                    RegistrationStep.DEVICE_ID -> "ID do dispositivo"
                    RegistrationStep.COMPANY -> "Código da empresa"
                    RegistrationStep.NICKNAME -> "Apelido (ex.: TV Entrada)"
                    else -> "Número da loja"
                }
                inputType = when (step) {
                    RegistrationStep.COMPANY ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    RegistrationStep.NICKNAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    RegistrationStep.FILIAL -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                setText(
                    when (step) {
                        RegistrationStep.DEVICE_ID -> state.deviceId
                        RegistrationStep.COMPANY -> state.companyCode
                        RegistrationStep.NICKNAME -> state.nickname
                        else -> state.filial
                    },
                )
                setSelection(text?.length ?: 0)
            }
            programmatic = false
        }

        binding.stepContent.alpha = 0f
        binding.stepContent.animate().alpha(1f).setDuration(160).start()

        if (isText) {
            binding.answerEdit.requestFocus()
        } else {
            // Passo das tags: sem campo de texto, o teclado só atrapalharia a lista.
            hideKeyboard()
            binding.continueButton.requestFocus()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun renderTags(state: DeviceRegistrationUiState) {
        binding.tagsStatusText.visibility = View.VISIBLE
        binding.tagsStatusText.text = when {
            state.tagsLoading -> "Carregando tags..."
            state.tagsError != null -> state.tagsError
            state.tags.isEmpty() -> "A empresa ainda não tem tags — dá pra seguir assim mesmo."
            else -> "Opcional: marque as tags que valem para este dispositivo."
        }
        binding.tagsScroll.visibility = if (state.tags.isEmpty()) View.GONE else View.VISIBLE

        if (renderedTags != state.tags) {
            renderedTags = state.tags
            rebuildChips(state)
        } else {
            syncChipSelection(state)
        }
    }

    private fun rebuildChips(state: DeviceRegistrationUiState) {
        programmatic = true
        binding.tagsGroup.removeAllViews()
        for (tag in state.tags) binding.tagsGroup.addView(buildChip(tag, tag.id in state.selectedTagIds))
        programmatic = false
        // Primeira vez que a lista aparece: o foco vai pra primeira tag (D-pad), senão fica no botão.
        if (binding.tagsGroup.childCount > 0 && binding.continueButton.hasFocus()) {
            binding.tagsGroup.getChildAt(0).requestFocus()
        }
    }

    private fun syncChipSelection(state: DeviceRegistrationUiState) {
        programmatic = true
        for (i in 0 until binding.tagsGroup.childCount) {
            val chip = binding.tagsGroup.getChildAt(i) as? Chip ?: continue
            val wanted = (chip.tag as? String) in state.selectedTagIds
            if (chip.isChecked != wanted) chip.isChecked = wanted
        }
        programmatic = false
    }

    private fun buildChip(tag: WizardTag, checked: Boolean): Chip {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val focusStates = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf())
        return Chip(this).apply {
            this.tag = tag.id
            text = tag.name
            textSize = 16f
            isCheckable = true
            isChecked = checked
            isFocusable = true
            chipMinHeight = dp(46).toFloat()
            chipBackgroundColor = ColorStateList(states, intArrayOf(Color.parseColor("#6C5CE7"), Color.parseColor("#EEF0F6")))
            setTextColor(ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#0B1220"))))
            chipStrokeWidth = dp(2).toFloat()
            chipStrokeColor = ColorStateList(focusStates, intArrayOf(Color.parseColor("#0B1220"), Color.TRANSPARENT))
            isCheckedIconVisible = true
            checkedIconTint = ColorStateList.valueOf(Color.WHITE)
            chipIcon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(12), dp(12))
                setColor(parseColorOr(tag.color, "#6C5CE7"))
            }
            isChipIconVisible = true
            setOnCheckedChangeListener { _, _ -> if (!programmatic) viewModel.toggleTag(tag.id) }
        }
    }

    private fun updateContinueEnabled(state: DeviceRegistrationUiState) {
        binding.continueButton.isEnabled = !state.isLoading && !lastOverlayState
    }

    private fun parseColorOr(value: String?, fallback: String): Int =
        runCatching { Color.parseColor(value ?: fallback) }.getOrElse { Color.parseColor(fallback) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openPlayerAndFinish(serial: String) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_DEVICE_ID, serial.trim())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setOfflineUiVisible(visible: Boolean) {
        if (visible == lastOverlayState) return
        lastOverlayState = visible
        val overlay = binding.offlineOverlay
        if (visible) {
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1f).setDuration(220).start()
            binding.offlineWifiButton.requestFocus()
        } else {
            overlay.animate().alpha(0f).setDuration(220).withEndAction {
                overlay.visibility = View.GONE
            }.start()
        }
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (android.os.Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255,
                )
            }
        }
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString().orEmpty())
    }
}
