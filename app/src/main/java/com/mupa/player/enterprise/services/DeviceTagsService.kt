package com.mupa.player.enterprise.services

import android.content.Context
import android.util.Log
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import com.mupa.player.enterprise.ui.WizardTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object TagsJson {
    /**
     * Linhas de `tags` → lista para a última pergunta do cadastro. Ignora o que não tem id/nome ou
     * está inativo; ordena por categoria e depois nome (sem diferenciar maiúsculas).
     */
    fun parse(json: String): List<WizardTag> {
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<WizardTag>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                val name = o.optString("name", "").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                if (o.has("is_active") && !o.optBoolean("is_active", true)) continue
                out += WizardTag(
                    id = id,
                    name = name,
                    slug = o.optNullable("slug"),
                    category = o.optNullable("category"),
                    color = o.optNullable("color"),
                )
            }
            out.sortedWith(compareBy({ (it.category ?: "").lowercase() }, { it.name.lowercase() }))
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optNullable(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}

sealed class TagsResult {
    data class Success(val tags: List<WizardTag>) : TagsResult()
    data class Error(val message: String) : TagsResult()
}

/**
 * Tags da empresa e vínculo delas com o dispositivo recém-cadastrado.
 *
 * Tudo aqui é best-effort: o cadastro nunca pode falhar por causa das tags.
 */
class DeviceTagsService(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    private val api = SupabaseClient.createApi()

    /** Raiz do projeto Supabase (tudo antes de `/rest/v1`), derivada da URL já configurada no build. */
    private val projectBase: String = BuildConfig.SUPABASE_COMPANIES_URL.substringBefore("/rest/v1")

    /** Só as tags do tenant: a composição de playlists do servidor ignora as globais (`tenant_id` nulo). */
    suspend fun listByTenant(tenantId: String): TagsResult = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext TagsResult.Error("token")
        if (tenantId.isBlank()) return@withContext TagsResult.Success(emptyList())
        runCatching {
            val url = "$projectBase/rest/v1/tags" +
                "?select=id,name,slug,category,color,is_active" +
                "&tenant_id=eq.$tenantId&is_active=eq.true&order=category.asc,name.asc"
            TagsResult.Success(TagsJson.parse(api.getRaw(url).string()))
        }.getOrElse {
            Log.w(TAG, "list_tags_failed", it)
            TagsResult.Error(it.javaClass.simpleName)
        }
    }

    /**
     * O RPC de validação não devolve o `device_uuid`; ele só vem na resposta do manifesto
     * (`device.device_uuid`). Atenção: a primeira consulta do manifesto já faz o servidor montar a playlist.
     */
    suspend fun resolveDeviceUuid(serial: String): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank()) return@withContext null
        runCatching {
            val body = api.postJson("$projectBase/functions/v1/device-api-v2/manifest", mapOf("serial" to serial)).string()
            JSONObject(body).optJSONObject("device")?.optString("device_uuid", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        }.getOrElse {
            Log.w(TAG, "resolve_device_uuid_failed", it)
            null
        }
    }

    /** Um upsert por tag (`device_id,tag_id` é único no servidor). True só se todos passarem. */
    suspend fun link(deviceUuid: String, tagIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_TOKEN.isBlank() || deviceUuid.isBlank()) return@withContext false
        if (tagIds.isEmpty()) return@withContext true
        var allOk = true
        for (tagId in tagIds.distinct()) {
            val ok = runCatching {
                api.upsertJson(
                    url = "$projectBase/rest/v1/device_tags?on_conflict=device_id,tag_id",
                    body = mapOf("device_id" to deviceUuid, "tag_id" to tagId),
                ).close()
            }.onFailure { Log.w(TAG, "link_tag_failed tag=$tagId", it) }.isSuccess
            if (!ok) allOk = false
        }
        allOk
    }

    private companion object {
        const val TAG = "MPlayerRegister"
    }
}
