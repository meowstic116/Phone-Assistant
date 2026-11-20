package com.phoneassistant

/** Recognize a direct naming request, not a name mentioned in a quote or a longer phone job. */
internal object PreferredName {
    const val NOTE_PREFIX = "Preferred name: "

    private val request = Regex(
        """^(?:(?:(?:hey|hi|hello)\s+)?pip[,:!]\s*)?(?:(?:please|remember(?: that)?|from now on|going forward)[,:]?\s+)*(?:(?:can|could|would|will) you\s+(?:please\s+)?)?(?:(?:you can|you may)\s+)?(call me|refer to me as|address me as|(?:my (?:preferred )?name is)|(?:i['’]m|i am) called|i (?:prefer|want) to be called)\s*:?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val suffix = Regex("""(?:,?\s+(?:from now on|going forward|instead|please))+$""", RegexOption.IGNORE_CASE)
    private val name = Regex("""(?:[\p{L}\p{M}]+(?:['’\-][\p{L}\p{M}]+)*|\p{L}\.)(?: (?:[\p{L}\p{M}]+(?:['’\-][\p{L}\p{M}]+)*|\p{L}\.)){0,5}""")
    private val spacing = Regex("\\s+")
    private val notNameWords = Regex(
        """\b(?:and|then|when|whenever|once|if|after|before|about)\b""",
        RegexOption.IGNORE_CASE
    )
    private val callLanguage = Regex(
        """^(?:back|later|now|again|tomorrow|tonight|today|at|on|from|in|with|via|using|a|an|the)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(message: String): String? {
        if ('\n' in message || '\r' in message || message.length > 220) return null
        val match = request.matchEntire(message.trim().replace(spacing, " ")) ?: return null
        val (verb, tail) = match.destructured
        val literal = tail.trim().trimEnd('!', '.', '?').replace(suffix, "").trim()
        val quoted = literal.length > 1 && when (literal.first()) {
            '"' -> literal.last() == '"'
            '“' -> literal.last() == '”'
            '\'' -> literal.last() == '\''
            '‘' -> literal.last() == '’'
            else -> false
        }
        val value = if (quoted) literal.substring(1, literal.length - 1).trim() else literal
        if (value.length !in 1..60 || !name.matches(value)) return null
        if (!quoted && (notNameWords.containsMatchIn(value) ||
                verb.equals("call me", ignoreCase = true) && callLanguage.containsMatchIn(value))) return null
        return value
    }

    fun save(existing: List<String>, name: String, limit: Int): List<String> =
        (existing.filterNot { it.startsWith(NOTE_PREFIX, ignoreCase = true) } + "$NOTE_PREFIX$name").takeLast(limit)
}
