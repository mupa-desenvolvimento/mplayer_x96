package com.mupa.player.enterprise.managers

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mupa.player.enterprise.BuildConfig
import com.mupa.player.enterprise.storage.settingsDataStore
import kotlinx.coroutines.flow.first
import java.net.NetworkInterface
import java.util.UUID

class DeviceIdentityManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        val persistentDeviceId = stringPreferencesKey(PERSISTENT_DEVICE_ID_TAG)
        val legacyPersistentDeviceId = stringPreferencesKey(LEGACY_DEVICE_ID_TAG)
    }

    suspend fun getPersistentId(): String = generateIfMissing()

    suspend fun generateIfMissing(): String {
        val current = load()
        // Uma vez salva e validada, a identidade nunca é trocada implicitamente aqui — só
        // re-resolve quando não há nenhuma ainda. Isso já causou perda real de dados: com o
        // WiFi desligado, a wlan0 some da lista de interfaces, resolveMacAddress() falha, e o
        // fallback (resolveZebraStableId) lê ro.serialno/ro.boot.serialno — que em vários X96
        // baratos vem de fábrica com o mesmo placeholder genérico ("1234567890", não-único
        // entre aparelhos). Como esse valor "diferente mas válido" passava no validate(), o
        // código sobrescrevia o id real (derivado do MAC, único) por esse placeholder a cada
        // boot sem WiFi — perdendo o cadastro e o conteúdo já baixado, mesmo com tudo intacto
        // em disco/banco sob o id antigo. Um troca de identidade legítima (ex.: device
        // reaproveitado em outro hardware) passa pelo wipe completo, não por aqui.
        if (!current.isNullOrBlank()) return current

        val best = resolveBestAvailableId()
        save(best)
        return best
    }

    suspend fun save(deviceId: String) {
        val normalized = deviceId.trim()
        if (!validate(normalized)) return

        legacyPrefs.edit()
            .putString(PERSISTENT_DEVICE_ID_TAG, normalized)
            .putString(LEGACY_DEVICE_ID_TAG, normalized)
            .apply()
        context.settingsDataStore.edit {
            it[Keys.persistentDeviceId] = normalized
            it[Keys.legacyPersistentDeviceId] = normalized
        }
    }

    suspend fun load(): String? {
        val prefs = context.settingsDataStore.data.first()
        val fromDsNew = prefs[Keys.persistentDeviceId]
        if (!fromDsNew.isNullOrBlank()) return fromDsNew
        val fromDsLegacy = prefs[Keys.legacyPersistentDeviceId]
        if (!fromDsLegacy.isNullOrBlank()) return fromDsLegacy

        val fromPrefsNew = legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, null)
        if (!fromPrefsNew.isNullOrBlank()) return fromPrefsNew
        val fromPrefsLegacy = legacyPrefs.getString(LEGACY_DEVICE_ID_TAG, null)
        return fromPrefsLegacy?.takeIf { it.isNotBlank() }
    }

    fun getCachedId(): String {
        val fromNew = legacyPrefs.getString(PERSISTENT_DEVICE_ID_TAG, "") ?: ""
        if (fromNew.isNotBlank()) return fromNew
        return legacyPrefs.getString(LEGACY_DEVICE_ID_TAG, "") ?: ""
    }

    fun validate(deviceId: String): Boolean = deviceId.isNotBlank() && deviceId.length >= 8

    private fun resolveBestAvailableId(): String {
        val stable = resolveStableHardwareId()
        if (!stable.isNullOrBlank()) return stable

        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim()
        if (!androidId.isNullOrBlank() && androidId.lowercase() != ANDROID_ID_BUG) return androidId
        return ""
    }

    private fun resolveStableHardwareId(): String? {
        // This app is dedicated to Android-9-and-below TV boxes with no camera/barcode
        // scanner; must match the ARGOS Agent legacy flavor's algorithm exactly (same
        // NetworkInterface("wlan0") MAC read, same uppercase-hex-no-separators format)
        // since the Web panel registration needs both apps to agree on one serial for
        // the same physical device. Build.getSerial() is unreliable on these devices.
        if (BuildConfig.USE_MAC_AS_SERIAL) {
            resolveMacAddress()?.let { return it }
        }

        val zebraId = resolveZebraStableId()
        if (!zebraId.isNullOrBlank()) return zebraId

        val serial = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }.getOrNull()?.trim().orEmpty()
        if (serial.isBlank()) return null
        if (serial.equals("unknown", ignoreCase = true)) return null
        if (!validate(serial)) return null
        return serial
    }

    private fun resolveMacAddress(): String? {
        val mac = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            for (iface in interfaces) {
                if (!iface.name.equals("wlan0", ignoreCase = true)) continue
                val bytes = iface.hardwareAddress ?: continue
                val formatted = bytes.joinToString("") { "%02X".format(it) }
                if (formatted.isNotBlank() && formatted != "000000000000") return@runCatching formatted
            }
            null
        }.getOrNull()
        return mac?.takeIf { validate(it) }
    }

    private fun resolveZebraStableId(): String? {
        val keys = listOf(
            "persist.vendor.zebra.sys.uuid",
            "ro.vendor.zebra.hardware.uuid",
            "ro.serialno",
            "ro.boot.serialno",
        )
        for (k in keys) {
            val v = readSystemPropertyCompat(k)?.trim().orEmpty()
            if (v.isBlank()) continue
            if (v.equals("unknown", ignoreCase = true)) continue
            if (!validate(v)) continue
            return v
        }
        return null
    }

    private fun readSystemPropertyCompat(key: String): String? {
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, "") as? String)?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    companion object {
        const val PERSISTENT_DEVICE_ID_TAG = "persistent_device_id"
        private const val LEGACY_DEVICE_ID_TAG = "device_id"

        private const val LEGACY_PREFS_NAME = "mupa_device_identity_legacy"
        private const val ANDROID_ID_BUG = "9774d56d682e549c"
    }
}
