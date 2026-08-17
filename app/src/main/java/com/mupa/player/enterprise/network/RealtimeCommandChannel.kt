package com.mupa.player.enterprise.network

import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canal de push em tempo real do Supabase Realtime (Opção A do manual de integração).
 *
 * Implementa o protocolo Phoenix direto sobre `OkHttp WebSocket` em vez de usar `supabase-kt`,
 * porque o flavor `legacy` tem `minSdk 21` e a biblioteca oficial exige 24+ e Ktor. O cliente vem
 * de [TlsCompat], que injeta a raiz ISRG Root X1 em Android < 7 — sem isso o TLS falha nos X96
 * antigos.
 *
 * O canal **não** executa o comando a partir do payload recebido: qualquer INSERT relevante
 * apenas dispara uma varredura de pendências via REST. Isso mantém um único caminho de execução
 * (ack → aplica → done) e recupera automaticamente comandos perdidos enquanto o socket esteve
 * fora do ar.
 *
 * Pré-requisitos no backend, sem os quais o socket conecta mas nunca entrega nada:
 * - a tabela observada precisa estar na publication `supabase_realtime`;
 * - a policy de RLS precisa permitir `SELECT` para o role usado pela anon key.
 *
 * Usado hoje em duas tabelas: `device_commands` (comandos do Mupa Connect) e `queue_events`
 * (chamadas de senha do MUPA Queue). Cada tabela tem sua própria trava de conexão única.
 */
