package com.phoneassistant

/** One recent aloud exchange, in RAM only; never reads typed history or saved facts. */
internal class VoiceExchange {
    data class Turn(val input: String, val reply: String)
    private var recent: Turn? = null
    private var expiresAt = 0L

    fun remember(input: String, reply: String, now: Long) {
        recent = Turn(input.take(600), reply.take(600).trimEnd { it.isHighSurrogate() })
        expiresAt = now + 90_000L
    }

    fun takeIfRecent(now: Long): Turn? {
        val turn = recent.takeIf { now < expiresAt }
        clear()
        return turn
    }

    fun clear() {
        recent = null
        expiresAt = 0L
    }
}
