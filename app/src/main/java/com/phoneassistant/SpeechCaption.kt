package com.phoneassistant

/** Maps speech-engine offsets to the displayed reply, including unspoken stage directions. */
internal class SpeechCaption private constructor(val text: String, private val words: List<Word>, private val weight: Int) {
    private data class Word(val spokenStart: Int, val spokenEnd: Int, val displayEnd: Int, val startsAt: Int)

    fun displayEndForRange(start: Int, end: Int): Int =
        words.lastOrNull { it.spokenStart < end && it.spokenEnd > start }?.displayEnd ?: 0

    fun displayEndForProgress(progress: Float): Int {
        // At zero the player is still priming; no spoken frame has passed yet.
        if (!progress.isFinite() || progress <= 0f) return 0
        val position = weight * progress.coerceAtMost(1f)
        return words.lastOrNull { it.startsAt <= position }?.displayEnd ?: 0
    }

    val estimatedDurationMs: Long get() = (weight * 65L).coerceAtLeast(350L)

    companion object {
        private val stage = Regex("\\*[^*\\n]+\\*")
        private val token = Regex("\\S+")

        private fun weightOf(word: String): Int = 2 + word.count(Char::isLetterOrDigit).coerceAtMost(14) +
            when (word.lastOrNull()) {
                '.', '!', '?', '…' -> 5
                ',', ':', ';' -> 3
                else -> 0
            }

        /** Pass message text to speech exactly as supplied, not through persona parsing. */
        fun literal(text: String, limit: Int): SpeechCaption? =
            text.takeIf { it.isNotBlank() && it.length <= limit }?.let { SpeechCaption(it, emptyList(), 0) }

        fun from(display: String, limit: Int): SpeechCaption? {
            val line = display.trimStart()
            if (line.isEmpty() || line.startsWith('{') || line.startsWith('[') || line.trimEnd() == "(silence)") return null
            // Blank directions without shifting offsets, then rebuild the exact text we send.
            val material = display.toCharArray()
            stage.findAll(display).forEach { match -> material.fill(' ', match.range.first, match.range.last + 1) }
            val text = StringBuilder()
            val words = mutableListOf<Word>()
            var weight = 0
            for (word in token.findAll(String(material))) {
                val space = if (text.isNotEmpty()) 1 else 0
                val available = limit - text.length - space
                if (available <= 0 || (space > 0 && word.value.length > available)) break
                val value = word.value.take(available).trimEnd { it.isHighSurrogate() }
                if (value.isEmpty()) break
                if (space > 0) text.append(' ')
                val start = text.length
                text.append(value)
                words += Word(start, text.length, word.range.first + value.length, weight)
                weight += weightOf(value)
            }
            if (!text.any(Char::isLetterOrDigit)) return null
            return SpeechCaption(text.toString(), words, weight)
        }
    }
}
