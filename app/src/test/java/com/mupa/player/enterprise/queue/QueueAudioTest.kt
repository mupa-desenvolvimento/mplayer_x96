package com.mupa.player.enterprise.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes das partes puras do áudio da chamada: o texto falado e o WAV do ding-dong.
 *
 * Nenhum depende de rede nem de Android — o que sai daqui é exatamente o que vai para o serviço do
 * Azure e para o MediaPlayer.
 */
class QueueAudioTest {

    // ---------------------------------------------------------------------------------------
    // SSML
    // ---------------------------------------------------------------------------------------

    @Test
    fun `numero e falado digito a digito`() {
        val ssml = QueueSpeech.ssmlFor("A125", "Farmácia")
        assertTrue("esperava dígitos separados, veio: $ssml", ssml.contains("A, 1, 2, 5."))
    }

    @Test
    fun `setor entra na fala depois do numero`() {
        val ssml = QueueSpeech.ssmlFor("A125", "Atendimento Farmacêutico")
        assertTrue(ssml.contains("Senha. A, 1, 2, 5. Atendimento Farmacêutico."))
    }

    @Test
    fun `setor vazio nao deixa ponto solto na fala`() {
        val ssml = QueueSpeech.ssmlFor("B7", "   ")
        assertTrue(ssml.contains(">Senha. B, 7.<"))
    }

    @Test
    fun `caractere especial do nome do setor e escapado`() {
        // Sem escape, um "&" no nome da loja quebraria o XML e a chamada sairia muda.
        val ssml = QueueSpeech.ssmlFor("A1", "Casa & Cia")
        assertTrue(ssml.contains("Casa &amp; Cia"))
        assertTrue("o & cru não pode sobrar", !ssml.contains("Casa & Cia"))
    }

    @Test
    fun `separadores do numero nao viram fala`() {
        // "A-125" e "A 125" precisam soar igual a "A125".
        assertEquals(QueueSpeech.ssmlFor("A125", "X"), QueueSpeech.ssmlFor("A-125", "X"))
        assertEquals(QueueSpeech.ssmlFor("A125", "X"), QueueSpeech.ssmlFor("a 125", "X"))
    }

    @Test
    fun `ssml declara voz e idioma`() {
        val ssml = QueueSpeech.ssmlFor("A1", "Padaria")
        assertTrue(ssml.startsWith("""<speak version="1.0" xml:lang="pt-BR">"""))
        assertTrue(ssml.contains("""<voice name="pt-BR-FranciscaNeural">"""))
        assertTrue(ssml.endsWith("</voice></speak>"))
    }

    // ---------------------------------------------------------------------------------------
    // Ding-dong
    // ---------------------------------------------------------------------------------------

    @Test
    fun `chime tem cabecalho RIFF WAVE valido`() {
        val wav = QueueChime.wavBytes(24_000)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        // O campo de tamanho do RIFF precisa bater com o arquivo, senão players rigorosos recusam.
        val riffSize = readInt32(wav, 4)
        assertEquals(wav.size - 8, riffSize)

        val dataSize = readInt32(wav, 40)
        assertEquals(wav.size - 44, dataSize)

        assertEquals(1, readInt16(wav, 20))          // PCM
        assertEquals(1, readInt16(wav, 22))          // mono
        assertEquals(24_000, readInt32(wav, 24))     // taxa de amostragem
        assertEquals(16, readInt16(wav, 34))         // bits por amostra
    }

    @Test
    fun `chime dura cerca de um segundo e meio`() {
        val wav = QueueChime.wavBytes(24_000)
        val samples = (wav.size - 44) / 2
        val durationMs = samples * 1000L / 24_000
        // Longo o bastante para chamar atenção, curto o bastante para não atrasar a fala.
        assertTrue("duração fora do esperado: ${durationMs}ms", durationMs in 1_200..1_500)
    }

    @Test
    fun `chime nao ceifa a onda`() {
        val wav = QueueChime.wavBytes(24_000)
        var peak = 0
        var index = 44
        while (index + 1 < wav.size) {
            val sample = readInt16Signed(wav, index)
            if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
            index += 2
        }
        // Clipping soa como estalo no alto-falante da TV. O gerador normaliza com folga.
        assertTrue("pico alto demais: $peak", peak < 32_000)
        assertTrue("pico baixo demais, o aviso ficaria inaudível: $peak", peak > 20_000)
    }

    private fun readInt32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun readInt16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun readInt16Signed(b: ByteArray, at: Int): Int =
        readInt16(b, at).toShort().toInt()
}
