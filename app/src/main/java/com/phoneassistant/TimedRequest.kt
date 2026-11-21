package com.phoneassistant

/** Parse numeric holds and breaks without overflowing the stored wall-clock deadline. */
internal object TimedRequest {
    const val MAX_MS = 30L * 24 * 60 * 60 * 1_000
    private val numeric = Regex("""(?<![\w.+-])([0-9]+(?:\.[0-9]+)?)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""",
        RegexOption.IGNORE_CASE)

    fun numericDuration(text: String): Long? {
        val match = numeric.find(text) ?: return null
        val unitMs = when (match.groupValues[2][0].lowercaseChar()) {
            's' -> 1_000L
            'h' -> 3_600_000L
            else -> 60_000L
        }
        val amount = match.groupValues[1].toBigDecimalOrNull() ?: return null
        return (amount * unitMs.toBigDecimal()).min(MAX_MS.toBigDecimal()).toLong()
    }
}
