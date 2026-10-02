package com.mupa.player.enterprise.services

import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Payload de saúde do device — o que qualquer painel externo (Argos/Mupa Brain) precisa pra
 * decidir "esse device está mudo, dispara alerta". Uma linha por `deviceId` (upsert), nunca um
 * log crescente: o que importa pra alta disponibilidade é o estado mais recente.
 */
data class HeartbeatPayload(
    val deviceId: String,
    val appVersion: String,
    val status: String,
    val currentItemId: String?,
    val currentItemType: String?,
    val isPlaying: Boolean?,
    val lastPlaybackError: String?,
    val lastCrashAtEpochMs: Long?,
    val lastCrashMessage: String?,
    val lastSyncOk: Boolean?,
    val lastSyncAtEpochMs: Long?,
    val storageFreeMb: Long?,
    val cpuPercent: Float?,
    val usedRamMb: Int?,
    val totalRamMb: Int?,
    val temperatureC: Float?,
)

/**
 * Escreve em `public.device_heartbeat` (tabela nova — ver README para o DDL). Upsert por
 * `device_id`: sempre uma linha por device, sobrescrita a cada ciclo.
 */
class HeartbeatService {
    private val api = SupabaseClient.createApi()

    suspend fun send(payload: HeartbeatPayload): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext false
        if (payload.deviceId.isBlank()) return@withContext false

        val url = "${BuildConfig.SUPABASE_DEVICE_HEARTBEAT_URL}?on_conflict=device_id"
        val nowIso = nowIso8601()
        runCatching {
            api.upsertJson(
                url = url,
                body = mapOf(
                    "device_id" to payload.deviceId,
                    "app_version" to payload.appVersion,
                    "status" to payload.status,
                    "current_item_id" to payload.currentItemId,
                    "current_item_type" to payload.currentItemType,
                    "is_playing" to payload.isPlaying,
                    "last_playback_error" to payload.lastPlaybackError,
                    "last_crash_at" to payload.lastCrashAtEpochMs?.let { isoFromEpochMs(it) },
                    "last_crash_message" to payload.lastCrashMessage,
                    "last_sync_ok" to payload.lastSyncOk,
                    "last_sync_at" to payload.lastSyncAtEpochMs?.let { isoFromEpochMs(it) },
                    "storage_free_mb" to payload.storageFreeMb,
                    "cpu_percent" to payload.cpuPercent,
                    "used_ram_mb" to payload.usedRamMb,
                    "total_ram_mb" to payload.totalRamMb,
                    "temperature_c" to payload.temperatureC,
                    "last_heartbeat_at" to nowIso,
                    "updated_at" to nowIso,
                ),
            ).close()
        }.onFailure { Log.w(TAG, "send_heartbeat_failed deviceId=${payload.deviceId}", it) }.isSuccess
    }

    private fun isoFromEpochMs(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    private fun nowIso8601(): String = isoFromEpochMs(System.currentTimeMillis())

    companion object {
        private const val TAG = "MPlayerHeartbeat"
    }
}
