package com.phoneassistant

/** Only a wake word at the start of a recognized utterance can open a capture window. */
internal class WakePhrase {
    private val prefix = Regex("""^(?:(?:hey|okay|ok)[, ]+)?pip(?=$|[\s,.!?;:])""", RegexOption.IGNORE_CASE)
    private var until = 0L
    val awaiting get() = until != 0L

    fun expire(now: Long): Boolean {
        if (!awaiting || now < until) return false
        reset()
        return true
    }

    fun accept(transcript: String, now: Long): String? {
        expire(now)
        val text = transcript.trim()
        if (text.isEmpty()) return null
        val wake = prefix.find(text)
        if (wake == null && !awaiting) return null
        val message = if (wake == null) text else text.substring(wake.range.last + 1)
            .trimStart(' ', '\t', '\n', ',', '.', '!', '?', ';', ':')
        if (message.isBlank()) {
            until = now + 8_000L
            return null
        }
        reset()
        return message.take(600).trimEnd { it.isHighSurrogate() }.takeIf { it.any(Char::isLetterOrDigit) }
    }

    fun reset() { until = 0L }
}
