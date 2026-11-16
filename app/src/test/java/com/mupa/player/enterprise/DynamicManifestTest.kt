package com.mupa.player.enterprise

import com.mupa.player.enterprise.managers.DynamicManifest
import com.mupa.player.enterprise.managers.ViewportFit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicManifestTest {

    private val manifestWithOverlays = """
        {
          "name": "Playlist",
          "items": [],
          "overlays": [
            {"id":"b","name":"Segundo","type":"web","url":"https://app.mupa.app/tv/dinamico?serial=X&layout=2&overlay=1",
             "start_ms":40000,"duration_ms":10000,"media_viewport":null},
            {"id":"a","name":"L-Bar","type":"web","url":"https://app.mupa.app/tv/dinamico?serial=X&layout=1&overlay=1",
             "start_ms":0,"duration_ms":30000,
             "media_viewport":{"x":0,"y":0,"w":72,"h":80,"object_fit":"contain"}}
          ],
          "is_operante": true, "is_inoperative": false
        }
    """.trimIndent()

    @Test
    fun overlays_areParsedAndSortedByStart() {
        val list = DynamicManifest.parseOverlays(manifestWithOverlays)
        assertEquals(listOf("a", "b"), list.map { it.id })
        val a = list[0]
        assertEquals(0L, a.startMs)
        assertEquals(30_000L, a.durationMs)
        assertEquals(ViewportFit.CONTAIN, a.viewport?.fit)
        assertEquals(0.72f, a.viewport!!.w, 0.0001f)
        assertNull(list[1].viewport)
    }

    @Test
    fun overlays_fromPlaylistKeyAreAccepted() {
        val json = """{"playlist":{"overlays":[{"id":"p","url":"https://x/y","start_ms":0,"duration_ms":5000}]}}"""
        assertEquals(1, DynamicManifest.parseOverlays(json).size)
    }

    @Test
    fun overlays_invalidOnesAreDropped() {
        val json = """
            {"overlays":[
              {"id":"no-url","url":"","start_ms":0,"duration_ms":5000},
              {"id":"not-http","url":"file:///etc/passwd","start_ms":0,"duration_ms":5000},
              {"id":"too-short","url":"https://x/y","start_ms":0,"duration_ms":500},
              {"id":"ok","url":"https://x/y","start_ms":-5,"duration_ms":1000}
            ]}
        """.trimIndent()
        val list = DynamicManifest.parseOverlays(json)
        assertEquals(listOf("ok"), list.map { it.id })
        assertEquals("start_ms negativo vira 0", 0L, list[0].startMs)
    }

    @Test
    fun overlays_missingOrBrokenJson_isEmpty() {
        assertTrue(DynamicManifest.parseOverlays("{}").isEmpty())
        assertTrue(DynamicManifest.parseOverlays("not json").isEmpty())
    }

    @Test
    fun viewport_degenerateOrOutOfRange_isRejected() {
        fun vp(s: String) = DynamicManifest.parseViewport(JSONObject(s))
        assertNull(vp("""{"x":0,"y":0,"w":0,"h":0.8}"""))
        assertNull(vp("""{"x":0,"y":0,"w":0.8}"""))
        assertNull(DynamicManifest.parseViewport(null))
        // x=90% deixa só 10% de largura útil mesmo pedindo 72%: é cortado, não estourado.
        val clamped = vp("""{"x":90,"y":0,"w":72,"h":50,"fit":"cover"}""")!!
        assertEquals(0.1f, clamped.w, 0.0001f)
        assertEquals(ViewportFit.COVER, clamped.fit)
    }

    @Test
    fun viewport_unitIsAlwaysPercent_soTinyValuesAreRejectedNotFullScreen() {
        // Contrato v2.1: a unidade é % e ponto. O formato antigo "0..1" virou 0,72% -> rejeitado,
        // em vez de adivinhado. `w: 1` é 1%, nunca "tela cheia".
        assertNull(DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":0.72,"h":0.8}""")))
        assertNull(DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":1,"h":1}""")))
    }

    @Test
    fun viewport_contractV21Payload_withFractionalPercentAndRadius() {
        // Exemplo do contrato v2.1 (§5.1): x=0.1 é 0,1% (não 10%).
        val vp = DynamicManifest.parseViewport(
            JSONObject("""{"x":0.1,"y":0,"w":80.78,"h":81.94,"object_fit":"contain","border_radius":14}"""),
        )!!
        assertEquals(0.001f, vp.x, 0.00001f)
        assertEquals(0.8078f, vp.w, 0.0001f)
        assertEquals(0.8194f, vp.h, 0.0001f)
        assertEquals(14, vp.borderRadius)
    }

    @Test
    fun viewport_borderRadius_isClampedAndOptional() {
        fun r(extra: String) = DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":50,"h":50$extra}"""))!!.borderRadius
        assertEquals(0, r(""))
        assertEquals(200, r(""","border_radius":999"""))
        assertEquals(0, r(""","border_radius":-5"""))
    }

    @Test
    fun viewport_realServerFormat_isPercentWithObjectFit() {
        // Formato que o servidor REALMENTE emite (extractMediaViewportFromElements).
        val vp = DynamicManifest.parseViewport(
            JSONObject("""{"x":0,"y":0,"w":72,"h":80,"border_radius":0,"object_fit":"cover"}"""),
        )!!
        assertEquals(0.72f, vp.w, 0.0001f)
        assertEquals(0.80f, vp.h, 0.0001f)
        assertEquals(ViewportFit.COVER, vp.fit)
        assertFalse("72x80% não pode virar tela cheia", vp.isFullScreen)
        val r = DynamicManifest.viewportToPixels(vp, 1920, 1080)
        assertEquals(1382, r.width)
        assertEquals(864, r.height)
    }

    @Test
    fun viewport_percentWithOffset_andFullPercent() {
        val vp = DynamicManifest.parseViewport(JSONObject("""{"x":10,"y":20,"w":50,"h":40,"object_fit":"contain"}"""))!!
        assertEquals(0.10f, vp.x, 0.0001f)
        assertEquals(0.20f, vp.y, 0.0001f)
        assertEquals(0.50f, vp.w, 0.0001f)
        // 100x100% (o default do servidor quando o slot não define w/h) = sem squeeze.
        assertTrue(DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":100,"h":100}"""))!!.isFullScreen)
    }

    @Test
    fun overlay_withRealServerPayload_isParsed() {
        val json = """
            {"overlays":[{"id":"u","slide_id":"s","layout_id":"l","name":"Overlay L-Bar","type":"web",
             "url":"https://midias.mupa.app/tv/dinamico?serial=ABC&slide=s&overlay=1","start_ms":0,"duration_ms":15000,
             "media_viewport":{"x":0,"y":0,"w":72,"h":80,"border_radius":0,"object_fit":"contain"},
             "required_modules":["WEATHER","NEWS"]}]}
        """.trimIndent()
        val o = DynamicManifest.parseOverlays(json).single()
        assertEquals(0.72f, o.viewport!!.w, 0.0001f)
        assertEquals(15_000L, o.durationMs)
    }

    @Test
    fun viewport_toPixels_followsTheContractFormula() {
        val vp = DynamicManifest.parseViewport(JSONObject("""{"x":10,"y":20,"w":72,"h":80}"""))
        val r = DynamicManifest.viewportToPixels(vp, 1920, 1080)
        assertEquals(192, r.left)
        assertEquals(216, r.top)
        assertEquals(1382, r.width) // round(0.72 * 1920) = 1382,4 -> 1382
        assertEquals(864, r.height)
        val full = DynamicManifest.viewportToPixels(null, 1920, 1080)
        assertEquals(DynamicManifest.PixelRect(0, 0, 1920, 1080), full)
    }

    @Test
    fun viewport_fullScreenDetection() {
        assertTrue(DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":100,"h":100}"""))!!.isFullScreen)
        assertFalse(DynamicManifest.parseViewport(JSONObject("""{"x":0,"y":0,"w":72,"h":80}"""))!!.isFullScreen)
    }

    @Test
    fun operationalState_variants() {
        assertTrue(DynamicManifest.parseOperationalState("""{"is_operante":true,"is_inoperative":false}""").isOperante)

        val off = DynamicManifest.parseOperationalState("""{"is_operante":false,"is_inoperative":true,"inoperative_reason":"Sem licença"}""")
        assertFalse(off.isOperante)
        assertEquals("Sem licença", off.reason)

        // Qualquer um dos dois flags derruba.
        assertFalse(DynamicManifest.parseOperationalState("""{"is_inoperative":true}""").isOperante)
        assertFalse(DynamicManifest.parseOperationalState("""{"is_operante":false}""").isOperante)

        // Manifesto antigo sem os campos, ou JSON quebrado, NUNCA derruba o painel.
        assertTrue(DynamicManifest.parseOperationalState("""{"items":[]}""").isOperante)
        assertTrue(DynamicManifest.parseOperationalState("garbage").isOperante)
        assertNull(DynamicManifest.parseOperationalState("""{"is_operante":true,"inoperative_reason":null}""").reason)
    }

    @Test
    fun products_skipNamelessAndKeepPromo() {
        val json = org.json.JSONArray(
            """[
              {"code":"1","name":"Picanha","price":69.9,"unit":"KG","is_promo":true,"promo_label":"OFERTA"},
              {"code":"2","name":"  ","price":1},
              {"name":"Sem preço"}
            ]""",
        )
        val list = DynamicManifest.parseProducts(json)
        assertEquals(2, list.size)
        assertEquals(69.9, list[0].price!!, 0.0001)
        assertTrue(list[0].isPromo)
        assertEquals("OFERTA", list[0].promoLabel)
        assertNull(list[1].price)
    }

    @Test
    fun fingerprint_ignoresDeviceFieldsThatChangeEveryCall() {
        val a = """{"success":true,"device":{"last_player_activity_at":"2026-11-12T16:24:48"},"manifest":{"name":"P","items":[{"id":"1","type":"image","url":"u"}]}}"""
        val b = """{"success":true,"device":{"last_player_activity_at":"2026-11-12T16:24:51"},"manifest":{"name":"P","items":[{"id":"1","type":"image","url":"u"}]}}"""
        assertEquals("só o device mudou: não é programação nova", DynamicManifest.fingerprint(a), DynamicManifest.fingerprint(b))
    }

    @Test
    fun fingerprint_detectsRealChanges() {
        val base = """{"device":{"x":1},"manifest":{"items":[{"id":"1","url":"u"}],"overlays":[]}}"""
        val newItem = """{"device":{"x":1},"manifest":{"items":[{"id":"1","url":"u"},{"id":"2","url":"v"}],"overlays":[]}}"""
        val newOverlay = """{"device":{"x":1},"manifest":{"items":[{"id":"1","url":"u"}],"overlays":[{"id":"o","url":"https://x","duration_ms":5000}]}}"""
        val inoperative = """{"device":{"x":1},"manifest":{"items":[{"id":"1","url":"u"}],"overlays":[],"is_operante":false}}"""
        val fp = DynamicManifest.fingerprint(base)
        assertFalse(fp == DynamicManifest.fingerprint(newItem))
        assertFalse(fp == DynamicManifest.fingerprint(newOverlay))
        assertFalse(fp == DynamicManifest.fingerprint(inoperative))
    }

    @Test
    fun fingerprint_withoutManifestKeyOrBrokenJson_fallsBackToText() {
        assertEquals("""{"items":[]}""", DynamicManifest.fingerprint("""  {"items":[]}  """))
        assertEquals("garbage", DynamicManifest.fingerprint(" garbage "))
    }

    @Test
    fun trackOneBlocking_onlyWebAndPriceTable() {
        assertTrue(DynamicManifest.blocksOverlay("web"))
        assertTrue(DynamicManifest.blocksOverlay("price_table"))
        assertTrue(DynamicManifest.blocksOverlay("WEB"))
        assertFalse(DynamicManifest.blocksOverlay("video"))
        assertFalse(DynamicManifest.blocksOverlay("image"))
        assertFalse(DynamicManifest.blocksOverlay(null))
    }
}
