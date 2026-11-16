package com.mupa.player.enterprise.player

import kotlinx.coroutines.CoroutineScope
import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val layerA: LayerViews,
    private val layerB: LayerViews,
    private val telemetrySink: PlayerTelemetrySink? = null,
) {
    data class PlaybackItem(
        val id: String,
        val type: String,
        /** Vídeo/imagem: o arquivo local. `price_table`: o fundo (opcional). `web`: sempre null. */
        val file: File?,
        val durationMs: Long?,
        val volume: Float?,
        val offsetStartMs: Long?,
        val offsetEndMs: Long?,
        /** `type == "web"`: página exibida em WebView (nunca baixada). */
        val webUrl: String? = null,
        /** `type == "price_table"`: dados desenhados nativamente. */
        val priceTable: PriceTableData? = null,
    )

    data class LayerViews(
        val container: View,
        val playerView: PlayerView,
        val imageView: ImageView,
        /** Hospeda a WebView dos itens `web` (criada sob demanda, destruída ao sair da camada). */
        val webHost: android.widget.FrameLayout,
        val priceTableView: PriceTableView,
    )

    /** Avisos do motor para quem coordena a Trilha 2 (overlays). Sempre chamados na thread principal. */
    interface PlaybackListener {
        /** Um novo ciclo da playlist começou (índice 0 entrou em cena). */
        fun onCycleStart()

        /** O item [type] passou a ser o exibido na Trilha 1. */
        fun onItemShown(type: String)
    }

    fun setPlaybackListener(listener: PlaybackListener?) {
        playlistEngine.listener = listener
    }

    private val profile = PlaybackProfile.detect(context.applicationContext)
    private val videoEngine = VideoEngine(context.applicationContext, profile, layerA, layerB)
    private val imageEngine = ImageEngine(context.applicationContext)
    private val transitionEngine = TransitionEngine(profile)
    private val playlistEngine =
        PlaylistEngine(
            context = context.applicationContext,
            scope = scope,
            profile = profile,
            layerA = layerA,
            layerB = layerB,
            videoEngine = videoEngine,
            imageEngine = imageEngine,
            transitionEngine = transitionEngine,
            telemetrySink = telemetrySink,
        )
    private val releasedRef = AtomicBoolean(false)

    fun getCurrentItemId(): String? = playlistEngine.getCurrentItemId()

    fun setTransitionConfig(config: TransitionConfig) {
        transitionEngine.setConfig(config)
    }

    fun start(items: List<PlaybackItem>) {
        if (releasedRef.get()) return
        playlistEngine.start(items)
    }

    fun setPlaylist(items: List<PlaybackItem>) {
        if (releasedRef.get()) return
        playlistEngine.setPlaylist(items)
    }

    fun pause() {
        if (releasedRef.get()) return
        playlistEngine.pause()
    }

    fun resume() {
        if (releasedRef.get()) return
        playlistEngine.resume()
    }

    fun stop() {
        if (releasedRef.get()) return
        playlistEngine.stop()
    }

    fun release() {
        if (!releasedRef.compareAndSet(false, true)) return
        playlistEngine.release()
    }
}
