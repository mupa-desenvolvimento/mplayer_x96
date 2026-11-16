package com.mupa.player.enterprise.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import com.mupa.player.enterprise.managers.DynamicManifest
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

    /** Quem coordena a Trilha 2 (overlays). Só tocado na thread principal. */
    var listener: PlayerEngine.PlaybackListener? = null

    /** Uma WebView viva por camada, no máximo (índice 0 = A, 1 = B). */
    private val webSlides = arrayOfNulls<DynamicWebSlide>(2)

    /** Item `web` → instante (elapsedRealtime) antes do qual não vale tentar de novo. */
    private val webRetryAfter = HashMap<String, Long>()

    fun getCurrentItemId(): String? = currentItemIdRef.get()

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

                // Primeiro item exibível. Um `web` sem rede/cache, por exemplo, não é: pula pro
                // próximo em vez de travar. Se NADA for exibível, mostra a marca e tenta de novo.
                var firstIndex: Int? = null
                while (isActive && firstIndex == null) {
                    firstIndex = prepareFirstPlayableInto(activeLayer(), activeSlot(), playlist, 0)
                    if (firstIndex == null) {
                        showIdle()
                        delay(IDLE_RETRY_MS)
                        pendingPlaylist.getAndSet(null)?.let { playlist = it }
                    }
                }
                index = firstIndex ?: return@launch
                val first = playlist[index]
                startPrepared(activeSlot(), first)
                layer(activeLayerIndex).container.visibility = View.VISIBLE
                layer(activeLayerIndex).container.alpha = 1f
                listener?.onCycleStart()

                while (isActive) {
                    val currentItem = playlist[index]
                    val inactive = inactiveLayer()
                    val inactiveSlot = inactiveSlot()

                    val t0 = SystemClock.elapsedRealtime()
                    var nextIndex = prepareFirstPlayableInto(inactive, inactiveSlot, playlist, index + 1)
                    val preloadMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)

                    waitForSwitch(currentItem, activeSlot(), preloadMs)

                    val pending = pendingPlaylist.getAndSet(null)
                    if (!pending.isNullOrEmpty()) {
                        val pendingIndex = prepareFirstPlayableInto(inactive, inactiveSlot, pending, 0)
                        if (pendingIndex != null) {
                            switchTo(currentItem, pending[pendingIndex])
                            playlist = pending
                            index = pendingIndex
                            listener?.onCycleStart()
                            continue
                        }
                        // Nada da nova programação é exibível agora (ex.: só `web` e sem rede):
                        // segue com a atual e tenta a nova no próximo ciclo.
                        pendingPlaylist.compareAndSet(null, pending)
                        nextIndex = prepareFirstPlayableInto(inactive, inactiveSlot, playlist, index + 1)
                    }

                    if (nextIndex == null) {
                        // Nem o próprio item atual dá pra re-preparar: segue exibindo ele e
                        // tenta de novo no próximo ciclo, sem trocar pra uma camada vazia.
                        continue
                    }

                    switchTo(currentItem, playlist[nextIndex])
                    if (nextIndex <= index) listener?.onCycleStart()
                    index = nextIndex
                }
            }
    }

    private suspend fun switchTo(currentItem: PlayerEngine.PlaybackItem, nextItem: PlayerEngine.PlaybackItem) {
        transitionToPrepared(currentItem, activeLayer(), activeSlot(), inactiveLayer(), inactiveSlot(), nextItem)
        clearLayer(activeLayer(), activeSlot())
        toggleActiveLayer()
    }

    /**
     * Tenta preparar, a partir de [startAt] (circular), o primeiro item da lista que dá pra
     * exibir. Devolve o índice ou null se nenhum prepara.
     */
    private suspend fun prepareFirstPlayableInto(
        layer: PlayerEngine.LayerViews,
        slot: VideoEngine.Slot,
        list: List<PlayerEngine.PlaybackItem>,
        startAt: Int,
    ): Int? {
        for (step in list.indices) {
            val i = (startAt + step) % list.size
            if (prepareInto(layer, slot, list[i])) return i
        }
        return null
    }

    private fun showIdle() {
        val l = activeLayer()
        clearLayer(l, activeSlot())
        showFallback(l)
        l.container.visibility = View.VISIBLE
        l.container.alpha = 1f
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

    /**
     * Prepara [item] na camada (escondida enquanto a outra está no ar). Devolve false se o item
     * NÃO dá pra exibir agora — o chamador pula para o próximo em vez de mostrar tela quebrada.
     */
    private suspend fun prepareInto(layer: PlayerEngine.LayerViews, slot: VideoEngine.Slot, item: PlayerEngine.PlaybackItem): Boolean {
        // Estado limpo: o que a camada mostrou antes não pode vazar para o novo item.
        releaseWeb(layer)
        layer.priceTableView.clear()
        layer.priceTableView.visibility = View.GONE
        layer.webHost.visibility = View.GONE

        when {
            item.type.equals("video", ignoreCase = true) -> {
                val file = item.file ?: return false
                layer.imageView.visibility = View.GONE
                layer.playerView.visibility = View.VISIBLE
                videoEngine.stopAndClear(slot)
                videoEngine.clearError(slot)
                videoEngine.prepare(slot, item.copy(file = file))
                return true
            }
            DynamicManifest.isWebType(item.type) -> return prepareWeb(layer, slot, item)
            DynamicManifest.isPriceTableType(item.type) -> {
                val table = item.priceTable ?: return false
                layer.playerView.visibility = View.GONE
                layer.imageView.visibility = View.GONE
                videoEngine.stopAndClear(slot)
                layer.priceTableView.visibility = View.VISIBLE
                layer.priceTableView.bind(table, item.durationMs)
                item.file?.let { imageEngine.loadInto(layer.priceTableView.backgroundView, it).join() }
                return true
            }
            else -> {
                val file = item.file ?: return false
                layer.playerView.visibility = View.GONE
                layer.imageView.visibility = View.VISIBLE
                videoEngine.stopAndClear(slot)
                imageEngine.clear(layer.imageView)
                imageEngine.loadInto(layer.imageView, file).join()
                return true
            }
        }
    }

    /**
     * Carrega a página do item `web` numa WebView da camada, escondida. Só devolve true se a
     * página realmente carregou (e não avisou "empty"). Qualquer falha marca o item em
     * cooldown: ele não é tentado de novo por [WEB_RETRY_COOLDOWN_MS], pra um servidor fora do ar
     * não virar uma espera de [WEB_LOAD_TIMEOUT_MS] a cada troca de slide.
     */
    private suspend fun prepareWeb(layer: PlayerEngine.LayerViews, slot: VideoEngine.Slot, item: PlayerEngine.PlaybackItem): Boolean {
        val url = item.webUrl?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return false
        val now = SystemClock.elapsedRealtime()
        if ((webRetryAfter[item.id] ?: 0L) > now) return false

        val offline = !DynamicWebSlide.hasNetwork(context)
        // Offline e esta URL nunca carregou neste aparelho: não há cache nem localStorage,
        // então não há o que mostrar. Pula direto, sem esperar timeout.
        if (offline && !hasLoadedBefore(url)) return false

        layer.playerView.visibility = View.GONE
        layer.imageView.visibility = View.GONE
        videoEngine.stopAndClear(slot)

        // Visível com alpha 0: WebView em container INVISIBLE não pinta direito nem termina de carregar.
        layer.container.visibility = View.VISIBLE
        layer.container.alpha = 0f

        val slide = DynamicWebSlide(layer.webHost.context, transparent = false)
        layer.webHost.removeAllViews()
        layer.webHost.addView(
            slide.webView,
            android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT),
        )
        layer.webHost.visibility = View.VISIBLE
        webSlides[layerIndex(layer)] = slide

        val result = slide.load(url, WEB_LOAD_TIMEOUT_MS, offline, WEB_BRIDGE_GRACE_MS, WEB_MOUNT_TIMEOUT_MS)
        if (result == WebLoadResult.OK) {
            rememberLoaded(url)
            // Congela a página escondida (animações, timers) até ela entrar no ar.
            slide.onPause()
            return true
        }

        releaseWeb(layer)
        layer.webHost.visibility = View.GONE
        webRetryAfter[item.id] = SystemClock.elapsedRealtime() +
            (if (result == WebLoadResult.EMPTY) WEB_EMPTY_COOLDOWN_MS else WEB_RETRY_COOLDOWN_MS)
        Log.i(TAG, "web_item_skipped id=${item.id} result=$result offline=$offline")
        return false
    }

    private fun layerIndex(layer: PlayerEngine.LayerViews): Int = if (layer === layerA) 0 else 1

    private fun releaseWeb(layer: PlayerEngine.LayerViews) {
        val i = layerIndex(layer)
        webSlides[i]?.destroy()
        webSlides[i] = null
        layer.webHost.removeAllViews()
    }

    private fun webPrefs() = context.getSharedPreferences(WEB_PREFS, Context.MODE_PRIVATE)

    private fun hasLoadedBefore(url: String): Boolean =
        runCatching { webPrefs().getStringSet(KEY_LOADED_URLS, emptySet())?.contains(url) == true }.getOrDefault(false)

    private fun rememberLoaded(url: String) {
        runCatching {
            val set = webPrefs().getStringSet(KEY_LOADED_URLS, emptySet()).orEmpty()
            if (set.contains(url)) return
            // Cópia: o Set devolvido pelo SharedPreferences não pode ser modificado.
            val updated = HashSet(set).apply { add(url) }
            // Limite defensivo: URLs trocam de slide ao longo do tempo.
            val capped = if (updated.size > MAX_REMEMBERED_URLS) updated.toList().takeLast(MAX_REMEMBERED_URLS).toSet() else updated
            webPrefs().edit().putStringSet(KEY_LOADED_URLS, capped).apply()
        }
    }

    /** O item acabou de entrar no ar: acorda o que ficou congelado no pré-carregamento. */
    private fun onItemBecameVisible(layer: PlayerEngine.LayerViews, item: PlayerEngine.PlaybackItem) {
        when {
            DynamicManifest.isWebType(item.type) -> webSlides[layerIndex(layer)]?.onResume()
            DynamicManifest.isPriceTableType(item.type) -> layer.priceTableView.startPaging()
        }
        Log.i(TAG, "item_shown id=${item.id} type=${item.type}")
        listener?.onItemShown(item.type)
    }

    private fun startPrepared(slot: VideoEngine.Slot, item: PlayerEngine.PlaybackItem) {
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
        onItemBecameVisible(if (slot == VideoEngine.Slot.A) layerA else layerB, item)
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

        // Web/price_table entram no ar junto com a troca: acorda a página / inicia a paginação
        // e avisa a Trilha 2 (regra defensiva: overlay some durante esses itens).
        onItemBecameVisible(nextLayer, nextItem)

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
        // WebView é pesada no X96: não fica viva depois de sair da tela.
        releaseWeb(layer)
        layer.webHost.visibility = View.GONE
        layer.priceTableView.clear()
        layer.priceTableView.visibility = View.GONE
        videoEngine.stopAndClear(slot)
        imageEngine.clear(layer.imageView)
    }

    private fun showFallback(layer: PlayerEngine.LayerViews) {
        layer.playerView.visibility = View.GONE
        layer.imageView.visibility = View.VISIBLE
        imageEngine.showFallback(layer.imageView)
    }

    private companion object {
        const val TAG = "MPlayerPlaylist"

        /** Tempo máximo pra uma página `web` carregar antes de ser pulada. */
        const val WEB_LOAD_TIMEOUT_MS = 12_000L

        /** Depois de carregar, espera um pouco pela ponte (`ready`/`empty`) antes de seguir. */
        const val WEB_BRIDGE_GRACE_MS = 2_500L

        /** Depois de carregar, a página tem esse tempo pra realmente montar (React) antes de ser pulada. */
        const val WEB_MOUNT_TIMEOUT_MS = 8_000L

        /** Item que falhou: não tenta de novo antes disso (evita timeout a cada troca de slide). */
        const val WEB_RETRY_COOLDOWN_MS = 2 * 60_000L

        /** Página que disse "sem conteúdo" (ex.: sem aniversariantes hoje): reconfere em alguns minutos. */
        const val WEB_EMPTY_COOLDOWN_MS = 3 * 60_000L

        /** Nenhum item exibível: marca na tela e reavalia a cada tanto. */
        const val IDLE_RETRY_MS = 15_000L

        const val WEB_PREFS = "mupa_web_slides"
        const val KEY_LOADED_URLS = "loaded_urls"
        const val MAX_REMEMBERED_URLS = 64
    }
}
