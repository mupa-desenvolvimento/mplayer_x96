package com.mupa.player.enterprise.player

import android.animation.ValueAnimator
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mupa.player.enterprise.managers.DynamicManifest
import com.mupa.player.enterprise.managers.ManifestOverlay
import com.mupa.player.enterprise.managers.MediaViewport
import com.mupa.player.enterprise.managers.ViewportFit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Trilha 2 (L-Bar / Squeeze): WebView transparente em tela cheia por cima do player, e o vídeo
 * da Trilha 1 encolhido para `media_viewport` enquanto o overlay estiver no ar.
 *
 * Garantias que importam num painel de rua:
 *  - a Trilha 1 NUNCA espera a Trilha 2: este controlador só observa o que o [PlaylistEngine] avisa
 *    ([onCycleStart], [onItemShown]) e mexe em views; não segura nem atrasa nenhuma troca;
 *  - o vídeo só encolhe quando a página diz `ready` — falha de rede, `empty` ou silêncio deixam
 *    a mídia em tela cheia, nunca uma moldura vazia;
 *  - durante itens `web`/`price_table` o overlay some e o vídeo volta a 0,0,1,1.
 *
 * Toda a classe roda na thread principal.
 */
internal class OverlayController(
    private val overlayHost: FrameLayout,
    private val playerContainer: View,
    private val playerViews: List<PlayerView>,
    private val imageViews: List<ImageView>,
    private val scope: CoroutineScope,
    private val useSoftwareLayer: () -> Boolean,
    private val hasNetwork: () -> Boolean,
) : PlayerEngine.PlaybackListener {

    private var overlays: List<ManifestOverlay> = emptyList()
    private var cycleStartAt: Long = -1L
    private var trackOneBlocks = false
    private var suspended = false

    private var slide: DynamicWebSlide? = null
    private var loadedUrl: String? = null
    private var loadJob: Job? = null
    private var lastLoadFailAt = 0L

    /** Falhas seguidas de carga: o intervalo entre tentativas dobra (falha de script não se resolve sozinha). */
    private var loadFailures = 0
    private var tickJob: Job? = null

    private var showing = false
    private var appliedViewport: MediaViewport? = null
    private var animator: ValueAnimator? = null

    /** Raio atual dos cantos do container da Trilha 1, em px de tela (0 = sem arredondar). */
    private var cornerRadiusPx = 0f

    // ---- API usada pela PlayerActivity -------------------------------------------------

    fun setOverlays(list: List<ManifestOverlay>) {
        if (list == overlays) return
        overlays = list
        // Programação nova: dá uma chance limpa à página, sem herdar o backoff da anterior.
        loadFailures = 0
        lastLoadFailAt = 0L
        if (list.isEmpty()) {
            tickJob?.cancel()
            tickJob = null
            hideNow()
            destroySlide()
            return
        }
        // Pré-aquecimento (5.5): carrega a página do primeiro overlay já, invisível.
        ensureLoaded(list.first().url)
        if (tickJob?.isActive != true) tickJob = scope.launch { tickLoop() }
    }

    /** Device inoperante: derruba o overlay imediatamente e impede que volte. */
    fun setSuspended(value: Boolean) {
        suspended = value
        evaluate()
    }

    fun release() {
        tickJob?.cancel()
        tickJob = null
        animator?.cancel()
        hideNow()
        destroySlide()
    }

    // ---- PlayerEngine.PlaybackListener -------------------------------------------------

    override fun onCycleStart() {
        cycleStartAt = SystemClock.elapsedRealtime()
        evaluate()
    }

    override fun onItemShown(type: String) {
        trackOneBlocks = DynamicManifest.blocksOverlay(type)
        evaluate()
    }

    // ---- Núcleo ------------------------------------------------------------------------

    private suspend fun tickLoop() {
        while (scope.isActive) {
            evaluate()
            delay(TICK_MS)
        }
    }

    private fun evaluate() {
        if (overlays.isEmpty()) return
        val cycleMs = if (cycleStartAt < 0L) -1L else SystemClock.elapsedRealtime() - cycleStartAt
        val active = OverlayScheduler.activeAt(overlays, cycleMs)

        // Mantém a página certa carregada: a do overlay ativo ou a do próximo que vai entrar.
        val wanted = active ?: OverlayScheduler.upcomingWithin(overlays, cycleMs, PRELOAD_LEAD_MS)
        if (wanted != null) ensureLoaded(wanted.url)

        val state = slide?.takeIf { loadedUrl == active?.url }?.bridgeState
        val show = OverlayScheduler.shouldShow(active, state, trackOneBlocks, suspended)

        if (show && active != null) {
            showNow(active)
        } else if (showing) {
            hideNow()
        }
    }

    private fun ensureLoaded(url: String) {
        if (slide != null && loadedUrl == url) return
        // Falhou há pouco: espera antes de tentar de novo, pra não martelar um servidor fora do ar.
        if (lastLoadFailAt != 0L && SystemClock.elapsedRealtime() - lastLoadFailAt < retryDelayMs()) return

        loadJob?.cancel()
        destroySlide()

        val s = DynamicWebSlide(overlayHost.context, transparent = true, softwareLayer = useSoftwareLayer())
        s.onBridgeState = { overlayHost.post { evaluate() } }
        overlayHost.addView(s.webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        slide = s
        loadedUrl = url

        loadJob = scope.launch {
            val result = s.load(url, LOAD_TIMEOUT_MS, offline = !hasNetwork(), bridgeGraceMs = 0L, mountTimeoutMs = MOUNT_TIMEOUT_MS)
            if (result == WebLoadResult.FAILED && slide === s) {
                Log.w(TAG, "overlay_load_failed url=$url")
                lastLoadFailAt = SystemClock.elapsedRealtime()
                loadFailures++
                destroySlide()
                hideNow()
            } else {
                lastLoadFailAt = 0L
                loadFailures = 0
            }
        }
    }

    /** 30 s, 60 s, 2 min, 4 min... até 10 min. Cada tentativa baixa o SPA inteiro numa box fraca. */
    private fun retryDelayMs(): Long =
        (LOAD_RETRY_MS shl (loadFailures - 1).coerceIn(0, 5)).coerceAtMost(MAX_LOAD_RETRY_MS)

    private fun destroySlide() {
        slide?.destroy()
        slide = null
        loadedUrl = null
    }

    private fun showNow(active: ManifestOverlay) {
        if (!showing) {
            Log.i(TAG, "overlay_show id=${active.id}")
            overlayHost.visibility = View.VISIBLE
            slide?.onResume()
            showing = true
        }
        val target = active.viewport?.takeUnless { it.isFullScreen }
        if (target != appliedViewport) {
            applyViewport(target)
            appliedViewport = target
        }
    }

    private fun hideNow() {
        if (showing) Log.i(TAG, "overlay_hide")
        overlayHost.visibility = View.INVISIBLE
        showing = false
        if (appliedViewport != null) {
            applyViewport(null)
            appliedViewport = null
        }
    }

    // ---- Squeeze da Trilha 1 -----------------------------------------------------------

    private fun applyViewport(vp: MediaViewport?) {
        val root = playerContainer.parent as? View ?: return
        val screenW = root.width
        val screenH = root.height
        if (screenW <= 0 || screenH <= 0) return

        val lp = playerContainer.layoutParams as? FrameLayout.LayoutParams ?: return
        val fromLeft = lp.leftMargin
        val fromTop = lp.topMargin
        val fromW = if (lp.width > 0) lp.width else screenW
        val fromH = if (lp.height > 0) lp.height else screenH

        val to = DynamicManifest.viewportToPixels(vp, screenW, screenH)

        setFit(vp?.fit)
        setCornerRadius(vp, screenW)

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SQUEEZE_MS
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.gravity = android.view.Gravity.NO_GRAVITY
                lp.leftMargin = lerp(fromLeft, to.left, f)
                lp.topMargin = lerp(fromTop, to.top, f)
                lp.width = lerp(fromW, to.width, f)
                lp.height = lerp(fromH, to.height, f)
                playerContainer.layoutParams = lp
                if (cornerRadiusPx > 0f) playerContainer.invalidateOutline()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (vp == null) {
                        // Voltou à tela cheia: restaura o layout original (match_parent), sem resto de margem.
                        lp.leftMargin = 0
                        lp.topMargin = 0
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        playerContainer.layoutParams = lp
                    }
                }
            })
            start()
        }
    }

    /**
     * `border_radius` (best-effort do contrato v2.1): arredonda os cantos do container da Trilha 1
     * enquanto encolhido. O raio vem em px de design (tela de 1920), então escala pela largura real.
     */
    private fun setCornerRadius(vp: MediaViewport?, screenW: Int) {
        cornerRadiusPx = if (vp == null || vp.borderRadius <= 0) 0f else vp.borderRadius * screenW / REFERENCE_WIDTH_PX
        if (cornerRadiusPx > 0f) {
            playerContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            playerContainer.clipToOutline = true
        } else {
            playerContainer.clipToOutline = false
            playerContainer.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        playerContainer.invalidateOutline()
    }

    private fun setFit(fit: ViewportFit?) {
        val (resize, scale) = when (fit) {
            null -> AspectRatioFrameLayout.RESIZE_MODE_FILL to ImageView.ScaleType.FIT_XY
            ViewportFit.CONTAIN -> AspectRatioFrameLayout.RESIZE_MODE_FIT to ImageView.ScaleType.FIT_CENTER
            ViewportFit.COVER -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to ImageView.ScaleType.CENTER_CROP
        }
        playerViews.forEach { it.resizeMode = resize }
        imageViews.forEach { it.scaleType = scale }
    }

    private fun lerp(from: Int, to: Int, f: Float): Int = from + ((to - from) * f).toInt()

    private companion object {
        const val TAG = "MPlayerOverlay"
        const val TICK_MS = 250L
        const val PRELOAD_LEAD_MS = 10_000L
        const val LOAD_TIMEOUT_MS = 20_000L
        const val LOAD_RETRY_MS = 30_000L
        const val MAX_LOAD_RETRY_MS = 10 * 60_000L
        const val MOUNT_TIMEOUT_MS = 8_000L
        const val SQUEEZE_MS = 300L
        const val REFERENCE_WIDTH_PX = 1920f
    }
}
