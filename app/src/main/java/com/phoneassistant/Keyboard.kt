package com.phoneassistant

import android.content.Context

/** Remembers the measured height of the soft keyboard so it is known before it opens. */
object Keyboard {

    private const val PREFS = "keyboard"
    private const val KEY = "height_dp"

    /** Last measured height in pixels, or 0 when the keyboard has never been seen. */
    fun rememberedPx(ctx: Context): Int {
        val dp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 0f)
        if (dp <= 0f) return 0
        return (dp * ctx.resources.displayMetrics.density).toInt()
    }

    fun remember(ctx: Context, px: Int) {
        if (px <= 0) return
        val dp = px / ctx.resources.displayMetrics.density
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, dp).apply()
    }
}
