package com.phoneassistant

/** One message of the on-device conversation history. */
data class Msg(val role: String, val content: String)

/**
 * The pure rules of the rolling chat window: how long a message may be, and which
 * messages travel with the next request. Split out of [Memory] so the host tests in
 * `tests/` can compile the rules without Android.
 */
internal object ChatWindow {

    /** How much conversation is kept and shown: 25 from the user, 25 from Pip. */
    const val MAX_MESSAGES = 50
    /** How much of it travels with each model request — cost per reply, not memory. */
    const val CONTEXT_MESSAGES = 24
    const val MAX_MESSAGE_CHARS = 4096

    /**
     * Caps a message and trims a trailing high surrogate, so a message cut through a
     * surrogate pair is never sent half a character.
     */
    fun bounded(content: String): String =
        if (content.length <= MAX_MESSAGE_CHARS) content
        else content.take(MAX_MESSAGE_CHARS).trimEnd { it.isHighSurrogate() }

    /**
     * The messages to send with `current`: the stored window plus the new turn. Sending
     * the current text must not depend on a successful local history write, and asking
     * twice in a row must not append the same turn twice.
     */
    fun forReply(history: List<Msg>, current: String): List<Msg> {
        val latest = Msg("user", bounded(current))
        return if (history.lastOrNull() == latest) history
        else history.takeLast(CONTEXT_MESSAGES - 1) + latest
    }
}
