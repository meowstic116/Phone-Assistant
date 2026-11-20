package com.phoneassistant

/** Only an address at the start of an utterance (or an explicit Speak now tap) opens a capture window. */
internal class WakePhrase {
    private val separators = "[\\s\\p{Z},.!?;:–—-]"
    private val prefix = Regex("""^(?:(?:hey|okay|ok|yo)$separators+(?:p[. ]*i[. ]*p|pipp|peep)|p[. ]*i[. ]*p)(?=$|$separators)""",
        RegexOption.IGNORE_CASE)
    private val leading = Regex("""^[\s\p{Z}"'“‘]+""")
    private var until = 0L
    val awaiting get() = until != 0L

    private fun cleaned(transcript: String) = leading.replaceFirst(transcript, "").trimEnd()

    fun hasAddress(transcript: String) = prefix.containsMatchIn(cleaned(transcript))

    fun arm(now: Long) { until = now + 10_000L }

    fun expire(now: Long): Boolean {
        if (!awaiting || now < until) return false
        reset()
        return true
    }

    fun accept(transcript: String, now: Long): String? {
        expire(now)
        val text = cleaned(transcript)
        if (text.isEmpty()) return null
        val wake = prefix.find(text)
        if (wake == null && !awaiting) return null
        val message = if (wake == null) text else text.substring(wake.range.last + 1)
            .trimStart { it.isWhitespace() || it in ",.!?;:–—-\"'”’" }
        if (message.isBlank()) {
            arm(now)
            return null
        }
        reset()
        return message.take(600).trimEnd { it.isHighSurrogate() }.takeIf { it.any(Char::isLetterOrDigit) }
    }

    fun reset() { until = 0L }
}
