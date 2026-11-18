package com.phoneassistant

import android.content.Context
import org.json.JSONArray

/**
 * Durable notes about the user, kept between conversations. Facts are pulled out
 * locally with cheap pattern matching so remembering costs no extra API calls.
 */
object Facts {

    private const val PREFS = "facts"
    private const val KEY = "list"
    private const val MAX_FACTS = 40

    fun all(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun add(ctx: Context, text: String) {
        val cleaned = clean(text)
        if (cleaned.length < 3) return
        val existing = all(ctx)
        if (existing.any { it.equals(cleaned, ignoreCase = true) }) return
        write(ctx, (existing + cleaned).takeLast(MAX_FACTS))
    }

    fun remove(ctx: Context, text: String) {
        write(ctx, all(ctx).filterNot { it == text })
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** The block appended to the system prompt. Empty when nothing is known yet. */
    fun promptBlock(ctx: Context): String {
        val facts = all(ctx)
        if (facts.isEmpty()) return ""
        return buildString {
            append("NOTES ABOUT THEM (things they told you. Use them naturally when they")
            append(" matter, and never recite the list back at them.)\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }.trimEnd()
    }

    /**
     * Pulls out the things people tend to say about themselves. Deliberately
     * conservative: an occasional miss is better than storing nonsense forever.
     */
    fun rememberFromMessage(ctx: Context, message: String) {
        val text = message.trim()
        if (text.length < 4) return
        val found = mutableListOf<String>()

        // Specific patterns first, so "remember my name is Sam" is stored once, not twice.
        Regex("(?i)\\b(?:my name is|call me|i'm called|i am called) ([\\p{L}][\\p{L} .'-]{0,30})")
            .find(text)?.let {
                found += "My name is " + clean(it.groupValues[1]) + "."
            }
        Regex("(?i)\\bi(?:'m| am) working on (.{3,100})").find(text)?.let {
            found += "I'm working on " + clean(it.groupValues[1]) + "."
        }
        Regex("(?i)\\bi (like|love|enjoy|prefer) (.{3,100})").find(text)?.let {
            found += "I " + it.groupValues[1].lowercase() + " " + clean(it.groupValues[2]) + "."
        }
        Regex("(?i)\\bi (hate|dislike|can't stand|cannot stand) (.{3,100})").find(text)?.let {
            found += "I " + it.groupValues[1].lowercase() + " " + clean(it.groupValues[2]) + "."
        }
        Regex("(?i)\\bi live in (.{2,60})").find(text)?.let {
            found += "I live in " + clean(it.groupValues[1]) + "."
        }

        if (found.isEmpty()) {
            Regex("(?i)remember (?:that )?(.{3,140})").find(text)?.let {
                val captured = clean(it.groupValues[1])
                // "remember to buy milk" is a chore, not a fact about them.
                if (!captured.lowercase().startsWith("to ")) found += captured
            }
        }

        found.forEach { add(ctx, it) }
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
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
