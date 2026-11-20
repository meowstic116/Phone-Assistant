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

        // Some devices restrict boot-time background starts. Don't open an activity or
        // keep retrying if Android rejects the foreground service launch.
        OverlayService.start(context)
    }
}

internal object PipStartup {
    private const val PREFS = "startup"
    private const val KEY = "start_at_boot"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
