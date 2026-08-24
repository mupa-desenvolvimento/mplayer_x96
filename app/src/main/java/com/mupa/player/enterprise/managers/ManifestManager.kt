package com.mupa.player.enterprise.managers

import android.content.Context
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.network.SupabaseClient
import com.mupa.player.enterprise.network.TlsCompat
import com.mupa.player.enterprise.storage.db.AppDatabase
import com.mupa.player.enterprise.storage.db.ManifestEntity
import com.mupa.player.enterprise.storage.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ManifestItem(
    val id: String,
    val type: String,
    val url: String,
    val name: String?,
    val durationMs: Long?,
    val volume: Float?,
    val offsetStartMs: Long?,
    val offsetEndMs: Long?,
    val startDate: String?,
    val endDate: String?,
    val startTime: String?,
    val endTime: String?,
)

data class MediaSyncProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentName: String?,
    val currentBytesDownloaded: Long,
    val currentBytesTotal: Long?,
    val currentSpeedBytesPerSec: Long,
)

private const val WEB_TYPE = "web"

class ManifestManager(private val context: Context) {
    private val api = SupabaseClient.createApi()
    private val db = AppDatabase.get(context)
    private val okHttp = TlsCompat.newClient()

    suspend fun fetchManifest(deviceId: String): String = withContext(Dispatchers.IO) {
        val token = BuildConfig.SUPABASE_TOKEN.trim()
        if (token.isBlank()) return@withContext ""

        val url = "https://iurqddkuihjsmxubibao.supabase.co/functions/v1/device-api-v2/manifest"
        api.postJson(url = url, body = mapOf("serial" to deviceId)).string()
    }

    suspend fun saveManifest(deviceId: String, json: String) = withContext(Dispatchers.IO) {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return@withContext
        val now = System.currentTimeMillis()

        db.manifestDao().upsert(
            ManifestEntity(
                deviceId = deviceId,
                json = trimmed,
                updatedAtEpochMs = now,
            ),
        )

        val file = File(getMediaDir(), "manifest.json")
        file.parentFile?.mkdirs()
        file.writeText(trimmed)
    }

    suspend fun compareManifest(deviceId: String, remoteJson: String): Boolean = withContext(Dispatchers.IO) {
        val local = db.manifestDao().getByDeviceId(deviceId)?.json?.trim().orEmpty()
        if (local.isBlank()) return@withContext false
        return@withContext local == remoteJson.trim()
    }

    suspend fun loadOfflineManifest(deviceId: String): String? = withContext(Dispatchers.IO) {
        val file = File(getMediaDir(), "manifest.json")
        if (file.exists()) return@withContext file.readText().takeIf { it.isNotBlank() }
        return@withContext db.manifestDao().getByDeviceId(deviceId)?.json?.takeIf { it.isNotBlank() }
    }

