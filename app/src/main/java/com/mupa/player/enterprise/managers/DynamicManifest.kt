package com.mupa.player.enterprise.managers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelos e parsing dos blocos "dinâmicos" do manifesto do Mupa Connect
 * (contrato 2026-11-12-contrato-specs-mplayer.md): item `web`, `price_table`, `overlays[]` (L-Bar)
 * e o estado operacional (`is_operante`).
 *
 * Fica separado do [ManifestManager] de propósito: é Kotlin puro (só org.json), então roda em
 * teste unitário sem Android e o contrato fica num lugar só.
 */

data class PriceProduct(
    val code: String?,
    val name: String,
    val price: Double?,
    val unit: String?,
    val isPromo: Boolean,
    val promoLabel: String?,
)

enum class ViewportFit { CONTAIN, COVER }

/** Retângulo normalizado (0..1) relativo à tela inteira onde a Trilha 1 deve ser exibida. */
data class MediaViewport(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fit: ViewportFit,
    /** Raio dos cantos em px de design (0..200), relativo à tela de referência de 1920 px. */
    val borderRadius: Int = 0,
) {
    /** Ocupa a tela inteira — equivalente a "sem squeeze". */
    val isFullScreen: Boolean get() = x <= 0f && y <= 0f && w >= 1f && h >= 1f
}

data class ManifestOverlay(
    val id: String,
    val name: String?,
    val url: String,
    val startMs: Long,
    val durationMs: Long,
    val viewport: MediaViewport?,
)

data class OperationalState(
    val isOperante: Boolean,
    val reason: String?,
)

object DynamicManifest {
    const val TYPE_WEB = "web"
    const val TYPE_PRICE_TABLE = "price_table"

    /** Tipos de item da Trilha 1 que NÃO são arquivo de mídia baixável como vídeo/imagem. */
    fun isWebType(type: String?): Boolean = type.equals(TYPE_WEB, ignoreCase = true)

    fun isPriceTableType(type: String?): Boolean = type.equals(TYPE_PRICE_TABLE, ignoreCase = true)

    /** `web` e `price_table` ocupam a Trilha 1 de um jeito incompatível com squeeze (regra defensiva 5.4). */
    fun blocksOverlay(type: String?): Boolean = isWebType(type) || isPriceTableType(type)

    /**
     * Impressão digital do que realmente define a programação.
     *
     * A resposta do servidor traz também `device.*` (ex.: `last_player_activity_at`, `atualizado`),
     * que muda A CADA consulta — só por o player ter consultado. Comparar a resposta inteira fazia
     * o app enxergar "nova programação" a cada ciclo: card na tela, novo sync e a playlist
     * voltando ao item 0. Só a subárvore `manifest` (itens, overlays, aparência, estado
     * operacional) conta; sem ela, cai pro texto inteiro.
     */
    fun fingerprint(json: String): String {
        val text = json.trim()
        return runCatching {
            val m = JSONObject(text).optJSONObject("manifest")
            m?.toString() ?: text
        }.getOrDefault(text)
    }

    private fun root(json: String): JSONObject {
        val r = JSONObject(json)
        return r.optJSONObject("manifest") ?: r
    }

    /**
     * Estado operacional. Quando o servidor marca o device como inoperante, ele troca a playlist
     * por um único item de imagem (`device-inoperative.svg`) — o app não decodifica SVG, então
     * a tela de "inoperante" é desenhada nativamente a partir deste estado.
     */
    fun parseOperationalState(json: String): OperationalState {
        return runCatching {
            val m = root(json)
            val inoperative = m.optBoolean("is_inoperative", false)
            val operante = if (m.has("is_operante")) m.optBoolean("is_operante", true) else true
            val reason = m.optString("inoperative_reason", "").takeIf { it.isNotBlank() && it != "null" }
            OperationalState(isOperante = operante && !inoperative, reason = reason)
        }.getOrDefault(OperationalState(isOperante = true, reason = null))
    }

