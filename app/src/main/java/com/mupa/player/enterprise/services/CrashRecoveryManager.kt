package com.mupa.player.enterprise.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mupa.player.enterprise.ui.SplashActivity
import kotlin.system.exitProcess

/**
 * Painel LED na rua não tem ninguém pra apertar um botão quando o app trava. Sem isso, um
 * `Thread.setDefaultUncaughtExceptionHandler` ausente significa que uma exceção não tratada
 * deixa a tela preta/congelada até alguém notar e reiniciar manualmente o dispositivo.
 *
 * Agenda o relançamento via [AlarmManager] (sobrevive à morte do processo atual) e só então
 * mata o processo — a única forma confiável de sair de um estado de app corrompido.
 */
object CrashRecoveryManager {
    private const val TAG = "MPlayerCrashRecovery"
    private const val RESTART_DELAY_MS = 2_000L
    private const val PREFS_NAME = "mupa_crash_legacy"
    private const val KEY_LAST_CRASH_AT = "last_crash_at_epoch_ms"
    private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(appContext, throwable) }
                .onFailure { Log.e(TAG, "record_crash_failed", it) }
            runCatching { scheduleRestart(appContext) }
                .onFailure { Log.e(TAG, "schedule_restart_failed", it) }

            Log.e(TAG, "uncaught_exception thread=${thread.name}", throwable)

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Consumido pelo heartbeat: último crash conhecido, lido uma vez e limpo. */
    fun consumeLastCrash(context: Context): Pair<Long, String>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_LAST_CRASH_AT, 0L)
        if (at <= 0L) return null
        val message = prefs.getString(KEY_LAST_CRASH_MESSAGE, null).orEmpty()
        prefs.edit().clear().apply()
        return at to message
    }

    private fun recordCrash(context: Context, throwable: Throwable) {
        val message = "${throwable.javaClass.simpleName}: ${throwable.message}".take(500)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
            .putString(KEY_LAST_CRASH_MESSAGE, message)
            .apply()
    }

    private fun scheduleRestart(context: Context) {
        val launchIntent = Intent(context, SplashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }
}
