package com.mupa.player.enterprise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mupa.player.enterprise.managers.ManifestManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parsing do manifesto real: garante que `web`/`price_table` não viram "arquivo para baixar" por engano. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManifestParsingTest {

    private val manager by lazy { ManifestManager(ApplicationProvider.getApplicationContext<Context>()) }

    private val json = """
        {"name":"Mix","items":[
          {"id":"v1","type":"video","url":"https://cdn/x/v1.mp4","duration_ms":30000,"volume":1},
          {"id":"w1","type":"web","name":"Clima","url":"https://app.mupa.app/tv/dinamico?serial=ABC&slide=s1","duration_ms":12000,"volume":0},
          {"id":"p1","type":"price_table","name":"Açougue","sector":"Açougue","url":"https://cdn/x/notes.html",
           "background_url":"https://cdn/x/bg.png","duration_ms":15000,
           "products":[{"code":"1","name":"Picanha","price":69.9,"unit":"KG","is_promo":true,"promo_label":"OFERTA"}]},
          {"id":"p2","type":"price_table","sector":"Padaria","url":"","duration_ms":9000,"products":[{"name":"Pão","price":1.5,"unit":"UN"}]}
        ]}
    """.trimIndent()

    @Test
    fun webItem_keepsPageUrl_andIsNotDownloadable() {
        val web = manager.parseItemsPublic(json).first { it.id == "w1" }
        assertEquals("web", web.type)
        assertEquals("https://app.mupa.app/tv/dinamico?serial=ABC&slide=s1", web.url)
        assertFalse("página não pode ser baixada como mídia", web.needsDownload)
    }

    @Test
    fun priceTable_usesBackgroundUrl_notTheGenericUrl() {
        val p = manager.parseItemsPublic(json).first { it.id == "p1" }
        assertEquals("https://cdn/x/bg.png", p.url)
        assertTrue(p.needsDownload)
        assertEquals("Açougue", p.sector)
        assertEquals(1, p.products.size)
        assertEquals("Picanha", p.products[0].name)
    }

    @Test
    fun priceTable_withoutBackground_isKeptButNeedsNoDownload() {
        val p = manager.parseItemsPublic(json).first { it.id == "p2" }
        assertEquals("", p.url)
        assertFalse(p.needsDownload)
        assertEquals(1, p.products.size)
    }

    @Test
    fun webItem_fromDeviceApiV1_usesDurationInSeconds() {
        // device-api v1 / ManifestService.ts: `duration` em segundos (v2 usa duration_ms).
        val v1 = """{"items":[{"id":"w9","type":"web","name":"Clima + Noticias","url":"https://midias.mupa.app/tv/dinamico?serial=ABC&slide=s9","duration":30,"volume":0}]}"""
        val w = manager.parseItemsPublic(v1).single()
        assertEquals(30_000L, w.durationMs)
        assertFalse(w.needsDownload)
    }

    @Test
    fun videoItem_isStillAnOrdinaryDownload() {
        val v = manager.parseItemsPublic(json).first { it.id == "v1" }
        assertTrue(v.needsDownload)
        assertEquals(4, manager.parseItemsPublic(json).size)
    }
}
