package com.phoneassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Starts the overlay after normal boot, once credential-protected settings are available. */
class PipBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !PipStartup.enabled(context) ||
            !Settings.canDrawOverlays(context) || OverlayService.running) return

        // Some devices restrict boot-time background starts. Don't retry if Android
        // rejects the foreground service launch.
        OverlayService.startFromBoot(context)
    }
}

internal object PipStartup {
    private const val PREFS = "startup"
    private const val KEY = "start_at_boot"
    private const val OPEN_APP_AT_BOOT = "open_app_at_boot"
    private const val LISTEN_ON_START = "listen_on_foreground_start"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    fun openAppAtBoot(context: Context): Boolean = prefs(context).getBoolean(OPEN_APP_AT_BOOT, false)

    fun setOpenAppAtBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(OPEN_APP_AT_BOOT, enabled).apply()
    }

    fun listenOnStart(context: Context): Boolean = prefs(context).getBoolean(LISTEN_ON_START, false)

    fun setListenOnStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(LISTEN_ON_START, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
