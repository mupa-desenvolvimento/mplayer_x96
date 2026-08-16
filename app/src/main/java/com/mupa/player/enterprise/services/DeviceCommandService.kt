package com.mupa.player.enterprise.services

import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DeviceCommand(
    val id: String,
    val deviceId: String,
    val command: String,
    val payloadJson: String?,
)

/**
 * Canal de comandos remotos do Mupa Connect — tabela `public.device_commands`.
 *
 * Implementa a Opção B do manual de integração (polling REST), que é também o caminho
 * obrigatório de inicialização e reconexão na Opção A. O ciclo de vida seguido é o da
 * seção 4 do manual: `pending` → `ack` → `done` / `error`, com log opcional em
 * `device_execution_logs`.
 *
 * Restrições conhecidas (ver MPLAYER_COMMANDS.md §6):
 * - `device_id` é TEXT livre no Mupa Connect (serial, apelido interno ou id). Por isso a
 *   consulta é feita contra a lista de identificadores conhecidos do dispositivo.
 * - `error_message` não consta na definição da tabela no manual; o envio é tolerante a falha.
 */
class DeviceCommandService {
    private val api = SupabaseClient.createApi()

    /**
     * Busca comandos pendentes de um tipo específico para qualquer um dos identificadores
     * conhecidos do dispositivo. Filtrar por `command` no servidor evita que o X96 consuma
     * ou marque comandos destinados a outros players.
     */
    suspend fun fetchPending(identifiers: List<String>, command: String): List<DeviceCommand> =
        withContext(Dispatchers.IO) {
            if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext emptyList()

            val candidates = identifiers.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (candidates.isEmpty()) return@withContext emptyList()

            val inList = candidates.joinToString(",") { "\"${it.replace("\"", "")}\"" }
            val url = buildString {
                append(BuildConfig.SUPABASE_DEVICE_COMMANDS_URL)
                append("?select=id,device_id,command,payload")
                append("&device_id=in.(").append(encode(inList)).append(")")
                append("&status=eq.pending")
                append("&command=eq.").append(encode(command))
                append("&order=created_at.asc")
                append("&limit=").append(MAX_COMMANDS_PER_POLL)
            }

            val body = runCatching { api.getRaw(url).string() }
                .onFailure { Log.w(TAG, "fetch_pending_failed", it) }
                .getOrNull()
                ?: return@withContext emptyList()

            parseCommands(body)
        }

    /** Passo 1 do manual: confirma o recebimento assim que o comando chega. */
    suspend fun acknowledge(commandId: String): Boolean =
        patchCommand(
            commandId = commandId,
            body = mapOf(
                "status" to STATUS_ACK,
                "acknowledged_at" to nowIso8601(),
            ),
        )

    /**
     * Passo 3 do manual: encerra o comando.
     *
     * `error_message` não está documentado na estrutura da tabela; se a coluna não existir o
     * PostgREST recusa o PATCH inteiro. Nesse caso repetimos sem o campo, para que o status
     * final chegue ao painel de qualquer forma.
     */
    suspend fun complete(commandId: String, ok: Boolean, errorMessage: String? = null): Boolean {
        val base = mapOf<String, Any?>(
            "status" to if (ok) STATUS_DONE else STATUS_ERROR,
            "executed_at" to nowIso8601(),
        )
        if (ok || errorMessage.isNullOrBlank()) {
            return patchCommand(commandId, base)
        }
        val withReason = base + ("error_message" to errorMessage)
        if (patchCommand(commandId, withReason, quiet = true)) return true

        Log.w(TAG, "complete_with_error_message_failed commandId=$commandId; retrying without it")
        return patchCommand(commandId, base)
    }

    /** Passo 4 do manual (opcional). Falha aqui nunca invalida a execução do comando. */
    suspend fun logExecution(
        deviceId: String,
        commandId: String,
        command: String,
        ok: Boolean,
        durationMs: Long,
        detail: String? = null,
    ) = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext

        val payload = JSONObject()
            .put("action", "${command}_completed")
            .apply { if (!detail.isNullOrBlank()) put("detail", detail) }
            .toString()

        runCatching {
            api.insertJson(
                url = BuildConfig.SUPABASE_DEVICE_EXECUTION_LOGS_URL,
                body = mapOf(
                    "device_id" to deviceId,
                    "command_id" to commandId,
                    "command" to command,
                    "result" to if (ok) "success" else "error",
                    "duration_ms" to durationMs,
                    "payload" to payload,
                ),
            ).close()
        }.onFailure { Log.w(TAG, "log_execution_failed commandId=$commandId", it) }
        Unit
    }

    private suspend fun patchCommand(
        commandId: String,
        body: Map<String, Any?>,
        quiet: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext false
        val url = "${BuildConfig.SUPABASE_DEVICE_COMMANDS_URL}?id=eq.${encode(commandId)}"
        runCatching { api.patchJson(url = url, body = body).close() }
            .onFailure { if (!quiet) Log.w(TAG, "patch_command_failed commandId=$commandId", it) }
            .isSuccess
    }

    private fun parseCommands(body: String): List<DeviceCommand> {
        return runCatching {
            val arr = JSONArray(body)
            val out = ArrayList<DeviceCommand>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val command = obj.optString("command", "").trim()
                if (id.isBlank() || command.isBlank()) continue
                out += DeviceCommand(
                    id = id,
                    deviceId = obj.optString("device_id", "").trim(),
                    command = command,
                    payloadJson = obj.optJSONObject("payload")?.toString(),
                )
            }
            out
        }.onFailure { Log.w(TAG, "parse_commands_failed", it) }.getOrDefault(emptyList())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun nowIso8601(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    companion object {
        private const val TAG = "MPlayerCommands"
        private const val MAX_COMMANDS_PER_POLL = 20

        const val COMMAND_RELOAD_PLAYLIST = "reload_playlist"

        private const val STATUS_ACK = "ack"
        private const val STATUS_DONE = "done"
        private const val STATUS_ERROR = "error"
    }
}
