package com.phoneassistant

import android.content.Context
import org.json.JSONArray

/**
 * Durable notes about the user, kept between conversations. Model suggestions are
 * checked locally and don't require a second API call.
 */
object Facts {

    private const val PREFS = "facts"
    private const val KEY = "list"
    private const val MAX_FACTS = 40
    @Volatile private var revision = 0L

    fun revision() = revision

    fun all(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Handles a whole naming request locally, before it could be sent to the API or Phone app. */
    @Synchronized
    fun saveNameRequest(ctx: Context, message: String): String? {
        val name = PreferredName.parse(message) ?: return null
        val existing = all(ctx)
        val updated = PreferredName.save(existing, name, MAX_FACTS)
        if (existing != updated) write(ctx, updated)
        return "I’ll call you $name. I saved that in Memory."
    }

    @Synchronized
    fun add(ctx: Context, text: String) {
        val cleaned = clean(text)
        if (cleaned.length < 3) return
        val existing = all(ctx)
        if (existing.any { it.equals(cleaned, ignoreCase = true) }) return
        write(ctx, (existing + cleaned).takeLast(MAX_FACTS))
    }

    @Synchronized
    fun remove(ctx: Context, text: String) {
        write(ctx, all(ctx).filterNot { it == text })
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        revision++
    }

    /** The block appended to the system prompt. Empty when nothing is known yet. */
    fun promptBlock(ctx: Context): String {
        val facts = all(ctx)
        if (facts.isEmpty()) return ""
        return buildString {
            if (facts.any { it.startsWith(PreferredName.NOTE_PREFIX, ignoreCase = true) }) {
                append("Address the user by their preferred name below, even if an older note uses a different name.\n")
            }
            append("NOTES ABOUT THEM (things they told you. Use them naturally when they")
            append(" matter, and never recite the list back at them.)\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }.trimEnd()
    }

    fun rememberExplicitly(ctx: Context, message: String): Boolean {
        val note = MemorySuggestion.explicitNote(message) ?: return false
        add(ctx, note)
        return true
    }

    /** Don't re-create forgotten notes or apply a late reply after a different chat turn. */
    @Synchronized
    internal fun applySuggestion(ctx: Context, note: MemorySuggestion.Note, expectedRevision: Long, turn: Long): Boolean {
        if (revision != expectedRevision || !ChatTurns.isCurrent(turn) ||
            !PipSettings.enabled(ctx, PipSettings.Option.LEARN_FROM_CHAT)) return false
        val existing = all(ctx)
        val next = MemorySuggestion.merge(existing, note, MAX_FACTS)
        if (next == existing) return false
        write(ctx, next)
        return true
    }

    private fun clean(raw: String): String = raw
        .trim()
        .trim('"', '\'', '“', '”')
        .trimEnd('.', '!', '?', ',', ';', ':')
        .replace(Regex("\\s+"), " ")
        .take(140)
        .trim()

    private fun write(ctx: Context, facts: List<String>) {
        val array = JSONArray()
        facts.forEach { array.put(it) }
        prefs(ctx).edit().putString(KEY, array.toString()).apply()
        revision++
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
