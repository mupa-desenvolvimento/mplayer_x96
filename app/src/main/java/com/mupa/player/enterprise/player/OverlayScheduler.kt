package com.mupa.player.enterprise.player

import com.mupa.player.enterprise.managers.ManifestOverlay

/**
 * Regras puras (sem Android) da Trilha 2 — separadas do [OverlayController] pra serem testáveis.
 *
 * "Ciclo" = uma volta completa da playlist da Trilha 1; `start_ms` é contado a partir do
 * instante em que o ciclo começa (item 0 entra em cena).
 */
object OverlayScheduler {

    /** Overlay cuja janela [start_ms, start_ms + duration_ms) contém [cycleMs]. O primeiro por `start_ms` vence. */
    fun activeAt(overlays: List<ManifestOverlay>, cycleMs: Long): ManifestOverlay? {
        if (cycleMs < 0L) return null
        return overlays.firstOrNull { cycleMs >= it.startMs && cycleMs < it.startMs + it.durationMs }
    }

    /** Próximo overlay que começa dentro de [leadMs] — usado pra carregar a página antes de entrar. */
    fun upcomingWithin(overlays: List<ManifestOverlay>, cycleMs: Long, leadMs: Long): ManifestOverlay? {
        if (cycleMs < 0L) return null
        return overlays.firstOrNull { it.startMs > cycleMs && it.startMs - cycleMs <= leadMs }
    }

    /**
     * O overlay (e o squeeze do vídeo) só aparece quando TUDO isto vale:
     *  - há um overlay na janela;
     *  - a página dele disse `ready` (contrato 5.3 — `empty` ou silêncio = vídeo em tela cheia);
     *  - a Trilha 1 não está num item `web`/`price_table` (regra defensiva 5.4);
     *  - o device não está inoperante.
     */
    fun shouldShow(
        active: ManifestOverlay?,
        bridgeState: String?,
        trackOneBlocksOverlay: Boolean,
        suspended: Boolean,
    ): Boolean {
        if (suspended || trackOneBlocksOverlay || active == null) return false
        return bridgeState == "ready"
    }
}
