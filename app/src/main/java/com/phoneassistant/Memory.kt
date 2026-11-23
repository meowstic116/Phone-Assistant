package com.phoneassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversation history, kept on the device and trimmed to a rolling window. The window
 * rules live in [ChatWindow], where the host tests can compile them.
 */
object Memory {

    private const val PREFS = "memory"
    private const val KEY = "history"

    /** Kept here so existing callers keep their single source for the limit. */
    internal const val MAX_MESSAGE_CHARS = ChatWindow.MAX_MESSAGE_CHARS

    fun history(ctx: Context): List<Msg> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (maxOf(0, array.length() - ChatWindow.MAX_MESSAGES) until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val role = item.opt("role") as? String ?: return@mapNotNull null
                val content = item.opt("content") as? String ?: return@mapNotNull null
                if (role == "user" || role == "assistant") Msg(role, ChatWindow.bounded(content)) else null
            }
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }

    /** Sending the current text must not depend on a successful local history write. */
    internal fun forReply(history: List<Msg>, current: String): List<Msg> =
        ChatWindow.forReply(history, current)

    @Synchronized
    fun append(ctx: Context, role: String, content: String) {
        val messages = history(ctx).toMutableList()
        messages.add(Msg(role, ChatWindow.bounded(content)))
        while (messages.size > ChatWindow.MAX_MESSAGES) messages.removeAt(0)

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
