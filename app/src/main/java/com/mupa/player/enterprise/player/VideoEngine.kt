package com.mupa.player.enterprise.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class VideoEngine(
    private val context: Context,
    private val profile: PlaybackProfile,
    private val layerA: PlayerEngine.LayerViews,
    private val layerB: PlayerEngine.LayerViews,
) {
    enum class Slot { A, B }

    data class VideoMetrics(
        val lastRenderedFrameAtMs: Long,
        val renderedFrames: Long,
        val droppedFrames: Long,
        val lastError: String?,
    )

    private val playerARef = AtomicReference<ExoPlayer>(newPlayer())
    private val playerBRef = AtomicReference<ExoPlayer>(newPlayer())

    private val aFrames = AtomicLong(0L)
    private val bFrames = AtomicLong(0L)
    private val aDropped = AtomicLong(0L)
    private val bDropped = AtomicLong(0L)
    private val aLastFrameAt = AtomicLong(0L)
    private val bLastFrameAt = AtomicLong(0L)
    private val aLastError = AtomicReference<String?>(null)
    private val bLastError = AtomicReference<String?>(null)

    init {
        bind(layerA.playerView, Slot.A)
        bind(layerB.playerView, Slot.B)
    }

    fun player(slot: Slot): ExoPlayer = if (slot == Slot.A) playerARef.get() else playerBRef.get()

    fun metrics(slot: Slot): VideoMetrics {
        return if (slot == Slot.A) {
            VideoMetrics(
                lastRenderedFrameAtMs = aLastFrameAt.get(),
                renderedFrames = aFrames.get(),
                droppedFrames = aDropped.get(),
                lastError = aLastError.get(),
            )
        } else {
            VideoMetrics(
                lastRenderedFrameAtMs = bLastFrameAt.get(),
                renderedFrames = bFrames.get(),
                droppedFrames = bDropped.get(),
                lastError = bLastError.get(),
            )
        }
    }

    fun clearError(slot: Slot) {
        if (slot == Slot.A) aLastError.set(null) else bLastError.set(null)
    }

    fun ensureOnlyOnePlaying(active: Slot) {
        val other = if (active == Slot.A) Slot.B else Slot.A
        val pOther = player(other)
        pOther.playWhenReady = false
        pOther.volume = 0f
        runCatching { pOther.pause() }
    }

    fun stopAndClear(slot: Slot) {
        val p = player(slot)
        p.playWhenReady = false
        runCatching { p.pause() }
        runCatching { p.stop() }
        runCatching { p.clearMediaItems() }
        p.volume = 0f
    }

    fun recreate(slot: Slot) {
        val old = player(slot)
        runCatching { old.release() }
        val fresh = newPlayer()
        if (slot == Slot.A) playerARef.set(fresh) else playerBRef.set(fresh)
        bind(if (slot == Slot.A) layerA.playerView else layerB.playerView, slot)
    }

    suspend fun prepare(
        slot: Slot,
        item: PlayerEngine.PlaybackItem,
    ): PrepareResult {
        val p = player(slot)
        p.playWhenReady = false
        p.volume = 0f
        p.clearMediaItems()
        clearError(slot)

        val uri = Uri.fromFile(requireNotNull(item.file) { "video item without local file" })
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val startMs = item.offsetStartMs ?: 0L
        val endMs = item.offsetEndMs
        if (startMs > 0L || (endMs != null && endMs > 0L)) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs.coerceAtLeast(0L))
                .apply { if (endMs != null && endMs > 0L) setEndPositionMs(endMs.coerceAtLeast(0L)) }
                .build()
            mediaItemBuilder.setClippingConfiguration(clip)
        }

        p.setMediaItem(mediaItemBuilder.build())
        p.prepare()
        val readyAt = awaitState(p, Player.STATE_READY, Player.STATE_ENDED, Player.STATE_IDLE)
        val firstFrameAt = awaitFirstFrameOrTimeout(p, profile.firstFrameWaitMs)
        return PrepareResult(readyAtMs = readyAt, firstFrameAtMs = firstFrameAt)
    }

    fun play(slot: Slot, volume: Float) {
        val p = player(slot)
        p.volume = volume.coerceIn(0f, 1f)
        p.playWhenReady = true
        p.play()
    }

    fun pause(slot: Slot) {
        val p = player(slot)
        p.playWhenReady = false
        runCatching { p.pause() }
        p.volume = 0f
    }

    suspend fun awaitFirstFrame(slot: Slot, timeoutMs: Long): Boolean {
        val p = player(slot)
        val at = awaitFirstFrameOrTimeout(p, timeoutMs)
        return at != null
    }

    data class PrepareResult(
        val readyAtMs: Long,
        val firstFrameAtMs: Long?,
    )

    private fun bind(view: PlayerView, slot: Slot) {
        view.useController = false
        view.setShutterBackgroundColor(Color.TRANSPARENT)
        view.setKeepContentOnPlayerReset(true)
        view.player = player(slot)

        val p = player(slot)

        p.setVideoFrameMetadataListener { _, _, _, _ ->
            val now = SystemClock.elapsedRealtime()
            if (slot == Slot.A) {
                aLastFrameAt.set(now)
                aFrames.incrementAndGet()
            } else {
                bLastFrameAt.set(now)
                bFrames.incrementAndGet()
            }
        }

        p.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    if (slot == Slot.A) aDropped.addAndGet(droppedFrames.toLong()) else bDropped.addAndGet(droppedFrames.toLong())
                }
            },
        )

        p.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val msg = "${error.errorCodeName}:${error.message.orEmpty()}"
                    if (slot == Slot.A) aLastError.set(msg) else bLastError.set(msg)
                }

                override fun onRenderedFirstFrame() {
                    val now = SystemClock.elapsedRealtime()
                    if (slot == Slot.A) aLastFrameAt.set(now) else bLastFrameAt.set(now)
                }
            },
        )
    }

    private fun newPlayer(): ExoPlayer {
        val renderers =
            DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    profile.videoMinBufferMs,
                    profile.videoMaxBufferMs,
                    profile.videoBufferForPlaybackMs,
                    profile.videoBufferForPlaybackAfterRebufferMs,
                )
                .build()

        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .build()
            .also { p ->
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.setAudioAttributes(audioAttributes, false)
                p.setWakeMode(C.WAKE_MODE_LOCAL)
                p.playWhenReady = false
                p.volume = 0f
            }
    }

    private suspend fun awaitState(player: ExoPlayer, vararg terminal: Int): Long {
        val current = player.playbackState
        if (terminal.contains(current)) return SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { cont ->
            val listener =
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (terminal.contains(playbackState)) {
                            player.removeListener(this)
                            if (cont.isActive) cont.resume(SystemClock.elapsedRealtime())
                        }
                    }
                }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }
    }

    private suspend fun awaitFirstFrameOrTimeout(player: ExoPlayer, timeoutMs: Long): Long? {
        if (timeoutMs <= 0L) return null
        return withTimeoutOrNull(timeoutMs) {
            awaitFirstFrame(player)
            SystemClock.elapsedRealtime()
        }
    }

    private suspend fun awaitFirstFrame(player: ExoPlayer) {
        suspendCancellableCoroutine { cont ->
            val listener =
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }
    }
}
