package com.mupa.player.enterprise.queue

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Gera o "ding-dong" que antecede a chamada da senha.
 *
 * Sintetizado em código em vez de empacotado como asset por duas razões práticas: o timbre fica
 * ajustável sem passar por edição de áudio, e não entra binário no repositório. O formato é o mesmo
 * que o Azure devolve — WAV PCM 24 kHz, 16 bits, mono — para que aviso e fala usem exatamente o
 * mesmo caminho de reprodução.
 *
 * O som é dois toques descendentes (E5 → C5) com decaimento exponencial e dois harmônicos, que é o
 * que dá o caráter de sino em vez de bipe. O segundo toque começa antes do primeiro terminar: é a
 * sobreposição que faz soar como campainha e não como duas notas separadas.
 */
object QueueChime {

    fun wavBytes(sampleRate: Int = 24_000): ByteArray {
        val strikes = listOf(
            Strike(frequencyHz = 659.25, startMs = 0, durationMs = 750),    // E5 — "ding"
            Strike(frequencyHz = 523.25, startMs = 380, durationMs = 950),  // C5 — "dong"
        )

        val totalMs = strikes.maxOf { it.startMs + it.durationMs }
        val totalSamples = (sampleRate.toLong() * totalMs / 1000L).toInt()
        val mix = DoubleArray(totalSamples)

        for (strike in strikes) {
            val startSample = (sampleRate.toLong() * strike.startMs / 1000L).toInt()
            val lengthSamples = (sampleRate.toLong() * strike.durationMs / 1000L).toInt()
            for (i in 0 until lengthSamples) {
                val index = startSample + i
                if (index >= totalSamples) break
                val t = i.toDouble() / sampleRate
                val envelope = exp(-DECAY_PER_SECOND * t)
                val w = 2.0 * PI * strike.frequencyHz * t
                // Fundamental + 2º e 3º parciais. As amplitudes decrescentes são o que separa um
                // sino de uma onda senoidal pura.
                val voice = sin(w) + PARTIAL_2 * sin(2 * w) + PARTIAL_3 * sin(3 * w)
                mix[index] += envelope * voice
            }
        }

        // Normaliza pelo pico real: a soma de parciais e a sobreposição dos toques passariam de
        // 1.0 e ceifariam a onda, o que soa como estalo no alto-falante da TV.
        val peak = mix.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0
        val gain = if (peak > 0) HEADROOM / peak else 0.0

        val pcm = ByteArrayOutputStream(totalSamples * 2)
        for (sample in mix) {
            val value = (sample * gain * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            pcm.write(value and 0xFF)
            pcm.write((value shr 8) and 0xFF)
        }
        return wrapInWavHeader(pcm.toByteArray(), sampleRate)
    }

    private data class Strike(val frequencyHz: Double, val startMs: Int, val durationMs: Int)

    /** Cabeçalho RIFF/WAVE de 44 bytes, PCM 16 bits mono, little-endian. */
    private fun wrapInWavHeader(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val out = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun int32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun int16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        ascii("RIFF"); int32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); int32(16); int16(1); int16(channels)
        int32(sampleRate); int32(byteRate); int16(blockAlign); int16(bitsPerSample)
        ascii("data"); int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    private const val DECAY_PER_SECOND = 4.2
    private const val PARTIAL_2 = 0.42
    private const val PARTIAL_3 = 0.16

    /** Margem abaixo do fundo de escala, para o clipping não acontecer na TV. */
    private const val HEADROOM = 0.85
}
