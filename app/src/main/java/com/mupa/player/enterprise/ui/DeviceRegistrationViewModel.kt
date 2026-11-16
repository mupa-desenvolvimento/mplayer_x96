package com.mupa.player.enterprise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mupa.player.enterprise.managers.DeviceCache
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.services.CompanyInfo
import com.mupa.player.enterprise.services.CompanyLookupResult
import com.mupa.player.enterprise.services.CompanyLookupService
import com.mupa.player.enterprise.services.DeviceRegistrationPayload
import com.mupa.player.enterprise.services.DeviceRegistrationResult
import com.mupa.player.enterprise.services.DeviceRegistrationService
import com.mupa.player.enterprise.services.DeviceTagsService
import com.mupa.player.enterprise.services.DeviceValidationResult
import com.mupa.player.enterprise.services.DeviceValidationService
import com.mupa.player.enterprise.services.TagsResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceRegistrationUiState(
    val deviceId: String,
    val companyCode: String,
    val company: CompanyInfo?,
    val nickname: String,
    val filial: String,
    val isLoading: Boolean,
    val status: String,
    /** Identificação automática falhou: o primeiro passo passa a ser "Qual é o ID do dispositivo?". */
    val needsDeviceId: Boolean = false,
    val stepIndex: Int = 0,
    val stepError: String? = null,
    val tags: List<WizardTag> = emptyList(),
    val tagsLoading: Boolean = false,
    val tagsError: String? = null,
    val selectedTagIds: Set<String> = emptySet(),
    /** O usuário já mexeu nas tags: a pré-seleção da loja não pode sobrescrever a escolha dele. */
    val tagsTouched: Boolean = false,
    /** Cadastro concluiu, mas as tags não foram vinculadas — a Activity avisa antes de abrir o player. */
    val tagsWarning: String? = null,
) {
    val steps: List<RegistrationStep> get() = RegistrationWizard.steps(needsDeviceId)
    val currentStep: RegistrationStep get() = steps.getOrElse(stepIndex) { steps.last() }
}

class DeviceRegistrationViewModel(app: Application) : AndroidViewModel(app) {
    private val companyLookupService = CompanyLookupService(app.applicationContext)
    private val registrationService = DeviceRegistrationService(app.applicationContext)
    private val validationService = DeviceValidationService(app.applicationContext)
    private val tagsService = DeviceTagsService(app.applicationContext)
    private val cacheManager = DeviceCacheManager(app.applicationContext)

    private val _uiState = MutableStateFlow(
        DeviceRegistrationUiState(
            deviceId = "",
            companyCode = "",
            company = null,
            nickname = "",
            filial = "",
            isLoading = false,
            status = "",
        ),
    )
    val uiState: StateFlow<DeviceRegistrationUiState> = _uiState.asStateFlow()

    private var lookupJob: Job? = null
    private var tagsJob: Job? = null

    /** Segura Enter repetido: só um "Continuar" por vez (o cadastro não pode disparar duas vezes). */
    private var busy = false

    fun setDeviceId(id: String) {
        _uiState.update { it.copy(deviceId = id, stepError = null) }
    }

    fun setNeedsDeviceId(value: Boolean) {
        _uiState.update { it.copy(needsDeviceId = value) }
    }

    fun setCompanyCode(value: String) {
        _uiState.update { it.copy(companyCode = value, company = null, stepError = null) }
        lookupJob?.cancel()
        val code = value.trim()
        if (code.length < 3) return
        lookupJob = viewModelScope.launch {
            delay(350)
            lookupCompany(code)
        }
    }

    fun setNickname(value: String) {
        _uiState.update { it.copy(nickname = value, stepError = null) }
    }

    fun setFilial(value: String) {
        _uiState.update { it.copy(filial = value, stepError = null) }
    }

    fun toggleTag(id: String) {
        _uiState.update {
            val next = if (id in it.selectedTagIds) it.selectedTagIds - id else it.selectedTagIds + id
            it.copy(selectedTagIds = next, tagsTouched = true)
        }
    }

