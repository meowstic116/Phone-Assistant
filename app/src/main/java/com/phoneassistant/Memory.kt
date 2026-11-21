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
    internal const val MAX_MESSAGE_CHARS = 4096

    private fun bounded(content: String) =
        if (content.length <= MAX_MESSAGE_CHARS) content else content.take(MAX_MESSAGE_CHARS).trimEnd { it.isHighSurrogate() }

    fun history(ctx: Context): List<Msg> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (maxOf(0, array.length() - MAX_MESSAGES) until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.opt("role") as? String ?: return@mapNotNull null
                val content = item.opt("content") as? String ?: return@mapNotNull null
                if (role == "user" || role == "assistant") Msg(role, bounded(content)) else null
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    /** Sending the current text must not depend on a successful local history write. */
    internal fun forReply(history: List<Msg>, current: String): List<Msg> {
        val latest = Msg("user", bounded(current))
        return if (history.lastOrNull() == latest) history else history.takeLast(MAX_MESSAGES - 1) + latest
    }

    @Synchronized
    fun append(ctx: Context, role: String, content: String) {
        val messages = history(ctx).toMutableList()
        messages.add(Msg(role, bounded(content)))
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
