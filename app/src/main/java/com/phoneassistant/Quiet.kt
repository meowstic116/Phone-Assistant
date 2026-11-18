package com.phoneassistant

import android.content.Context

/**
 * When the user has asked Pip to stop getting his toys out for a while. Stored as a
 * wall-clock time, so it survives the service restarting and even a reboot.
 */
object Quiet {

    private const val PREFS = "quiet"
    private const val KEY = "until"

    /** When he may play again. 0 when he is free to play now. */
    private fun until(ctx: Context): Long =
        prefs(ctx).getLong(KEY, 0L)

    fun remaining(ctx: Context): Long =
        (until(ctx) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun active(ctx: Context): Boolean = remaining(ctx) > 0L

    fun start(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY, System.currentTimeMillis() + ms).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