    /** Volta um passo. False = já estava no primeiro (o chamador decide o que fazer, ex.: sair). */
    fun onBack(): Boolean {
        val s = _uiState.value
        // Durante um cadastro em andamento o Voltar é engolido — sair no meio deixaria tudo pela metade.
        if (s.isLoading) return true
        if (s.stepIndex <= 0) return false
        _uiState.value = s.copy(stepIndex = s.stepIndex - 1, stepError = null)
        return true
    }

    fun onContinue() {
        if (busy || _uiState.value.isLoading) return
        busy = true
        viewModelScope.launch {
            try {
                val s = _uiState.value
                when (s.currentStep) {
                    RegistrationStep.DEVICE_ID -> advance(s, s.deviceId, companyFound = true)
                    RegistrationStep.COMPANY -> {
                        var company = s.company
                        if (company == null && s.companyCode.isNotBlank()) {
                            lookupJob?.cancel()
                            company = lookupCompany(s.companyCode.trim(), reportErrors = true)
                            // Erro de rede/configuração: a mensagem já foi publicada; não vira "Empresa não encontrada".
                            if (company == null && _uiState.value.stepError != null) return@launch
                        }
                        advance(_uiState.value, s.companyCode, companyFound = company != null)
                    }
                    RegistrationStep.NICKNAME -> advance(s, s.nickname, companyFound = true)
                    RegistrationStep.FILIAL -> advance(s, s.filial, companyFound = true)
                    RegistrationStep.TAGS -> register()
                }
            } finally {
                busy = false
            }
        }
    }

    private fun advance(state: DeviceRegistrationUiState, value: String, companyFound: Boolean) {
        val error = RegistrationWizard.validate(state.currentStep, value, companyFound)
        if (error != null) {
            _uiState.update { it.copy(stepError = error) }
            return
        }
        val nextIndex = (state.stepIndex + 1).coerceAtMost(state.steps.lastIndex)
        _uiState.update { it.copy(stepIndex = nextIndex, stepError = null) }
        if (state.steps[nextIndex] == RegistrationStep.TAGS) loadTags()
    }

    /**
     * Carrega as tags da empresa. Reentrar no passo (Voltar → mudar a filial → avançar) recalcula a
     * pré-seleção da loja, mas só se o usuário ainda não escolheu nada por conta própria.
     */
    private fun loadTags() {
        val company = _uiState.value.company ?: return
        tagsJob?.cancel()
        _uiState.update { it.copy(tagsLoading = true, tagsError = null) }
        tagsJob = viewModelScope.launch {
            when (val result = tagsService.listByTenant(company.tenantId)) {
                is TagsResult.Success -> _uiState.update { s ->
                    val existing = result.tags.map { it.id }.toSet()
                    val selected = if (s.tagsTouched) {
                        s.selectedTagIds.filter { it in existing }.toSet()
                    } else {
                        RegistrationWizard.preselectStoreTagId(result.tags, s.filial)?.let { setOf(it) } ?: emptySet()
                    }
                    s.copy(tags = result.tags, tagsLoading = false, tagsError = null, selectedTagIds = selected)
                }
                is TagsResult.Error -> _uiState.update {
                    it.copy(
                        tags = emptyList(),
                        tagsLoading = false,
                        tagsError = "Não foi possível carregar as tags. Você pode seguir sem elas.",
                    )
                }
            }
        }
    }

