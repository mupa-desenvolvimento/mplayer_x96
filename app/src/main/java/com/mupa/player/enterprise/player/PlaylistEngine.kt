package com.mupa.player.enterprise.player

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class PlaylistEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: PlaybackProfile,
    private val layerA: PlayerEngine.LayerViews,
    private val layerB: PlayerEngine.LayerViews,
    private val videoEngine: VideoEngine,
    private val imageEngine: ImageEngine,
    private val webSlideEngine: WebSlideEngine,
    private val transitionEngine: TransitionEngine,
    private val telemetrySink: PlayerTelemetrySink?,
) {
    private var activeLayerIndex = 0
    private var loopJob: Job? = null
    private val pendingPlaylist = AtomicReference<List<PlayerEngine.PlaybackItem>?>(null)
    private val currentItemIdRef = AtomicReference<String?>(null)
    private val currentItemTypeRef = AtomicReference<String?>(null)
    private val systemMetrics = SystemMetrics(context)
    private val pausedRef = AtomicBoolean(false)
    private val releasedRef = AtomicBoolean(false)
    private val activeVideoSlotRef = AtomicReference<VideoEngine.Slot?>(null)
    private val activeVideoVolumeRef = AtomicReference<Float>(1f)
    private var lastFpsAFrames: Long = 0L
    private var lastFpsATime: Long = 0L
    private var lastFpsBFrames: Long = 0L
    private var lastFpsBTime: Long = 0L

    fun getCurrentItemId(): String? = currentItemIdRef.get()

    private companion object {
        const val WEB_TYPE = "web"

        /** Tempo dado à página para montar o conteúdo em JS antes de entrar no crossfade. */
        const val WEB_SETTLE_MS = 600L
    }

    fun pause() {
        if (releasedRef.get()) return
        pausedRef.set(true)
        videoEngine.pause(VideoEngine.Slot.A)
        videoEngine.pause(VideoEngine.Slot.B)
    }

    fun resume() {
        if (releasedRef.get()) return
        pausedRef.set(false)
        val type = currentItemTypeRef.get()
        if (!type.equals("video", ignoreCase = true)) return
        val slot = activeVideoSlotRef.get() ?: return
        val volume = activeVideoVolumeRef.get().coerceIn(0f, 1f)
        videoEngine.ensureOnlyOnePlaying(slot)
        videoEngine.play(slot, volume)
    }

    fun start(items: List<PlayerEngine.PlaybackItem>) {
        if (releasedRef.get()) return
        stop()
        if (items.isEmpty()) return

        layerA.container.alpha = 0f
        layerB.container.alpha = 0f
        layerA.container.visibility = View.VISIBLE
        layerB.container.visibility = View.VISIBLE
        activeLayerIndex = 0

        loopJob =
            scope.launch {
                var playlist = items
                var index = 0

                val first = playlist[index]
                prepareInto(activeLayer(), activeSlot(), first)
                startPrepared(activeSlot(), first)
                layer(activeLayerIndex).container.visibility = View.VISIBLE
                layer(activeLayerIndex).container.alpha = 1f

                while (isActive) {
                    val currentItem = playlist[index]
                    val nextIndex = (index + 1) % playlist.size
                    val nextItem = playlist[nextIndex]
                    val inactive = inactiveLayer()
                    val inactiveSlot = inactiveSlot()

                    val t0 = SystemClock.elapsedRealtime()
                    prepareInto(inactive, inactiveSlot, nextItem)
                    val preloadMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)

                    waitForSwitch(currentItem, activeSlot(), preloadMs)

                    val pending = pendingPlaylist.getAndSet(null)
                    if (!pending.isNullOrEmpty()) {
                        playlist = pending
                        index = 0
                        val pFirst = playlist[index]
                        prepareInto(inactive, inactiveSlot, pFirst)
                        transitionToPrepared(currentItem, activeLayer(), activeSlot(), inactive, inactiveSlot, pFirst)
                        clearLayer(activeLayer(), activeSlot())
                        toggleActiveLayer()
                        continue
                    }

                    transitionToPrepared(currentItem, activeLayer(), activeSlot(), inactive, inactiveSlot, nextItem)
                    clearLayer(activeLayer(), activeSlot())
                    toggleActiveLayer()
                    index = nextIndex
                }
            }
    }

    fun setPlaylist(items: List<PlayerEngine.PlaybackItem>) {
        if (releasedRef.get()) return
        if (items.isEmpty()) return
        if (loopJob == null) start(items) else pendingPlaylist.set(items)
    }

    fun stop() {
        if (releasedRef.get()) return
        stopInternal()
    }

    private fun stopInternal() {
        loopJob?.cancel()
        loopJob = null
        clearLayer(layerA, VideoEngine.Slot.A)
        clearLayer(layerB, VideoEngine.Slot.B)
        showFallback(layerA)
        showFallback(layerB)
        layerA.container.alpha = 1f
        layerB.container.alpha = 0f
        activeLayerIndex = 0
    }

    fun release() {
        if (!releasedRef.compareAndSet(false, true)) return
        stopInternal()
        runCatching { videoEngine.player(VideoEngine.Slot.A).release() }
        runCatching { videoEngine.player(VideoEngine.Slot.B).release() }
    }

    private fun layer(index: Int): PlayerEngine.LayerViews = if (index == 0) layerA else layerB
    private fun activeLayer(): PlayerEngine.LayerViews = layer(activeLayerIndex)
    private fun inactiveLayer(): PlayerEngine.LayerViews = layer(if (activeLayerIndex == 0) 1 else 0)
    private fun activeSlot(): VideoEngine.Slot = if (activeLayerIndex == 0) VideoEngine.Slot.A else VideoEngine.Slot.B
    private fun inactiveSlot(): VideoEngine.Slot = if (activeLayerIndex == 0) VideoEngine.Slot.B else VideoEngine.Slot.A

    private fun toggleActiveLayer() {
        activeLayerIndex = if (activeLayerIndex == 0) 1 else 0
    }

    private suspend fun prepareInto(layer: PlayerEngine.LayerViews, slot: VideoEngine.Slot, item: PlayerEngine.PlaybackItem) {
        when (item.type.lowercase()) {
            "video" -> {
                layer.imageView.visibility = View.GONE
                layer.webView.visibility = View.GONE
                layer.playerView.visibility = View.VISIBLE
                videoEngine.stopAndClear(slot)
                videoEngine.clearError(slot)
                videoEngine.prepare(slot, item)
            }
            WEB_TYPE -> {
                layer.playerView.visibility = View.GONE
                layer.imageView.visibility = View.GONE
                layer.webView.visibility = View.VISIBLE
                videoEngine.stopAndClear(slot)
                webSlideEngine.resume(layer.webView)
                item.url?.let { webSlideEngine.load(layer.webView, it) }
                // Respiro antes do crossfade: a página monta o conteúdo em JS, e entrar com ela
                // ainda em branco produziria o mesmo flash que o fundo escuro tenta evitar.
                delay(WEB_SETTLE_MS)
            }
            else -> {
                layer.playerView.visibility = View.GONE
                layer.webView.visibility = View.GONE
                layer.imageView.visibility = View.VISIBLE
                videoEngine.stopAndClear(slot)
                imageEngine.clear(layer.imageView)
                // `file` só é nulo em item web, que não chega aqui.
                item.file?.let { imageEngine.loadInto(layer.imageView, it).join() }
            }
        }
    }

    private fun startPrepared(slot: VideoEngine.Slot, item: PlayerEngine.PlaybackItem) {
        android.util.Log.i(
            "MPlayerItem",
            "item_no_ar type=${item.type} id=${item.id} duracao=${item.durationMs}ms",
        )
        currentItemIdRef.set(item.id)
        currentItemTypeRef.set(item.type)
        activeVideoSlotRef.set(if (item.type.equals("video", ignoreCase = true)) slot else null)
        activeVideoVolumeRef.set((item.volume ?: 1f).coerceIn(0f, 1f))

        when (item.type.lowercase()) {
            "video" -> {
                videoEngine.ensureOnlyOnePlaying(slot)
                videoEngine.play(slot, (item.volume ?: 1f).coerceIn(0f, 1f))
            }
            else -> Unit
        }
    }

    private suspend fun transitionToPrepared(
        currentItem: PlayerEngine.PlaybackItem,
        currentLayer: PlayerEngine.LayerViews,
        currentSlot: VideoEngine.Slot,
        nextLayer: PlayerEngine.LayerViews,
        nextSlot: VideoEngine.Slot,
        nextItem: PlayerEngine.PlaybackItem,
    ) {
        val cfg = transitionEngine.getConfig()
        var transitionMs = transitionEngine.effectiveDurationMs()

        if (currentItem.type.equals("video", ignoreCase = true)) {
            videoEngine.pause(currentSlot)
        }

        if (nextItem.type.equals("video", ignoreCase = true)) {
            videoEngine.ensureOnlyOnePlaying(nextSlot)
            videoEngine.play(nextSlot, (nextItem.volume ?: 1f).coerceIn(0f, 1f))
        }

        currentItemIdRef.set(nextItem.id)
        currentItemTypeRef.set(nextItem.type)
        activeVideoSlotRef.set(if (nextItem.type.equals("video", ignoreCase = true)) nextSlot else null)
        activeVideoVolumeRef.set((nextItem.volume ?: 1f).coerceIn(0f, 1f))

        if (nextItem.type.equals("video", ignoreCase = true)) {
            nextLayer.container.visibility = View.VISIBLE
            nextLayer.container.alpha = 0f
            nextLayer.playerView.visibility = View.VISIBLE
            nextLayer.imageView.visibility = View.GONE
            val got = videoEngine.awaitFirstFrame(nextSlot, profile.firstFrameWaitMs)
            if (!got) {
                transitionMs = 0L
            }
        }

        if (transitionMs <= 0L) {
            transitionEngine.swap(currentLayer.container, nextLayer.container, 0L)
            return
        }

        val currentIsVideo = currentItem.type.equals("video", ignoreCase = true)
        val nextIsVideo = nextItem.type.equals("video", ignoreCase = true)

        if (cfg.mode == TransitionConfig.Mode.FADE && currentIsVideo && nextIsVideo) {
            transitionEngine.fadeThrough(currentLayer.container, nextLayer.container, transitionMs)
        } else {
            transitionEngine.swap(currentLayer.container, nextLayer.container, transitionMs)
        }
    }

    private suspend fun waitForSwitch(item: PlayerEngine.PlaybackItem, slot: VideoEngine.Slot, alreadySpentMs: Long) {
        val t0 = SystemClock.elapsedRealtime()
        when (item.type.lowercase()) {
            "video" -> {
                val p = videoEngine.player(slot)
                withTimeoutOrNull(4 * 60 * 60 * 1000L) { awaitVideoEndedOrStalled(p, slot) }
            }
            else -> {
                val duration = (item.durationMs ?: 8000L).coerceAtLeast(200L)
                val fade = transitionEngine.effectiveDurationMs()
                var remainingMs = (duration - fade - alreadySpentMs).coerceAtLeast(0L)
                while (remainingMs > 0L) {
                    if (pausedRef.get()) {
                        delay(200L)
                        continue
                    }
                    val step = remainingMs.coerceAtMost(200L)
                    delay(step)
                    remainingMs -= step
                }
            }
        }
        val elapsed = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
        if (elapsed > 0L) emitTelemetry(slot)
    }

    private suspend fun awaitVideoEndedOrStalled(player: androidx.media3.exoplayer.ExoPlayer, slot: VideoEngine.Slot) {
        var lastPos = player.currentPosition
        var lastAdvancedAt = SystemClock.elapsedRealtime()
        var lastTelemetryAt = 0L

        while (true) {
            if (pausedRef.get()) {
                delay(200L)
                continue
            }
            val state = player.playbackState
            if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) return

            val now = SystemClock.elapsedRealtime()
            val isPlaying = player.isPlaying || (player.playWhenReady && state == Player.STATE_READY)

            val m = videoEngine.metrics(slot)
            val lastFrameAt = m.lastRenderedFrameAtMs
            val hasError = !m.lastError.isNullOrBlank()

            if (hasError) return

            if (state == Player.STATE_BUFFERING) {
                lastPos = player.currentPosition
                lastAdvancedAt = now
            } else if (isPlaying) {
                val pos = player.currentPosition
                if (pos > lastPos + 80L) {
                    lastPos = pos
                    lastAdvancedAt = now
                } else {
                    val stalledByPos = (now - lastAdvancedAt) >= profile.videoStallMs
                    val stalledByFrames = lastFrameAt > 0L && (now - lastFrameAt) >= profile.videoStallMs
                    if (stalledByPos || stalledByFrames) return
                }
            } else {
                lastPos = player.currentPosition
                lastAdvancedAt = now
            }

            if (telemetrySink != null && now - lastTelemetryAt >= 2_000L) {
                lastTelemetryAt = now
                emitTelemetry(slot)
            }

            delay(profile.watchdogPollMs)
        }
    }

    private fun emitTelemetry(slot: VideoEngine.Slot) {
        val sink = telemetrySink ?: return
        val type = currentItemTypeRef.get()
        val itemId = currentItemIdRef.get()

        val p = videoEngine.player(slot)
        val videoMetrics = videoEngine.metrics(slot)

        val fps = if (type.equals("video", ignoreCase = true)) computeFps(slot, videoMetrics.renderedFrames) else null

        val cpu = systemMetrics.readCpuPercent()
        val (usedRam, totalRam) = systemMetrics.readRamMb()
        val temp = systemMetrics.readTemperatureC()

        sink.onTelemetry(
            PlayerTelemetry(
                itemId = itemId,
                itemType = type,
                playbackState = if (type.equals("video", ignoreCase = true)) p.playbackState else null,
                isPlaying = if (type.equals("video", ignoreCase = true)) p.isPlaying else null,
                renderedFps = fps,
                droppedFrames = videoMetrics.droppedFrames.takeIf { it > 0L },
                cpuPercent = cpu,
                usedRamMb = usedRam,
                totalRamMb = totalRam,
                temperatureC = temp,
                lastError = videoMetrics.lastError,
            ),
        )
    }

    private fun computeFps(slot: VideoEngine.Slot, totalFrames: Long): Float? {
        val now = SystemClock.elapsedRealtime()
        return if (slot == VideoEngine.Slot.A) {
            val prevT = lastFpsATime
            val prevF = lastFpsAFrames
            lastFpsATime = now
            lastFpsAFrames = totalFrames
            if (prevT <= 0L) null else {
                val dt = (now - prevT).coerceAtLeast(1L)
                val df = (totalFrames - prevF).coerceAtLeast(0L)
                (df.toFloat() * 1000f / dt.toFloat()).coerceAtLeast(0f)
            }
        } else {
            val prevT = lastFpsBTime
            val prevF = lastFpsBFrames
            lastFpsBTime = now
            lastFpsBFrames = totalFrames
            if (prevT <= 0L) null else {
                val dt = (now - prevT).coerceAtLeast(1L)
                val df = (totalFrames - prevF).coerceAtLeast(0L)
                (df.toFloat() * 1000f / dt.toFloat()).coerceAtLeast(0f)
            }
        }
    }

    private fun clearLayer(layer: PlayerEngine.LayerViews, slot: VideoEngine.Slot) {
        layer.container.alpha = 0f
        layer.container.visibility = View.INVISIBLE
        layer.playerView.visibility = View.INVISIBLE
        layer.imageView.visibility = View.INVISIBLE
        layer.webView.visibility = View.INVISIBLE
        videoEngine.stopAndClear(slot)
        imageEngine.clear(layer.imageView)
        webSlideEngine.clear(layer.webView)
    }

    private fun showFallback(layer: PlayerEngine.LayerViews) {
        layer.playerView.visibility = View.GONE
        layer.webView.visibility = View.GONE
        layer.imageView.visibility = View.VISIBLE
        imageEngine.showFallback(layer.imageView)
    }
}
