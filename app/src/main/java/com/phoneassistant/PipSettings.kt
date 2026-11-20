package com.phoneassistant

import android.content.Context

/** User choices for Pip, separate from Android permissions and temporary toy breaks. */
internal object PipSettings {
    enum class Option(val key: String) {
        ACCESSIBILITY("accessibility"), WANDER("wander"), TOYS("toys"), SPONTANEOUS("spontaneous_toys"),
        LEARN_FROM_CHAT("learn_from_chat")
    }

    fun enabled(ctx: Context, option: Option): Boolean = prefs(ctx).getBoolean(option.key, true)

    fun set(ctx: Context, option: Option, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(option.key, enabled).apply()
    }

    fun toySelected(ctx: Context, toy: Toy): Boolean = prefs(ctx).getBoolean(toyKey(toy), true)

    fun setToy(ctx: Context, toy: Toy, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(toyKey(toy), enabled).apply()
    }

    fun toyAllowed(ctx: Context, toy: Toy): Boolean = enabled(ctx, Option.TOYS) && toySelected(ctx, toy)

    private fun toyKey(toy: Toy) = "toy_${toy.name}"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pip_settings", Context.MODE_PRIVATE)
}