class RealtimeCommandChannel(
    private val table: String = "device_commands",
    private val onCommandInserted: () -> Unit,
) {
    private val client = TlsCompat.apply(
        okhttp3.OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS),
    ).build()

    private val refCounter = AtomicInteger(0)
    private val topic = "realtime:public:$table"

    /** Reconecta indefinidamente com backoff exponencial. Retorna só se o escopo for cancelado. */
    suspend fun run() {
        val apiKey = BuildConfig.SUPABASE_TOKEN.trim()
        if (apiKey.isBlank() || BuildConfig.SUPABASE_REALTIME_URL.isBlank()) {
            Log.w(TAG, "realtime_disabled: token ou url ausente")
            return
        }

        // Trava de conexão única no processo, POR TABELA. Não retorna cedo quando já há uma ativa:
        // espera a vez. Retornar deixaria o canal sem ninguém caso a instância vigente fosse
        // cancelada logo em seguida — o cenário oposto, e pior, do que o da duplicação.
        val connectionSlot = slotFor(table)
        var waitedForSlot = false
        while (!connectionSlot.compareAndSet(false, true)) {
            if (!waitedForSlot) {
                Log.i(TAG, "realtime_slot_busy: já existe uma conexão ativa, aguardando a vez")
                waitedForSlot = true
            }
            delay(SLOT_POLL_MS)
        }

        try {
            var backoffMs = BACKOFF_BASE_MS
            while (currentCoroutineContext().isActive) {
                val deliveredAny =
                    try {
                        connectAndAwaitClose(apiKey)
                    } catch (e: CancellationException) {
                        // Escopo cancelado (activity parou): encerra o loop em vez de tratar como
                        // falha de rede e reconectar.
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "realtime_connection_failed", e)
                        false
                    }

                if (deliveredAny) backoffMs = BACKOFF_BASE_MS
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        } finally {
            connectionSlot.set(false)
        }
    }

    /** Abre o socket e suspende até ele fechar. `true` se o canal chegou a ser aceito (`phx_reply` ok). */
    private suspend fun connectAndAwaitClose(apiKey: String): Boolean {
        val closed = CompletableDeferred<Boolean>()
        var joined = false

        val url = buildString {
            append(BuildConfig.SUPABASE_REALTIME_URL.trimEnd('/'))
            append("?apikey=").append(apiKey)
            append("&vsn=1.0.0")
        }
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "realtime_open topic=$topic")
                webSocket.send(joinMessage())
                webSocket.send(accessTokenMessage(apiKey))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("event")) {
                    "phx_reply" -> {
                        val status = msg.optJSONObject("payload")?.optString("status")
                        if (status == "ok" && msg.optString("topic") == topic) {
                            joined = true
                            Log.i(TAG, "realtime_joined topic=$topic")
                        } else if (status == "error") {
                            Log.w(TAG, "realtime_join_error: ${msg.optJSONObject("payload")}")
                        }
                    }

                    "postgres_changes" -> {
                        if (isRelevantInsert(msg)) {
                            Log.i(TAG, "realtime_insert recebido — disparando varredura de pendências")
                            runCatching { onCommandInserted() }
                                .onFailure { Log.w(TAG, "on_command_inserted_failed", it) }
                        }
                    }

                    "phx_close", "phx_error" -> {
                        Log.w(TAG, "realtime_channel_closed event=${msg.optString("event")}")
                        closed.complete(joined)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "realtime_failure code=${response?.code}", t)
                closed.complete(joined)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "realtime_closed code=$code reason=$reason")
                closed.complete(joined)
            }
        }

        val webSocket = client.newWebSocket(request, listener)

        // Heartbeat de aplicação: o Phoenix derruba o socket em ~60s sem isso, mesmo com o ping
        // de protocolo do OkHttp.
        //
        // Roda num Job PRÓPRIO, desacoplado do chamador de propósito. Na versão anterior ele era
        // filho do `coroutineScope` desta função e era encerrado com `cancel()` no `finally` — o
        // cancelamento do filho escapava como CancellationException do escopo inteiro, derrubava o
        // socket e disparava uma reconexão. O sintoma era o canal caindo e reconectando a cada
        // ~60s, indefinidamente, em toda a frota.
        val heartbeatScope = CoroutineScope(currentCoroutineContext() + Job())
        heartbeatScope.launch {
            var beat = 0
            while (isActive) {
                delay(HEARTBEAT_MS)
                val sent = webSocket.send(heartbeatMessage())
                if (BuildConfig.DEBUG) Log.d(TAG, "heartbeat #${++beat} sent=$sent")
                if (!sent) {
                    Log.w(TAG, "heartbeat_send_failed — fila do socket cheia ou já fechado")
                    break
                }
            }
        }

        try {
            return closed.await()
        } finally {
            heartbeatScope.cancel()
            runCatching { webSocket.close(1000, "bye") }
            runCatching { webSocket.cancel() }
        }
    }

    /** O filtro por device é feito na varredura REST; aqui só confirmamos que é INSERT na tabela. */
    private fun isRelevantInsert(msg: JSONObject): Boolean {
        val data = msg.optJSONObject("payload")?.optJSONObject("data") ?: return false
        if (!data.optString("type").equals("INSERT", ignoreCase = true)) return false
        val t = data.optString("table")
        return t.isBlank() || t == table
    }

    private fun joinMessage(): String {
        val change = JSONObject()
            .put("event", "INSERT")
            .put("schema", "public")
            .put("table", table)

        val config = JSONObject()
            .put("postgres_changes", JSONArray().put(change))

        return JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("payload", JSONObject().put("config", config))
            .put("ref", refCounter.incrementAndGet().toString())
            .toString()
    }

    private fun accessTokenMessage(apiKey: String): String =
        JSONObject()
            .put("topic", topic)
            .put("event", "access_token")
            .put("payload", JSONObject().put("access_token", apiKey))
            .put("ref", refCounter.incrementAndGet().toString())
            .toString()

    private fun heartbeatMessage(): String =
        JSONObject()
            .put("topic", "phoenix")
            .put("event", "heartbeat")
            .put("payload", JSONObject())
            .put("ref", refCounter.incrementAndGet().toString())
            .toString()

    companion object {
        private const val TAG = "MPlayerRealtime"

        /**
         * Garante uma única conexão por processo **para cada tabela**, mesmo que duas instâncias da
         * Activity coexistam — a guarda por Job em PlayerActivity é por instância e não cobriria
         * esse caso.
         *
         * A trava é por tabela porque o player mantém dois canais simultâneos e independentes:
         * `device_commands` (comandos do Mupa Connect) e `queue_events` (chamadas de senha do MUPA
         * Queue). Uma trava única no processo faria o segundo canal esperar para sempre.
         */
        private val connectionSlots =
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

        private fun slotFor(table: String): java.util.concurrent.atomic.AtomicBoolean =
            connectionSlots.getOrPut(table) { java.util.concurrent.atomic.AtomicBoolean(false) }
        private const val SLOT_POLL_MS = 1_000L
        private const val HEARTBEAT_MS = 25_000L
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_MAX_MS = 60_000L
    }
}
