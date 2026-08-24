package com.mupa.player.enterprise.network

import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.queue.QueueAction
import com.mupa.player.enterprise.queue.QueueCallOutcome
import com.mupa.player.enterprise.queue.QueueCredentials
import com.mupa.player.enterprise.queue.QueueDeviceConfig
import com.mupa.player.enterprise.queue.QueueState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Resultado de uma chamada à `queue-api`, já separando o que o cliente precisa tratar diferente. */
sealed class QueueApiResult<out T> {
    data class Ok<T>(val value: T) : QueueApiResult<T>()

    /** Credencial ausente, inválida ou papel errado — o operador precisa reparear o aparelho. */
    object Unauthorized : QueueApiResult<Nothing>()

    /** Falha de rede ou timeout. Distinta de erro do servidor: aqui o estado local segue válido. */
    data class NetworkError(val cause: Throwable?) : QueueApiResult<Nothing>()

    data class ApiError(val code: String, val message: String, val httpStatus: Int) :
        QueueApiResult<Nothing>()
}

/**
 * Cliente da Edge Function `queue-api` (CONTRATO_API.md).
 *
 * Deliberadamente **não** reusa o [SupabaseClient]: aquele injeta `apikey`/`Authorization` da anon
 * key em toda requisição, e a autenticação daqui é outra — serial + segredo de 256 bits por
 * dispositivo (CONTRATO §1). Misturar as duas credenciais no mesmo Retrofit tornaria fácil, num
 * refactor futuro, mandar a anon key para uma rota de escrita de fila.
 *
 * O `OkHttpClient` passa por [TlsCompat] pelo mesmo motivo do resto do app: X96 antigo é Android 5
 * e não conhece a raiz ISRG Root X1.
 */
class QueueApiClient(
    private val baseUrl: String = BuildConfig.QUEUE_API_URL,
    private val credentialsProvider: suspend () -> QueueCredentials?,
) {
    private val client = TlsCompat.apply(
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS),
    ).build()

    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    suspend fun getConfig(): QueueApiResult<QueueDeviceConfig> =
        when (val raw = request("GET", "/config") { QueueDeviceConfig.fromJson(it) }) {
            is QueueApiResult.Ok ->
                raw.value?.let { QueueApiResult.Ok(it) }
                    ?: QueueApiResult.ApiError("bad_response", "Configuração sem sector_id.", 200)

            is QueueApiResult.Unauthorized -> raw
            is QueueApiResult.NetworkError -> raw
            is QueueApiResult.ApiError -> raw
        }

    /**
     * Estado da fila. `CALLER`/`DISPLAY` não informam setor: ele vem do vínculo do dispositivo
     * (CONTRATO §1) — é o que impede um player de ler a fila do balcão vizinho.
     */
    suspend fun getState(historyLimit: Int = DEFAULT_HISTORY): QueueApiResult<QueueState> =
        request("GET", "/queue?history=${historyLimit.coerceIn(0, 50)}") { obj ->
            QueueState.fromJson(obj)
        }

    /** `POST /queue/{next|back|repeat}`. Sem corpo — o setor e o dispositivo vêm da credencial. */
    suspend fun call(action: QueueAction): QueueApiResult<QueueCallOutcome> =
        request("POST", "/queue/${action.route}") { obj ->
            QueueCallOutcome.fromJson(obj)
        }

    private suspend fun <T> request(
        method: String,
        path: String,
        parse: (JSONObject) -> T,
    ): QueueApiResult<T> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext QueueApiResult.ApiError("not_configured", "QUEUE_API_URL ausente na build.", 0)
        }
        val creds = runCatching { credentialsProvider() }.getOrNull()
        if (creds == null || !creds.isValid) return@withContext QueueApiResult.Unauthorized

        // Montar a requisição dentro de runCatching não é zelo excessivo: `Request.Builder.header`
        // lança IllegalArgumentException diante de caractere de controle no valor, e como isto roda
        // dentro de uma coroutine a exceção subia até a main e derrubava o player em laço
        // (2026-08-20, credencial colada com quebra de linha no meio). Credencial ruim tem que
        // virar "não autorizado", não crash.
        val builder = runCatching {
            Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("x-device-serial", creds.serial)
                .header("x-device-secret", creds.secret)
                .header("Accept", "application/json")
                .apply { if (method == "POST") post(EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE)) }
        }.getOrElse {
            Log.w(TAG, "queue_api_credencial_invalida: $method $path", it)
            return@withContext QueueApiResult.Unauthorized
        }

        val response = runCatching { client.newCall(builder.build()).execute() }
            .getOrElse { return@withContext QueueApiResult.NetworkError(it) }

        response.use { res ->
            val body = runCatching { res.body?.string().orEmpty() }.getOrDefault("")

            if (res.code == 401 || res.code == 403) {
                Log.w(TAG, "queue_api_unauthorized ${method} $path http=${res.code}")
                return@withContext QueueApiResult.Unauthorized
            }

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext QueueApiResult.ApiError(
                    "bad_response",
                    "Resposta não-JSON da queue-api.",
                    res.code,
                )

            if (!res.isSuccessful) {
                val err = json.optJSONObject("error")
                return@withContext QueueApiResult.ApiError(
                    code = err?.optString("code").orEmpty().ifBlank { "http_${res.code}" },
                    message = err?.optString("message").orEmpty(),
                    httpStatus = res.code,
                )
            }

            val parsed = runCatching { parse(json) }
                .getOrElse {
                    Log.w(TAG, "queue_api_parse_failed $method $path", it)
                    return@withContext QueueApiResult.ApiError("bad_response", "Falha ao ler a resposta.", res.code)
                }
            QueueApiResult.Ok(parsed)
        }
    }

    companion object {
        private const val TAG = "MPlayerQueueApi"
        private const val DEFAULT_HISTORY = 5
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 15L
        private const val EMPTY_JSON = "{}"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
