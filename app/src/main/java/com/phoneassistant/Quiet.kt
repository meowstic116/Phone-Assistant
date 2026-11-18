package com.phoneassistant

import android.content.Context

/**
 * When the user has asked Pip to stop getting his toys out for a while. Stored as a
 * wall-clock time, so it survives the service restarting and even a reboot — and so does
 * the fact that a break ended before he had the chance to say anything about it.
 */
object Quiet {

    private const val PREFS = "quiet"
    private const val KEY = "until"
    private const val SAID = "said"

    /**
     * Called after a break starts or is cleared, so the overlay can schedule its wake-up
     * for the new deadline instead of polling for it.
     */
    var onChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When he may play again. 0 when he is free to play now. */
    private fun until(ctx: Context): Long = prefs(ctx).getLong(KEY, 0L)

    /** The deadline he has already announced the end of. */
    private fun said(ctx: Context): Long = prefs(ctx).getLong(SAID, 0L)

    fun remaining(ctx: Context): Long =
        (until(ctx) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun active(ctx: Context): Boolean = remaining(ctx) > 0L

    /**
     * True when a break has run out and he never got to mention it — because the phone was
     * asleep, or because the service was killed before it could speak. Checks the clock
     * rather than any in-memory flag, so a restart does not lose it.
     */
    fun owed(ctx: Context): Boolean {
        val deadline = until(ctx)
        return deadline > 0L && deadline <= System.currentTimeMillis() && said(ctx) != deadline
    }

    /** Records that the end of the current break has been announced. */
    fun markSaid(ctx: Context) {
        val deadline = until(ctx)
        if (deadline <= 0L) return
        prefs(ctx).edit().putLong(SAID, deadline).apply()
    }

    fun start(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY, System.currentTimeMillis() + ms).apply()
        onChanged?.invoke()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        onChanged?.invoke()
    }
}
