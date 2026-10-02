package com.mupa.player.enterprise

import android.app.Application
import com.mupa.player.enterprise.services.CrashRecoveryManager

class MupaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecoveryManager.install(this)
    }
}