    /** Overlays da Trilha 2 (L-Bar / Squeeze), ordenados por `start_ms`. Inválidos são ignorados. */
    fun parseOverlays(json: String): List<ManifestOverlay> {
        return runCatching {
            val m = root(json)
            val playlist = m.optJSONObject("playlist")
            val arr: JSONArray = m.optJSONArray("overlays") ?: playlist?.optJSONArray("overlays") ?: JSONArray()
            val out = ArrayList<ManifestOverlay>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "").trim()
                if (!url.startsWith("http", ignoreCase = true)) continue
                val duration = o.optLong("duration_ms", 0L)
                if (duration < MIN_OVERLAY_DURATION_MS) continue
                out += ManifestOverlay(
                    id = o.optString("id", "").ifBlank { "overlay-$i" },
                    name = o.optString("name", "").takeIf { it.isNotBlank() },
                    url = url,
                    startMs = o.optLong("start_ms", 0L).coerceAtLeast(0L),
                    durationMs = duration,
                    viewport = parseViewport(o.optJSONObject("media_viewport")),
                )
            }
            out.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    /**
     * `media_viewport` do servidor (contrato v2.1): `x`,`y`,`w`,`h` são **porcentagem 0–100** da tela,
     * `object_fit` é `contain|cover` e `border_radius` é opcional (px, 0–200).
     *
     * Sempre porcentagem — sem adivinhar "0..1": com a unidade fixada, `w: 1` significa 1% (e é
     * rejeitado por ser pequeno demais), nunca "tela cheia". Aceita `fit` como apelido de `object_fit`.
     */
    fun parseViewport(o: JSONObject?): MediaViewport? {
        if (o == null) return null
        val x = o.optDouble("x", Double.NaN)
        val y = o.optDouble("y", Double.NaN)
        val w = o.optDouble("w", Double.NaN)
        val h = o.optDouble("h", Double.NaN)
        if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN()) return null
        val cx = (x / 100.0).coerceIn(0.0, 1.0)
        val cy = (y / 100.0).coerceIn(0.0, 1.0)
        // Retângulo degenerado ou fora da tela encolheria o vídeo pra nada: trata como "sem squeeze".
        val cw = (w / 100.0).coerceIn(0.0, 1.0 - cx)
        val ch = (h / 100.0).coerceIn(0.0, 1.0 - cy)
        if (cw <= MIN_VIEWPORT_SIDE || ch <= MIN_VIEWPORT_SIDE) return null
        val rawFit = o.optString("object_fit", "").ifBlank { o.optString("fit", "contain") }
        val fit = if (rawFit.equals("cover", ignoreCase = true)) ViewportFit.COVER else ViewportFit.CONTAIN
        val radius = o.optDouble("border_radius", 0.0).let { if (it.isNaN()) 0.0 else it }.coerceIn(0.0, 200.0).toInt()
        return MediaViewport(cx.toFloat(), cy.toFloat(), cw.toFloat(), ch.toFloat(), fit, radius)
    }

    fun parseProducts(arr: JSONArray?): List<PriceProduct> {
        if (arr == null) return emptyList()
        val out = ArrayList<PriceProduct>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name", "").trim()
            if (name.isEmpty()) continue
            val price = p.optDouble("price", Double.NaN).takeIf { !it.isNaN() }
            out += PriceProduct(
                code = p.optString("code", "").takeIf { it.isNotBlank() && it != "null" },
                name = name,
                price = price,
                unit = p.optString("unit", "").trim().takeIf { it.isNotEmpty() && it != "null" },
                isPromo = p.optBoolean("is_promo", false),
                promoLabel = p.optString("promo_label", "").trim().takeIf { it.isNotEmpty() && it != "null" },
            )
        }
        return out
    }

    /** Posição (px) e tamanho de um [MediaViewport] numa tela [screenW] x [screenH]. */
    data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

    fun viewportToPixels(vp: MediaViewport?, screenW: Int, screenH: Int): PixelRect {
        if (vp == null) return PixelRect(0, 0, screenW, screenH)
        return PixelRect(
            left = Math.round(vp.x * screenW),
            top = Math.round(vp.y * screenH),
            width = Math.round(vp.w * screenW).coerceAtLeast(1),
            height = Math.round(vp.h * screenH).coerceAtLeast(1),
        )
    }

    private const val MIN_OVERLAY_DURATION_MS = 1_000L
    private const val MIN_VIEWPORT_SIDE = 0.05
}
