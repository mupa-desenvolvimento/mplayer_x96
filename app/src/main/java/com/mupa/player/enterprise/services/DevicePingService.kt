package com.mupa.player.enterprise.services

import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.managers.DynamicManifest
import com.mupa.player.enterprise.managers.OperationalState
import com.mupa.player.enterprise.network.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `device-api-v2/ping`: health-check leve com o estado operacional (licença, bloqueio,
 * manutenção, tenant). Permite derrubar/devolver um painel em ~1 minuto sem baixar o manifesto.
 */
class DevicePingService {
    private val api = SupabaseClient.createApi()

    /** Estado atual no servidor, ou null se a consulta falhou (falha de rede NÃO muda o estado). */
    suspend fun ping(serial: String): OperationalState? = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext null
        val body = runCatching { api.postJson(PING_URL, mapOf("serial" to serial)).string() }.getOrNull()
        if (body.isNullOrBlank()) return@withContext null
        // Resposta sem o campo de estado (erro do servidor em JSON) não pode virar "operante".
        val hasState = runCatching { org.json.JSONObject(body).has("is_operante") }.getOrDefault(false)
        if (!hasState) return@withContext null
        DynamicManifest.parseOperationalState(body)
    }

    private companion object {
        const val PING_URL = "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/ping"
    }
}
