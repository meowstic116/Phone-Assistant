package com.phoneassistant

/** Bound the model's suggestions to words from the current user message. */
internal object MemorySuggestion {
    const val MARKER = "PIP_MEMORY:"
    private const val MAX_EVIDENCE = 140
    private val space = Regex("\\s+")
    private val sentences = Regex("""(?:^|(?<=[.!?;]) )(.+?)(?:([.!?;]+)(?= |$)|$)""")
    private val firstPerson = Regex("""\b(?:i|me|my|mine|our|we)\b""", RegexOption.IGNORE_CASE)
    private val skip = Regex(
        """\b(?:don't|do not|never|please don't)\s+(?:remember|save|store|keep|record)\b|\b(?:forget|off the record|keep (?:this|it) private|no memory|just kidding|hypothetically)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sensitive = Regex(
        """\b(?:pass(?:word|code|phrase)|api[- ]?key|token|secret|credentials?|pin|otp|verification code|login|ssn|social security|credit card|bank account|routing number|date of birth|home address|email address|phone number|diagnosis|medication|medical|mental health)\b|[\w.%+\-]+@[\w.\-]+\.[\p{L}]{2,}|https?://|\d{4,}|\b\d{3}[ .\-]\d{3}[ .\-]\d{4}\b""",
        RegexOption.IGNORE_CASE
    )
    private val explicit = Regex("""^(?:(?:hey\s+)?pip[,:]\s*)?(?:please\s+)?remember\s+(?:that\s+)?(.+)$""", RegexOption.IGNORE_CASE)
    private val categories = setOf("preference", "routine", "ongoing_goal", "personal_context", "preferred_name")

    data class Note(val text: String, val preferredName: Boolean = false)
    data class Reply(val line: String, val payload: String?)

    fun splitReply(text: String): Reply {
        if (text.startsWith(MARKER, ignoreCase = true)) return Reply("", null)
        val markerAt = text.indexOf("\n$MARKER", ignoreCase = true)
        if (markerAt < 0) return Reply(text, null)
        val payload = text.substring(markerAt + MARKER.length + 1).trim()
        return Reply(text.substring(0, markerAt).trimEnd(), payload.takeIf { it.length in 2..700 })
    }

    fun maySuggest(message: String): Boolean {
        val plain = normalize(message)
        return plain.isNotBlank() && !skip.containsMatchIn(plain) && !sensitive.containsMatchIn(plain)
    }

    fun explicitNote(message: String): String? {
        if (!maySuggest(message)) return null
        val note = explicit.matchEntire(message.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (note.startsWith("to ", ignoreCase = true) || note.length !in 3..MAX_EVIDENCE) return null
        return note
    }

    fun accept(message: String, kind: String, quote: String, value: String? = null): Note? {
        if (kind !in categories || !maySuggest(message)) return null
        val source = normalize(message)
        val evidence = quote.trim().replace(space, " ")
        if (evidence.length !in 3..MAX_EVIDENCE || !firstPerson.containsMatchIn(evidence)) return null
        // Match a complete sentence/clause, not a fragment edited out of a denial or a joke.
        val sentence = normalize(evidence).trimEnd('.', '!', '?', ';')
        val matched = sentences.findAll(source).any {
            it.groupValues[1].trim() == sentence && '?' !in it.groupValues[2]
        }
        if (!matched) return null
        if (kind == "preferred_name") {
            val candidate = value?.let { PreferredName.parse("Refer to me as \"$it\"") } ?: return null
            val exact = Regex("(?<![\\p{L}\\p{M}])${Regex.escape(normalize(candidate))}(?![\\p{L}\\p{M}])")
                .find(sentence) ?: return null
            // Use the user's spelling; never persist a model paraphrase.
            return Note(evidence.substring(exact.range), preferredName = true)
        }
        return Note(evidence)
    }

    fun merge(existing: List<String>, note: Note, limit: Int): List<String> {
        if (note.preferredName) {
            if (existing.any { it.startsWith(PreferredName.NOTE_PREFIX, ignoreCase = true) }) return existing
            return PreferredName.save(existing, note.text, limit)
        }
        val key = normalize(note.text).trimEnd('.', '!', '?', ';')
        if (existing.any { normalize(it).trimEnd('.', '!', '?', ';') == key }) return existing
        return (existing + note.text).takeLast(limit)
    }

    // Keep length and case for verbatim evidence; normalize only whitespace and apostrophes.
    private fun normalize(text: String) = text.trim().replace('’', '\'').replace(space, " ")
}