    private suspend fun register() {
        val state = _uiState.value
        val deviceId = state.deviceId.trim()
        val companyCode = state.companyCode.trim()
        val nickname = state.nickname.trim()
        val filial = state.filial.trim()

        if (deviceId.isBlank()) {
            fail("Identificando dispositivo...")
            return
        }
        val company = state.company
        if (company == null || companyCode.isBlank() || nickname.isBlank() || filial.isBlank()) {
            // Só chega aqui se um passo anterior foi pulado — não deveria acontecer pela interface.
            fail("Complete as perguntas anteriores.")
            return
        }

        setLoading("Cadastrando dispositivo...")
        when (val reg = registrationService.register(
            DeviceRegistrationPayload(
                apelidoInterno = nickname,
                serial = deviceId,
                empresaId = company.id,
                empresaCode = companyCode,
                numFilial = filial,
            ),
        )) {
            DeviceRegistrationResult.Success -> Unit
            DeviceRegistrationResult.NotConfigured -> return fail("Token não configurado.")
            is DeviceRegistrationResult.Error -> return fail("Erro no cadastro: ${reg.message}")
        }

        setLoading("Validando cadastro...")
        when (val validation = validationService.validateDevice(deviceId)) {
            is DeviceValidationResult.Found -> {
                com.mupa.player.enterprise.managers.DeviceIdentityManager(getApplication()).save(deviceId)
                cacheManager.save(
                    validation.cache.copy(
                        company = company.id,
                        companyCode = companyCode,
                        companyName = company.name,
                        tenant = company.tenantId,
                        deviceRegistered = true,
                    ),
                )
                val warning = linkTags(deviceId, _uiState.value.selectedTagIds.toList())
                _uiState.update { it.copy(tagsWarning = warning) }
                setLoadingDone("OK")
            }
            DeviceValidationResult.NotFound -> fail("Ainda não localizado no banco.")
            DeviceValidationResult.NotConfigured -> fail("Token não configurado.")
            is DeviceValidationResult.Error -> fail("Erro na validação: ${validation.message}")
        }
    }

    /** Best-effort: o cadastro já está feito. Devolve um aviso para a tela, ou null se tudo certo. */
    private suspend fun linkTags(serial: String, tagIds: List<String>): String? {
        if (tagIds.isEmpty()) return null
        setLoading("Vinculando tags...")
        val uuid = tagsService.resolveDeviceUuid(serial)
            ?: return "Tags não vinculadas (não foi possível identificar o dispositivo). Vincule no painel."
        return if (tagsService.link(uuid, tagIds)) null else "Algumas tags não foram vinculadas. Vincule no painel."
    }

    suspend fun getCachedRegisteredDevice(): DeviceCache? {
        val cached = cacheManager.load() ?: return null
        return cached.takeIf { it.deviceRegistered }
    }

    /**
     * Busca a empresa pelo código. Sucesso grava `company`; devolve null se não achou ou deu erro.
     * Com [reportErrors] (clique em Continuar), falhas de rede/configuração viram `stepError`; sem ele
     * (busca automática ao digitar) só o status muda, pra não gritar erro enquanto a pessoa ainda digita.
     */
    private suspend fun lookupCompany(code: String, reportErrors: Boolean = false): CompanyInfo? {
        setLoading("Validando empresa...")
        return when (val result = companyLookupService.lookupByCode(code)) {
            is CompanyLookupResult.Found -> {
                _uiState.update { it.copy(company = result.company, isLoading = false, status = "Empresa encontrada") }
                result.company
            }
            CompanyLookupResult.NotFound -> {
                _uiState.update { it.copy(company = null, isLoading = false, status = "Empresa não encontrada") }
                null
            }
            CompanyLookupResult.NotConfigured -> {
                _uiState.update {
                    it.copy(company = null, isLoading = false, status = "Token não configurado",
                        stepError = if (reportErrors) "Token não configurado." else it.stepError)
                }
                null
            }
            is CompanyLookupResult.Error -> {
                _uiState.update {
                    it.copy(company = null, isLoading = false, status = "Erro: ${result.message}",
                        stepError = if (reportErrors) "Sem conexão com o servidor. Tente de novo." else it.stepError)
                }
                null
            }
        }
    }

    private fun setLoading(message: String) {
        _uiState.update { it.copy(isLoading = true, status = message) }
    }

    private fun setLoadingDone(message: String) {
        _uiState.update { it.copy(isLoading = false, status = message) }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(isLoading = false, status = message, stepError = message) }
    }
}
