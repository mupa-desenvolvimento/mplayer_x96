package com.mupa.player.enterprise.queue

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.mupa.player.enterprise.network.AzureTtsClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Áudio da chamada de senha: **ding-dong e depois a fala**.
 *
 * Três decisões que valem o registro:
 *
 * **O áudio nunca bloqueia o overlay.** O card já está na tela quando a fala começa a ser buscada.
 * Se a rede cair, o cliente ainda vê a senha — degrada o aviso sonoro, não a chamada.
 *
 * **Cache em disco por texto.** ARQUITETURA §8.3 recomenda pré-gerar o áudio de todas as senhas
 * possíveis por setor. Isto aqui é o primeiro degrau desse caminho: gera na primeira vez e
 * reaproveita para sempre. Como o conjunto de senhas de um setor é pequeno e se repete todo dia, na
 * prática a loja converge para latência zero em poucas horas, sem precisar da pipeline de
 * pré-geração. O que falta em relação ao ideal é a **primeira** chamada de cada número, que ainda
 * depende de rede.
 *
 * **Uma chamada por vez.** O mutex impede que dois NEXT em sequência sobreponham duas falas —
 * o que na loja soaria como ruído, não como duas senhas.
 */
class QueueAnnouncer(
    private val context: Context,
    private val tts: AzureTtsClient = AzureTtsClient(),
) {
    private val playMutex = Mutex()
    private val cacheDir: File by lazy {
        File(context.cacheDir, "queue_audio").apply { mkdirs() }
    }

    val isConfigured: Boolean get() = tts.isConfigured

    /**
     * Toca o aviso e fala a senha. Suspende até terminar — o chamador decide se espera.
     *
     * Falha em qualquer etapa é registrada e engolida: áudio é acessório da chamada, e uma exceção
     * aqui não pode derrubar o loop da fila.
     */
    suspend fun announce(number: String, sectorName: String) = playMutex.withLock {
        runCatching {
            val chime = chimeFile()
            if (chime != null) play(chime)

            val speech = speechFile(number, sectorName)
            if (speech != null) play(speech) else Log.w(TAG, "announce_sem_fala number=$number")
        }.onFailure { Log.w(TAG, "announce_failed number=$number", it) }
        Unit
    }

    // -----------------------------------------------------------------------------------------
    // Arquivos
    // -----------------------------------------------------------------------------------------

    /** O ding-dong é sintetizado uma vez e fica em cache — não muda nunca. */
    private suspend fun chimeFile(): File? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "chime.wav")
        if (file.exists() && file.length() > 0) return@withContext file
        runCatching {
            file.writeBytes(QueueChime.wavBytes(AzureTtsClient.SAMPLE_RATE_HZ))
            file
        }.onFailure { Log.w(TAG, "chime_write_failed", it) }.getOrNull()
    }

    private suspend fun speechFile(number: String, sectorName: String): File? {
        val ssml = QueueSpeech.ssmlFor(number, sectorName)
        val file = File(cacheDir, "${sha256(ssml)}.wav")
        if (withContext(Dispatchers.IO) { file.exists() && file.length() > 0 }) return file

        val audio = tts.synthesize(ssml) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // Escreve em temporário e renomeia: uma falha no meio do download deixaria um WAV
                // truncado em cache, e ele seria reusado para sempre.
                val tmp = File(cacheDir, "${file.name}.tmp")
                tmp.writeBytes(audio)
                if (tmp.renameTo(file)) file else tmp
            }.onFailure { Log.w(TAG, "speech_write_failed", it) }.getOrNull()
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // -----------------------------------------------------------------------------------------
    // Reprodução
    // -----------------------------------------------------------------------------------------

    /**
     * Toca um arquivo e suspende até o fim.
     *
     * `STREAM_MUSIC` e não `STREAM_NOTIFICATION`: numa TV Box o canal de notificação costuma estar
     * em volume irrisório ou mudo de fábrica, e a chamada de senha simplesmente não seria ouvida.
     *
     * Não pede foco de áudio porque não adiantaria: o `VideoEngine` cria o ExoPlayer com
     * `setAudioAttributes(attrs, handleAudioFocus = false)` (`VideoEngine.kt:230`), então o player
     * ignora foco e não abaixaria o volume do vídeo de qualquer forma. Consequência prática: numa
     * playlist com vídeo sonoro, a chamada disputa com o áudio da mídia. Ver ENTREGA_06_X96.md.
     */
    private suspend fun play(file: File): Unit = withContext(Dispatchers.Main) {
        val finished = CompletableDeferred<Unit>()
        var player: MediaPlayer? = null
        try {
            player = MediaPlayer().apply {
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(file.absolutePath)
                setOnCompletionListener { finished.complete(Unit) }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "media_player_error what=$what extra=$extra file=${file.name}")
                    finished.complete(Unit)
                    true
                }
                prepare()
                start()
            }
            // Teto de segurança: um MediaPlayer que nunca chama onCompletion prenderia o mutex e
            // silenciaria todas as chamadas seguintes.
            withTimeoutOrNull(PLAY_TIMEOUT_MS) { finished.await() }
        } finally {
            runCatching { player?.release() }
        }
    }

    companion object {
        private const val TAG = "MPlayerQueueAudio"
        private const val PLAY_TIMEOUT_MS = 15_000L
    }
}
