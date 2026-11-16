package com.mupa.player.enterprise.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mupa.player.enterprise.databinding.ActivitySplashBinding
import com.mupa.player.enterprise.managers.DeviceCacheManager
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.services.DeviceValidationResult
import com.mupa.player.enterprise.services.DeviceValidationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : ComponentActivity() {
    private var phoneStatePermissionDeferred: CompletableDeferred<Boolean>? = null
    private val phoneStatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            phoneStatePermissionDeferred?.complete(granted)
            phoneStatePermissionDeferred = null
        }

    private suspend fun ensurePhoneStatePermission() {
        if (Build.VERSION.SDK_INT < 23) return
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) return

        val deferred = CompletableDeferred<Boolean>()
        phoneStatePermissionDeferred = deferred
        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        withTimeoutOrNull(8_000L) { deferred.await() }
        if (phoneStatePermissionDeferred === deferred) {
            phoneStatePermissionDeferred = null
        }
    }
    override fun attachBaseContext(newBase: Context) {
        val override = Configuration(newBase.resources.configuration).apply {
            fontScale = 0.85f
            densityDpi = (densityDpi * 1.15f).toInt().coerceIn(120, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        applyAlwaysOnScreen()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.deviceIdWatermark.text = "ID: -"

        lifecycleScope.launch {
            binding.stepText.text = "1 Identificando dispositivo"
            ensurePhoneStatePermission()
            val deviceId = DeviceIdentityManager(applicationContext).getPersistentId()
            binding.deviceIdWatermark.text = "ID: ${deviceId.trim()}"
            val cacheManager = DeviceCacheManager(applicationContext)
            val cached = cacheManager.load()
            val cachedRegistered = cached?.takeIf { it.deviceRegistered }?.deviceId?.trim().orEmpty()

            binding.stepText.text = "2 Validando cadastro"
            val nextIntent = if (isOnline()) {
                // Com WiFi ligado mas internet ruim/inexistente, o isOnline() acima é true e a
                // chamada ao servidor pode pendurar por dezenas de segundos (DNS + timeouts do
                // OkHttp) ANTES de o player abrir. Já existe fallback pra cadastro em cache
                // (branches Error/NotConfigured), então estourar o prazo só cai nele mais rápido.
                val validation = withTimeoutOrNull(VALIDATION_TIMEOUT_MS) {
                    DeviceValidationService(applicationContext).validateDevice(deviceId)
                } ?: DeviceValidationResult.Error("timeout")
                when (val result = validation) {
                    is DeviceValidationResult.Found -> {
                        val serial = result.cache.deviceId.ifBlank { deviceId }
                        Intent(this@SplashActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_DEVICE_ID, serial.trim())
                    }
                    DeviceValidationResult.NotFound -> {
                        Intent(this@SplashActivity, DeviceRegistrationActivity::class.java)
                    }
                    DeviceValidationResult.NotConfigured -> {
                        if (cachedRegistered.isNotBlank()) {
                            Intent(this@SplashActivity, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_DEVICE_ID, cachedRegistered)
                        } else {
                            Intent(this@SplashActivity, DeviceRegistrationActivity::class.java)
                        }
                    }
                    is DeviceValidationResult.Error -> {
                        if (cachedRegistered.isNotBlank()) {
                            Intent(this@SplashActivity, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_DEVICE_ID, cachedRegistered)
                        } else {
                            Intent(this@SplashActivity, DeviceRegistrationActivity::class.java)
                        }
                    }
                }
            } else {
                if (cachedRegistered.isNotBlank()) {
                    Intent(this@SplashActivity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_DEVICE_ID, cachedRegistered)
                } else {
                    Intent(this@SplashActivity, DeviceRegistrationActivity::class.java)
                }
            }

            delay(250)
            binding.stepText.text = "3 Carregando"
            startActivity(nextIntent)
            finish()
        }
    }

    private fun isOnline(): Boolean {
        val cm = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val VALIDATION_TIMEOUT_MS = 5_000L
    }

    private fun applyAlwaysOnScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = window.attributes
        p.screenBrightness = 1f
        window.attributes = p

        if (android.os.Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(this)) {
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            runCatching {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    255,
                )
            }
        }
    }
}
