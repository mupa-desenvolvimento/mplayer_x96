package com.mupa.player.enterprise.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mupa.player.enterprise.managers.PriceProduct
import java.text.NumberFormat
import java.util.Locale

data class PriceTableData(
    val sector: String?,
    val products: List<PriceProduct>,
)

/**
 * Tabela de preços nativa (item `price_table` — Açougue, Padaria, Hortifrúti...).
 *
 * Fundo = [backgroundView] (arquivo baixado pelo sync) + painel escuro translúcido com setor e
 * uma linha por produto. Quando há mais produtos do que cabem, vira páginas que se alternam
 * dentro da duração do item. Tamanhos de texto derivam da altura da linha, então serve tanto
 * 1920x1080 quanto 1080x1920.
 *
 * Os produtos vêm do manifesto já salvo em disco, então funciona 100% offline.
 */
class PriceTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val backgroundView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val scrim = View(context).apply { setBackgroundColor(SCRIM_COLOR) }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val handler = Handler(Looper.getMainLooper())
    private val priceFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))

    private var data: PriceTableData? = null
    private var itemDurationMs: Long = DEFAULT_DURATION_MS
    private var pages: List<List<PriceProduct>> = emptyList()
    private var pageIndex = 0
    private var pagingActive = false

    private val nextPage = object : Runnable {
        override fun run() {
            if (!pagingActive || pages.size < 2) return
            pageIndex = (pageIndex + 1) % pages.size
            renderPage()
            handler.postDelayed(this, pageDurationMs())
        }
    }

    init {
        addView(backgroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    fun bind(table: PriceTableData, durationMs: Long?) {
        stopPaging()
        data = table
        itemDurationMs = (durationMs ?: DEFAULT_DURATION_MS).coerceAtLeast(1_000L)
        pageIndex = 0
        rebuildPages()
        renderPage()
    }

    /** Começa a alternar páginas — chamado quando o item passa a ser o exibido, não no pré-carregamento. */
    fun startPaging() {
        stopPaging()
        pageIndex = 0
        renderPage()
        if (pages.size < 2) return
        pagingActive = true
        handler.postDelayed(nextPage, pageDurationMs())
    }

    fun stopPaging() {
        pagingActive = false
        handler.removeCallbacks(nextPage)
    }

    fun clear() {
        stopPaging()
        data = null
        pages = emptyList()
        content.removeAllViews()
        backgroundView.setImageDrawable(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (data != null && w > 0 && h > 0) {
            rebuildPages()
            renderPage()
        }
    }

    override fun onDetachedFromWindow() {
        stopPaging()
        super.onDetachedFromWindow()
    }

    private fun pageDurationMs(): Long =
        (itemDurationMs / pages.size.coerceAtLeast(1)).coerceAtLeast(MIN_PAGE_MS)

    private fun layoutMetrics(): Metrics? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val unit = minOf(h / 100f, w / 178f)
        val headerH = (unit * 16f).toInt()
        val padding = (unit * 3f).toInt()
        val naturalRow = unit * 9f
        val rows = ((h - headerH - padding * 2) / naturalRow).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
        val rowH = (h - headerH - padding * 2) / rows
        return Metrics(unit, headerH, padding, rows, rowH)
    }

    private fun rebuildPages() {
        val table = data ?: return
        val m = layoutMetrics()
        val perPage = m?.rows ?: MAX_ROWS
        pages = if (table.products.isEmpty()) listOf(emptyList()) else table.products.chunked(perPage)
        if (pageIndex >= pages.size) pageIndex = 0
    }

    private fun renderPage() {
        val table = data ?: return
        val m = layoutMetrics() ?: return
        content.removeAllViews()
        content.setPadding(m.padding * 2, m.padding, m.padding * 2, m.padding)

        val header = TextView(context).apply {
            text = (table.sector ?: "Tabela de preços").uppercase(Locale("pt", "BR"))
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, m.headerH * 0.62f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, m.headerH))

        val page = pages.getOrNull(pageIndex).orEmpty()
        if (page.isEmpty()) {
            content.addView(
                TextView(context).apply {
                    text = "Sem produtos no momento"
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, m.rowH * 0.5f)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            return
        }

        for (p in page) content.addView(buildRow(p, m), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, m.rowH))

        if (pages.size > 1) {
            // Indicador discreto de página, dentro da área do cabeçalho.
            header.text = "${header.text}   ${pageIndex + 1}/${pages.size}"
        }
    }

    private fun buildRow(p: PriceProduct, m: Metrics): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (p.isPromo) PROMO_ROW_COLOR else Color.TRANSPARENT)
        }
        val textPx = m.rowH * 0.42f

        row.addView(
            TextView(context).apply {
                text = p.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        if (p.isPromo) {
            val label = p.promoLabel ?: "OFERTA"
            row.addView(
                TextView(context).apply {
                    text = label.uppercase(Locale("pt", "BR"))
                    setTextColor(Color.BLACK)
                    setBackgroundColor(PROMO_BADGE_COLOR)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * 0.7f)
                    setPadding(m.padding, 0, m.padding, 0)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = m.padding
                },
            )
        }

        val priceText = p.price?.let { priceFormat.format(it).replace(' ', ' ') } ?: "—"
        val unit = p.unit?.let { " / ${it.uppercase(Locale("pt", "BR"))}" }.orEmpty()
        row.addView(
            TextView(context).apply {
                text = priceText + unit
                setTextColor(if (p.isPromo) PROMO_PRICE_COLOR else Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx * 1.25f)
                maxLines = 1
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return row
    }

    private data class Metrics(val unit: Float, val headerH: Int, val padding: Int, val rows: Int, val rowH: Int)

    private companion object {
        const val SCRIM_COLOR = 0xB3000000.toInt()
        const val PROMO_ROW_COLOR = 0x33FF3B30
        const val PROMO_BADGE_COLOR = 0xFFFFD60A.toInt()
        const val PROMO_PRICE_COLOR = 0xFFFFD60A.toInt()
        const val DEFAULT_DURATION_MS = 15_000L
        const val MIN_PAGE_MS = 4_000L
        const val MIN_ROWS = 3
        const val MAX_ROWS = 12
    }
}
