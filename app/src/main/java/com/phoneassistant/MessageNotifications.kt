package com.phoneassistant

import java.util.Locale

/** Match and quote notification previews literally. Neither is a model or phone-action input. */
internal object MessageNotifications {
    private val spaces = Regex("\\s+")
    private val question = Regex(
        """^(?:(?:hey )?pip[, ]+)?(?:please )?(?:(?:can|could|would) you tell me )?what did (.{1,100}?) (?:text|message|send) me(?: (?:last|most recently|just now|please))?$""",
        RegexOption.IGNORE_CASE
    )
    private val appSuffix = Regex("""^(.*?)[ ]+(?:on|in)[ ]+(.{1,60})$""", RegexOption.IGNORE_CASE)
    data class Request(val sender: String, val app: String?)

    fun request(text: String): Request? {
        val cleaned = text.trim().trimEnd('?', '.', '!').replace(spaces, " ")
        senderRequested(cleaned)?.let { return Request(it, null) }
        val groups = appSuffix.matchEntire(cleaned)?.groupValues ?: return null
        return Request(senderRequested(groups[1]) ?: return null, groups[2])
    }

    private val unusualName = Regex("""[?!.;:\n]|\b(?:and then|and also)\b""", RegexOption.IGNORE_CASE)

    fun senderRequested(request: String): String? {
        val sender = question.matchEntire(request.trim().trimEnd('?', '!', '.').replace(spaces, " "))
            ?.groupValues?.get(1)?.trim().orEmpty()
        return sender.takeIf { it.isNotEmpty() && !unusualName.containsMatchIn(it) }
    }

    data class Preview(val sender: String, val body: String?, val sentAt: Long, val postedAt: Long, val sourceKey: String? = null)

    private fun normalized(name: String) = name.trim().replace(spaces, " ").lowercase(Locale.ROOT)

    sealed interface Match {
        data class Found(val preview: Preview) : Match
        data object Missing : Match
        data object Ambiguous : Match
    }

    fun match(sender: String, previews: List<Preview>): Match {
        val wanted = normalized(sender)
        val exact = previews.filter { normalized(it.sender) == wanted }
        val matches = if (exact.isNotEmpty()) exact else previews.filter {
            normalized(it.sender).substringBefore(' ') == wanted
        }
        if (matches.map { normalized(it.sender) }.distinct().size > 1) return Match.Ambiguous
        return matches.maxWithOrNull(compareBy<Preview> { it.sentAt.takeIf { t -> t > 0 } ?: it.postedAt }
            .thenBy { it.postedAt })?.let(Match::Found) ?: Match.Missing
    }

    fun answer(sender: String, previews: List<Preview>): String = when (val result = match(sender, previews)) {
        Match.Ambiguous -> "More than one sender matches that name. Ask again using their full name as shown in the notification."
        Match.Missing -> "I couldn’t find a message from that sender in your current notifications."
        is Match.Found -> result.preview.let {
            if (it.body.isNullOrBlank()) "I found a notification from ${it.sender}, but it doesn’t include a readable text preview."
            else "From ${it.sender}: “${it.body}”"
        }
    }

    /** Bound third-party preview lengths and strip control/bidi override characters from the quote. */
    fun excerpt(value: CharSequence?, limit: Int): String? {
        if (value == null) return null
        val clipped = value.take(limit).toString().map { ch ->
            when {
                ch.isISOControl() || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' -> ' '
                else -> ch
            }
        }.joinToString("").trimEnd { it.isWhitespace() || Character.isHighSurrogate(it) }.trimStart()
        return clipped.takeIf { it.isNotEmpty() }?.let { if (value.length > limit) "$it…" else it }
    }
}
