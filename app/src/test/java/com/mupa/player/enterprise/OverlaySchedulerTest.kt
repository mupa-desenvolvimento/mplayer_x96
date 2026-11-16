package com.mupa.player.enterprise

import com.mupa.player.enterprise.managers.ManifestOverlay
import com.mupa.player.enterprise.player.OverlayScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySchedulerTest {

    private fun ov(id: String, start: Long, dur: Long) =
        ManifestOverlay(id = id, name = null, url = "https://x/$id", startMs = start, durationMs = dur, viewport = null)

    private val a = ov("a", 0, 30_000)
    private val b = ov("b", 40_000, 10_000)
    private val all = listOf(a, b)

    @Test
    fun activeAt_isHalfOpenInterval() {
        assertEquals("a", OverlayScheduler.activeAt(all, 0)?.id)
        assertEquals("a", OverlayScheduler.activeAt(all, 29_999)?.id)
        assertNull("fim da janela é exclusivo", OverlayScheduler.activeAt(all, 30_000))
        assertNull(OverlayScheduler.activeAt(all, 39_999))
        assertEquals("b", OverlayScheduler.activeAt(all, 40_000)?.id)
        assertNull(OverlayScheduler.activeAt(all, 50_000))
    }

    @Test
    fun activeAt_beforeAnyCycleStart_isNull() {
        assertNull(OverlayScheduler.activeAt(all, -1))
    }

    @Test
    fun activeAt_overlappingWindows_firstByStartWins() {
        val x = ov("x", 0, 20_000)
        val y = ov("y", 10_000, 20_000)
        assertEquals("x", OverlayScheduler.activeAt(listOf(x, y), 15_000)?.id)
        assertEquals("y", OverlayScheduler.activeAt(listOf(x, y), 25_000)?.id)
    }

    @Test
    fun upcomingWithin_findsOnlyNearFutureOnes() {
        assertEquals("b", OverlayScheduler.upcomingWithin(all, 35_000, 10_000)?.id)
        assertNull(OverlayScheduler.upcomingWithin(all, 20_000, 10_000))
        assertNull("já começou: não é 'próximo'", OverlayScheduler.upcomingWithin(all, 45_000, 10_000))
    }

    @Test
    fun shouldShow_requiresReadyAndNoBlockers() {
        assertTrue(OverlayScheduler.shouldShow(a, "ready", trackOneBlocksOverlay = false, suspended = false))
        // Contrato 5.3: empty / sem resposta => nunca encolher o vídeo.
        assertFalse(OverlayScheduler.shouldShow(a, "empty", false, false))
        assertFalse(OverlayScheduler.shouldShow(a, null, false, false))
        // Regra defensiva 5.4.
        assertFalse(OverlayScheduler.shouldShow(a, "ready", trackOneBlocksOverlay = true, suspended = false))
        // Device inoperante.
        assertFalse(OverlayScheduler.shouldShow(a, "ready", false, suspended = true))
        // Fora da janela.
        assertFalse(OverlayScheduler.shouldShow(null, "ready", false, false))
    }
}
