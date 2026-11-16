package com.mupa.player.enterprise

import com.mupa.player.enterprise.ui.RegistrationStep
import com.mupa.player.enterprise.ui.RegistrationWizard
import com.mupa.player.enterprise.ui.WizardTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationWizardTest {

    private fun tag(id: String, name: String, slug: String? = null) =
        WizardTag(id = id, name = name, slug = slug, category = null, color = null)

    @Test
    fun steps_withoutManualDeviceId() {
        assertEquals(
            listOf(RegistrationStep.COMPANY, RegistrationStep.NICKNAME, RegistrationStep.FILIAL, RegistrationStep.TAGS),
            RegistrationWizard.steps(needsDeviceId = false),
        )
    }

    @Test
    fun steps_withManualDeviceId_startWithDeviceId() {
        val steps = RegistrationWizard.steps(needsDeviceId = true)
        assertEquals(RegistrationStep.DEVICE_ID, steps.first())
        assertEquals(5, steps.size)
    }

    @Test
    fun validate_messagesPerStep() {
        assertEquals("Informe o código da empresa.", RegistrationWizard.validate(RegistrationStep.COMPANY, "  ", false))
        assertEquals("Empresa não encontrada.", RegistrationWizard.validate(RegistrationStep.COMPANY, "101MUP", false))
        assertNull(RegistrationWizard.validate(RegistrationStep.COMPANY, "101MUP", true))
        assertEquals("Informe um nome para o dispositivo.", RegistrationWizard.validate(RegistrationStep.NICKNAME, "", true))
        assertEquals("Informe o número da loja.", RegistrationWizard.validate(RegistrationStep.FILIAL, " ", true))
        assertEquals("Informe o ID do dispositivo.", RegistrationWizard.validate(RegistrationStep.DEVICE_ID, "", true))
        assertNull(RegistrationWizard.validate(RegistrationStep.NICKNAME, "Bancada", false))
    }

    @Test
    fun validate_tagsStepIsAlwaysOptional() {
        assertNull(RegistrationWizard.validate(RegistrationStep.TAGS, "", false))
    }

    @Test
    fun progressLabel_isOneBased() {
        assertEquals("2 de 4", RegistrationWizard.progressLabel(2, 4))
        assertEquals("1 de 5", RegistrationWizard.progressLabel(1, 5))
    }

    @Test
    fun storeSlug_mirrorsTheServerRule() {
        assertEquals("loja-1", RegistrationWizard.storeSlug("1"))
        assertEquals("loja-032", RegistrationWizard.storeSlug(" 032 "))
        assertEquals("loja-centro-norte", RegistrationWizard.storeSlug("Centro Norte"))
        assertEquals("loja-sao-jose", RegistrationWizard.storeSlug("São José"))
    }

    @Test
    fun preselect_matchesBySlug() {
        val tags = listOf(tag("a", "Premium", "premium"), tag("b", "Loja 1", "loja-1"))
        assertEquals("b", RegistrationWizard.preselectStoreTagId(tags, "1"))
    }

    @Test
    fun preselect_matchesByNameWhenSlugMissing() {
        val tags = listOf(tag("a", "Premium"), tag("b", "Loja 1"))
        assertEquals("b", RegistrationWizard.preselectStoreTagId(tags, "1"))
        assertEquals("b", RegistrationWizard.preselectStoreTagId(tags, " 1 "))
    }

    @Test
    fun preselect_doesNotMatchAPrefix() {
        // Filial "1" nunca pode escolher "Loja 10" nem "loja-1x".
        val tags = listOf(tag("a", "Loja 10", "loja-10"), tag("b", "Loja 11"))
        assertNull(RegistrationWizard.preselectStoreTagId(tags, "1"))
    }

    @Test
    fun preselect_keepsLeadingZeros() {
        val tags = listOf(tag("a", "Loja 32", "loja-32"), tag("b", "Loja 032", "loja-032"))
        assertEquals("b", RegistrationWizard.preselectStoreTagId(tags, "032"))
    }

    @Test
    fun preselect_nullWhenNoFilialOrNoTags() {
        assertNull(RegistrationWizard.preselectStoreTagId(emptyList(), "1"))
        assertNull(RegistrationWizard.preselectStoreTagId(listOf(tag("a", "Loja 1", "loja-1")), "  "))
    }
}