    suspend fun syncMedia(
        deviceId: String,
        manifestJson: String,
        onProgress: ((MediaSyncProgress) -> Unit)? = null,
        maxConcurrentDownloads: Int = 2,
    ): List<MediaEntity> = withContext(Dispatchers.IO) {
        // Itens `web` nao tem arquivo para baixar: sao pagina viva, renderizada na WebView, e o
        // contrato de 2026-08-21 e explicito em "nao tente pre-carregar". Filtrar aqui e nao mais
        // abaixo tambem conserta a contagem de progresso, que senao esperaria por um download que
        // nunca acontece.
        val items = parseItems(manifestJson).filterNot { it.type.equals(WEB_TYPE, ignoreCase = true) }
        val mediaDir = getMediaDir()
        mediaDir.mkdirs()

        val existing = items.count { item ->
            val existingFile = mediaDir.listFiles()?.firstOrNull {
                it.name.substringBeforeLast('.', missingDelimiterValue = "") == item.id && it.length() > 0L
            }
            existingFile != null
        }

        val completed = AtomicInteger(existing)
        val total = items.size
        val result = ArrayList<MediaEntity>(items.size)

        val semaphore = Semaphore(maxConcurrentDownloads.coerceIn(1, 4))
        val currentBytes = AtomicLong(0L)
        val currentTotalBytes = AtomicLong(-1L)
        val lastSpeedBytes = AtomicLong(0L)
        val lastSpeedAt = AtomicLong(System.currentTimeMillis())

        fun emitProgress(currentName: String?) {
            val now = System.currentTimeMillis()
            val bytesNow = currentBytes.get()
            val totalNow = currentTotalBytes.get().takeIf { it > 0 }
            val lastAt = lastSpeedAt.get()
            val lastBytes = lastSpeedBytes.get()
            val dtMs = (now - lastAt).coerceAtLeast(1L)
            val speed = ((bytesNow - lastBytes) * 1000L) / dtMs
            lastSpeedAt.set(now)
            lastSpeedBytes.set(bytesNow)

            onProgress?.invoke(
                MediaSyncProgress(
                    completedItems = completed.get().coerceAtMost(total),
                    totalItems = total,
                    currentName = currentName,
                    currentBytesDownloaded = bytesNow,
                    currentBytesTotal = totalNow,
                    currentSpeedBytesPerSec = speed.coerceAtLeast(0L),
                ),
            )
        }

        coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        val existingFile = mediaDir.listFiles()?.firstOrNull {
                            it.name.substringBeforeLast('.', missingDelimiterValue = "") == item.id && it.length() > 0L
                        }
                        val ext = resolveExtension(item)
                        val fileName = item.id + ext
                        val target = existingFile ?: File(mediaDir, fileName)

                        if (existingFile == null) {
                            currentBytes.set(0L)
                            currentTotalBytes.set(-1L)
                            lastSpeedAt.set(System.currentTimeMillis())
                            lastSpeedBytes.set(0L)
                            emitProgress(item.name)

                            val ok =
                                runCatching {
                                    downloadToFile(
                                        url = item.url,
                                        target = target,
                                        onChunk = { bytesDownloaded, bytesTotal ->
                                            currentBytes.set(bytesDownloaded)
                                            currentTotalBytes.set(bytesTotal ?: -1L)
                                            if ((bytesDownloaded and 0x3FFFFL) == 0L) {
                                                emitProgress(item.name)
                                            }
                                        },
                                    )
                                }.isSuccess
                            if (!ok || !target.exists() || target.length() == 0L) {
                                val done = completed.incrementAndGet()
                                onProgress?.invoke(
                                    MediaSyncProgress(
                                        completedItems = done.coerceAtMost(total),
                                        totalItems = total,
                                        currentName = item.name,
                                        currentBytesDownloaded = 0L,
                                        currentBytesTotal = null,
                                        currentSpeedBytesPerSec = 0L,
                                    ),
                                )
                                return@withPermit
                            }
                        }

                        val entity = MediaEntity(
                            mediaId = item.id,
                            type = item.type,
                            remoteUrl = item.url,
                            localPath = target.absolutePath,
                            fileSizeBytes = target.length(),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        db.mediaDao().upsert(entity)
                        synchronized(result) { result += entity }

                        val done = completed.incrementAndGet()
                        onProgress?.invoke(
                            MediaSyncProgress(
                                completedItems = done.coerceAtMost(total),
                                totalItems = total,
                                currentName = item.name,
                                currentBytesDownloaded = 0L,
                                currentBytesTotal = null,
                                currentSpeedBytesPerSec = 0L,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }

        cleanup(deviceId, items.map { it.id }.toSet(), mediaDir)
        return@withContext result
    }

    suspend fun cleanup(deviceId: String) = withContext(Dispatchers.IO) {
        val mediaDir = getMediaDir()
        val keep = db.mediaDao().getAll().map { it.mediaId }.toSet()
        cleanup(deviceId, keep, mediaDir)
    }

    private suspend fun cleanup(@Suppress("UNUSED_PARAMETER") deviceId: String, keepMediaIds: Set<String>, mediaDir: File) {
        val files = mediaDir.listFiles().orEmpty()
        for (f in files) {
            if (f.name == "manifest.json") continue
            val id = f.name.substringBeforeLast('.', missingDelimiterValue = f.name)
            if (!keepMediaIds.contains(id)) {
                runCatching { f.delete() }
                db.mediaDao().getById(id)?.let { db.mediaDao().deleteById(id) }
            }
        }
    }

    private fun getMediaDir(): File {
        return File(context.getExternalFilesDir(null), "media")
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        val s = optString(key, "").trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        return s
    }

    private fun parseItems(json: String): List<ManifestItem> {
        return try {
            val root = JSONObject(json)
            val manifestObj = root.optJSONObject("manifest") ?: root
            val playlistObj = manifestObj.optJSONObject("playlist") ?: manifestObj
            val items = playlistObj.optJSONArray("items") ?: manifestObj.optJSONArray("items") ?: JSONArray()
            val appearance = manifestObj.optJSONObject("appearance_config")
                ?: playlistObj.optJSONObject("appearance_config")
                ?: JSONObject()
            val volumesArr = appearance.optJSONArray("item_volumes")
            val offsetsArr = appearance.optJSONArray("item_video_offsets")

            val out = ArrayList<ManifestItem>()
            var mediaIndex = 0
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue

                if (obj.has("midias")) {
                    val campanha = obj.optString("campanha", "")
                    val dtInicio = obj.optNullableString("dt_inicio")
                    val dtFim = obj.optNullableString("dt_fim")
                    val midiasArr = obj.optJSONArray("midias") ?: JSONArray()

                    for (j in 0 until midiasArr.length()) {
                        val mediaObj = midiasArr.optJSONObject(j) ?: continue
                        val id = mediaObj.optString("id", "").ifBlank { mediaObj.optString("media_id", "") }
                        val rawUrl = mediaObj.optString("url", "").ifBlank { mediaObj.optString("src", "") }
                        val url = normalizeUrl(rawUrl)
                        val type = mediaObj.optString("type", "").lowercase().ifBlank { inferTypeFromUrl(url) }
                        if (id.isBlank() || url.isBlank()) continue

                        val durationMs = mediaObj.optLong("duration_ms").takeIf { it > 0 }
                            ?: (mediaObj.optLong("duration").takeIf { it > 0 }?.let { it * 1000 })
                        val volume = resolveVolume(mediaObj, volumesArr, mediaIndex)
                        val offsetStartMs = mediaObj.optLong("offset_start_ms").takeIf { it > 0 }
                            ?: (mediaObj.optLong("offset_start").takeIf { it > 0 }?.let { it * 1000 })
                            ?: (mediaObj.optLong("videoStartOffset").takeIf { it > 0 }?.let { it * 1000 })
                            ?: offsetsArr?.optJSONObject(mediaIndex)?.optLong("start")?.takeIf { it > 0 }?.let { it * 1000 }
                        val offsetEndMs = mediaObj.optLong("offset_end_ms").takeIf { it > 0 }
                            ?: (mediaObj.optLong("offset_end").takeIf { it > 0 }?.let { it * 1000 })
                            ?: (mediaObj.optLong("videoEndOffset").takeIf { it > 0 }?.let { it * 1000 })
                            ?: offsetsArr?.optJSONObject(mediaIndex)?.optLong("end")?.takeIf { it > 0 }?.let { it * 1000 }

                        val inicioFaixa = mediaObj.optNullableString("inicio_faixa")
                        val fimFaixa = mediaObj.optNullableString("fim_faixa")

                        out += ManifestItem(
                            id = id,
                            type = type,
                            url = url,
                            name = mediaObj.optString("name", "").takeIf { it.isNotBlank() } ?: campanha.takeIf { it.isNotBlank() },
                            durationMs = durationMs,
                            volume = volume,
                            offsetStartMs = offsetStartMs,
                            offsetEndMs = offsetEndMs,
                            startDate = dtInicio,
                            endDate = dtFim,
                            startTime = inicioFaixa,
                            endTime = fimFaixa,
                        )
                        mediaIndex++
                    }
                } else {
                    val id = obj.optString("id", "").ifBlank { obj.optString("media_id", "") }
                    val rawUrl = obj.optString("url", "").ifBlank { obj.optString("src", "") }
                    val url = normalizeUrl(rawUrl)
                    val type = obj.optString("type", "").lowercase().ifBlank { inferTypeFromUrl(url) }
                    if (id.isBlank() || url.isBlank()) continue

                    val durationMs = obj.optLong("duration_ms").takeIf { it > 0 }
                        ?: (obj.optLong("duration").takeIf { it > 0 }?.let { it * 1000 })
                    val volume = resolveVolume(obj, volumesArr, mediaIndex)
                    val offsetStartMs = obj.optLong("offset_start_ms").takeIf { it > 0 }
                        ?: (obj.optLong("offset_start").takeIf { it > 0 }?.let { it * 1000 })
                        ?: (obj.optLong("videoStartOffset").takeIf { it > 0 }?.let { it * 1000 })
                        ?: offsetsArr?.optJSONObject(mediaIndex)?.optLong("start")?.takeIf { it > 0 }?.let { it * 1000 }
                    val offsetEndMs = obj.optLong("offset_end_ms").takeIf { it > 0 }
                        ?: (obj.optLong("offset_end").takeIf { it > 0 }?.let { it * 1000 })
                        ?: (obj.optLong("videoEndOffset").takeIf { it > 0 }?.let { it * 1000 })
                        ?: offsetsArr?.optJSONObject(mediaIndex)?.optLong("end")?.takeIf { it > 0 }?.let { it * 1000 }

                    val dtInicio = obj.optNullableString("dt_inicio")
                    val dtFim = obj.optNullableString("dt_fim")
                    val inicioFaixa = obj.optNullableString("inicio_faixa")
                    val fimFaixa = obj.optNullableString("fim_faixa")

                    out += ManifestItem(
                        id = id,
                        type = type,
                        url = url,
                        name = obj.optString("name", "").takeIf { it.isNotBlank() },
                        durationMs = durationMs,
                        volume = volume,
                        offsetStartMs = offsetStartMs,
                        offsetEndMs = offsetEndMs,
                        startDate = dtInicio,
                        endDate = dtFim,
                        startTime = inicioFaixa,
                        endTime = fimFaixa,
                    )
                    mediaIndex++
                }
            }
            out
        } catch (e: Exception) {
            android.util.Log.e("ManifestManager", "Failed to parse items", e)
            emptyList()
        }
    }

    fun parseItemsPublic(json: String): List<ManifestItem> = parseItems(json)

    fun parsePlaylistName(json: String): String? {
        return runCatching {
            val root = JSONObject(json)
            val manifestObj = root.optJSONObject("manifest") ?: root
            manifestObj.optString("name", "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun parsePriceConfigJson(json: String): String? {
        return runCatching {
            val root = JSONObject(json)
            val direct = root.optJSONObject("price_config")
            if (direct != null) return@runCatching direct.toString()
            val manifest = root.optJSONObject("manifest")
            val fromManifest = manifest?.optJSONObject("price_config")
            if (fromManifest != null) return@runCatching fromManifest.toString()
            val device = root.optJSONObject("device")
            val fromDevice = device?.optJSONObject("price_config")
            fromDevice?.toString()
        }.getOrNull()
    }

    private fun inferTypeFromUrl(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".webm") -> "video"
            clean.endsWith(".gif") -> "gif"
            clean.endsWith(".webp") -> "webp"
            clean.endsWith(".png") -> "png"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "jpg"
            else -> "image"
        }
    }

    private fun resolveExtension(item: ManifestItem): String {
        val fromUrl = item.url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase()
        if (fromUrl.length in 2..5) return ".$fromUrl"

        return when (item.type.lowercase()) {
            "video" -> ".mp4"
            "gif" -> ".gif"
            "webp" -> ".webp"
            "png" -> ".png"
            "jpg", "jpeg" -> ".jpg"
            else -> ".bin"
        }
    }

    private fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1).trim()
        }
        s = s.replace("`", "").trim()
        return s
    }

    private fun resolveVolume(obj: JSONObject, volumesArr: JSONArray?, index: Int): Float? {
        val direct = obj.optDouble("volume", Double.NaN).takeIf { !it.isNaN() }
            ?: volumesArr?.optDouble(index, Double.NaN)?.takeIf { !it.isNaN() }

        val v = direct?.toFloat() ?: return null
        val normalized =
            when {
                v <= 1f -> v
                v <= 10f -> v / 10f
                v <= 100f -> v / 100f
                else -> 1f
            }
        return normalized.coerceIn(0f, 1f)
    }

    private fun downloadToFile(
        url: String,
        target: File,
        onChunk: ((bytesDownloaded: Long, bytesTotal: Long?) -> Unit)? = null,
    ) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.parentFile?.mkdirs()

        val req = Request.Builder()
            .url(normalizeUrl(url))
            .get()
            .build()

        var copiedBytes = 0L
        var totalBytes: Long? = null
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("http_${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("empty_body")
            totalBytes = body.contentLength().takeIf { it > 0L }
            FileOutputStream(tmp).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (true) {
                        read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        copiedBytes += read.toLong()
                        onChunk?.invoke(copiedBytes, totalBytes)
                    }
                }
                runCatching { out.fd.sync() }
            }
        }

        val expected = totalBytes
        if (expected != null && expected > 0L && copiedBytes != expected) {
            runCatching { tmp.delete() }
            throw IllegalStateException("download_incomplete")
        }
        if (!tmp.exists() || tmp.length() == 0L) {
            runCatching { tmp.delete() }
            throw IllegalStateException("empty_file")
        }

        if (target.exists()) runCatching { target.delete() }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            runCatching { tmp.delete() }
        }
    }
}
