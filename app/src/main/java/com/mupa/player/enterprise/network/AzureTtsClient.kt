package com.mupa.player.enterprise.network

import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Síntese de voz do Azure Speech (região brazilsouth), usada para falar a senha na TV.
 *
 * ### Por que autentica direto pela chave, e não pelo token
 *
 * O serviço aceita dois modos: `POST /sts/v1.0/issuetoken` trocando a chave por um JWT de 10
 * minutos, ou o header `Ocp-Apim-Subscription-Key` direto no endpoint de síntese. Os dois foram
 * verificados contra o serviço real em 2026-08-17 e funcionam.
 *
 * Escolhido o direto: metade das idas de rede no caminho crítico de uma chamada presencial, e sem
 * estado de expiração para manter correto. O modo por token só traria vantagem real se a chave
 * ficasse no servidor e o aparelho recebesse tokens de vida curta — que é justamente o destino
 * descrito em ARQUITETURA §8.3, e aí a troca acontece no backend, não aqui.
 *
 * Nota de campo: `issuetoken` exige `Content-Length: 0` num POST sem corpo. Sem isso o serviço
 * responde **411 Length Required** com uma página HTML, que um cliente desatento guardaria como se
 * fosse o token e depois levaria 400 na síntese.
 *
 * ### Onde a chave mora
 *
 * Hoje vem de `local.properties` para `BuildConfig`, ou seja, **está dentro do APK** e é extraível
 * por quem tiver o arquivo — e é a chave da subscrição inteira de Speech da Mupa, não só da fila.
 * Arranjo de bancada, não destino. A subscrição é de tier **F0 (gratuito)**, com cota mensal: é
 * mais uma razão para o cache em disco de [com.mupa.player.enterprise.queue.QueueAnnouncer] existir
 * desde o primeiro dia.
 */
class AzureTtsClient(
    private val subscriptionKey: String = BuildConfig.AZURE_SPEECH_KEY,
    private val region: String = BuildConfig.AZURE_SPEECH_REGION,
) {
    private val client = TlsCompat.apply(
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS),
    ).build()

    val isConfigured: Boolean get() = subscriptionKey.isNotBlank() && region.isNotBlank()

    /** Devolve o WAV (`riff-24khz-16bit-mono-pcm`) ou `null` se a síntese falhar. */
    suspend fun synthesize(ssml: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.w(TAG, "tts_nao_configurado: AZURE_SPEECH_KEY ausente na build")
            return@withContext null
        }

        val request = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            // O serviço recusa requisição sem User-Agent. O OkHttp manda o dele por padrão, mas o
            // valor é explícito aqui para o tráfego da frota ser identificável no portal do Azure.
            .header("User-Agent", "mplayer-x96/${BuildConfig.VERSION_NAME}")
            .post(ssml.toRequestBody(SSML_MEDIA_TYPE))
            .build()

        val response = runCatching { client.newCall(request).execute() }
            .getOrElse {
                Log.w(TAG, "tts_synthesize_failed", it)
                return@withContext null
            }

        response.use { res ->
            if (!res.isSuccessful) {
                // 401 = chave inválida ou revogada; 429 = cota do tier F0 estourada. Os dois
                // significam "sem áudio até alguém agir", e nenhum é recuperável por retentativa.
                Log.w(TAG, "tts_http_${res.code}")
                return@withContext null
            }
            runCatching { res.body?.bytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    companion object {
        private const val TAG = "MPlayerTts"

        /** Mesmo formato do exemplo de referência: WAV 24 kHz, 16 bits, mono. */
        const val OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"
        const val SAMPLE_RATE_HZ = 24_000

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 20L

        /**
         * `charset=utf-8` explícito: a fala carrega acento (nome do setor), e sem isso o serviço
         * interpreta os bytes como ASCII e devolve 400.
         */
        private val SSML_MEDIA_TYPE = "application/ssml+xml; charset=utf-8".toMediaType()
    }
}
