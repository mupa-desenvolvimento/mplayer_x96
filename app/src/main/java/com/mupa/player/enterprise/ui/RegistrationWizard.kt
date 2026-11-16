package com.mupa.player.enterprise.ui

import java.text.Normalizer

enum class RegistrationStep { DEVICE_ID, COMPANY, NICKNAME, FILIAL, TAGS }

/** Tag da empresa exibida na última pergunta do cadastro. */
data class WizardTag(
    val id: String,
    val name: String,
    val slug: String?,
    val category: String?,
    val color: String?,
)

/**
 * Regras puras do cadastro em passo a passo — sem Android, testáveis em JVM.
 * A Activity só desenha o passo atual; quem decide ordem, validação e pré-seleção é daqui.
 */
object RegistrationWizard {

    /** `DEVICE_ID` só entra quando a identificação automática do box falhou. */
    fun steps(needsDeviceId: Boolean): List<RegistrationStep> {
        val base = listOf(RegistrationStep.COMPANY, RegistrationStep.NICKNAME, RegistrationStep.FILIAL, RegistrationStep.TAGS)
        return if (needsDeviceId) listOf(RegistrationStep.DEVICE_ID) + base else base
    }

    /** Mensagem de erro do passo, ou null se pode avançar. As tags são sempre opcionais. */
    fun validate(step: RegistrationStep, value: String, companyFound: Boolean): String? {
        val v = value.trim()
        return when (step) {
            RegistrationStep.DEVICE_ID -> if (v.isEmpty()) "Informe o ID do dispositivo." else null
            RegistrationStep.COMPANY -> when {
                v.isEmpty() -> "Informe o código da empresa."
                !companyFound -> "Empresa não encontrada."
                else -> null
            }
            RegistrationStep.NICKNAME -> if (v.isEmpty()) "Informe um nome para o dispositivo." else null
            RegistrationStep.FILIAL -> if (v.isEmpty()) "Informe o número da loja." else null
            RegistrationStep.TAGS -> null
        }
    }

    /** `position` é 1-based. */
    fun progressLabel(position: Int, total: Int): String = "$position de $total"

    /**
     * Slug da tag de loja, igual ao que o servidor gera (`device-api` e `tagSyncEdge`):
     * "loja-" + filial sem acentos, minúscula, espaços viram "-", só [a-z0-9_-].
     */
    fun storeSlug(filial: String): String {
        val clean = Normalizer.normalize(filial.trim(), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^\\w-]+"), "")
            .replace(Regex("-+"), "-")
        return "loja-$clean"
    }

    /**
     * Tag de loja que corresponde à filial informada, por slug ou por nome exato
     * ("Loja N", ou a própria filial se já começar com "loja "). Comparação exata: filial "1" não
     * pode escolher "Loja 10".
     */
    fun preselectStoreTagId(tags: List<WizardTag>, filial: String): String? {
        val clean = filial.trim()
        if (clean.isEmpty() || tags.isEmpty()) return null
        val slug = storeSlug(clean)
        val expectedName = if (clean.lowercase().startsWith("loja ")) clean else "Loja $clean"
        return tags.firstOrNull { it.slug == slug }?.id
            ?: tags.firstOrNull { it.name.trim().equals(expectedName, ignoreCase = true) }?.id
    }
}
