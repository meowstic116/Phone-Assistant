package com.phoneassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Conversation history, kept on the device and trimmed to a rolling window. */
object Memory {

    data class Msg(val role: String, val content: String)

    private const val PREFS = "memory"
    private const val KEY = "history"
    private const val MAX_MESSAGES = 24

    fun history(ctx: Context): List<Msg> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Msg(item.getString("role"), item.getString("content"))
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun append(ctx: Context, role: String, content: String) {
        val messages = history(ctx).toMutableList()
        messages.add(Msg(role, content))
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)

        val array = JSONArray()
        messages.forEach {
            array.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        prefs(ctx).edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
