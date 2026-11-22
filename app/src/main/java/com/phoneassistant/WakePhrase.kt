package com.phoneassistant

/** Decide locally whether speech addresses Pip. Never send ambient speech to the chat API to decide. */
internal class WakePhrase {
    private val separators = "[\\s\\p{Z},.!?;:–—-]"
    private val name = "p[. ]*i[. ]*p"
    private val greeting = "(?:hey|okay|ok|yo)$separators+(?:$name|pipp|peep|pep|pips)"
    private fun pattern(value: String) = Regex(value, RegexOption.IGNORE_CASE)
    private val prefix = pattern("^(?:$greeting|$name)(?=$|$separators|[…\"”]|[’'](?=$|$separators))")
    private val suffix = pattern("$separators+(?:$greeting|$name)([.?!…]*)[\\s\\p{Z}\"'”’]*$")
    // An explicit voice address can also interrupt a sentence: "Can you, Pip, tell me another?"
    private val inset = pattern("\\s*[,–—]\\s*(?:$greeting|$name)\\s*[,–—]\\s*")
    private val leading = Regex("""^[\s\p{Z}"'“‘]+""")
    private val mention = pattern("\\b(?:about|to|with|of|for|from|at|by|in|on|as|is|was|than|like|love|hate|called|named|call|name|ask|asked|tell|told|saw|see|heard|hear|find|found|met|meet|know|knows|remember|miss|watch|watching|use|using|thank|thanked|thinks|mentions|mention|mentioned)\\s*$")
    private val reported = pattern("""\b(?:said|says|wrote|reads|quoted)\b.*""")
    private val committed = pattern("\\b(?:as|like|than|was|is)\\s+$")
    // The name can land anywhere in a sentence, even without the commas a vocative would
    // normally get: "could you pip turn the music down". Match whole words only, so
    // "pipe", "piper", and "Pip's" are not mistaken for an address.
    private val anywhere = pattern("(?<![\\p{L}\\p{N}])$name(?![\\p{L}\\p{N}])")
    // Decoders often write his name as "Peep", "Pep", or "Pipp". Accept those at the start
    // of a request ("Peep, pause Spotify") but not as ordinary words ("peep the window").
    private val variantName = "(?:pipp|peep|pep)"
    private val variant = pattern("^$variantName(?=$|$separators|[…\"”])")
    private val objectWord = pattern("^(?:the|a|an|this|that|these|those|my|your|his|her|its|our|their|at|on|in|of|for|to|from|with|and|or|is|was)\\b")
    private val trailingGreeting = pattern("(?:^|[\\s\\p{Z},.!?;:–—-])(?:hey|okay|ok|yo)[\\s\\p{Z},.!?;:–—-]*$")
    // "Pass me the pip" names an object, not the assistant. Greetings, fillers, and
    // questions may still come right before the name.
    private val article = pattern("""\b(?:the|a|an|this|that|my|your|his|her|its|our|their|pipe)\s*$""")
    // The name at the end can also begin the message ("OK Pip, pause it").
    private val leadingGreeting = pattern("(?:hey|okay|ok|yo)[\\s\\p{Z},.!?;:–—-]*$")
    /** True once any live snapshot of the current utterance has named Pip; final results don't. */
    private var named = false
    private var until = 0L
    val awaiting get() = until != 0L

    private fun cleaned(transcript: String) = leading.replaceFirst(transcript, "").trimEnd()
    private fun addressPunctuation(c: Char) = c.isWhitespace() || c in ",.!?;:–—-…\"'”’"

    /** Null is not addressed. An empty result means the name was spoken alone. */
    private fun addressedMessage(text: String): String? {
        prefixCommand(text)?.let { return it }
        // Don't treat a reference or reported quotation as a request to the assistant.
        inset.find(text)?.let {
            val head = text.substring(0, it.range.first).trimEnd()
            val rest = text.substring(it.range.last + 1).trimStart()
            // A comma on both sides of the name marks a vocative even behind "to" or "ask".
            if (head.any(Char::isLetterOrDigit) && rest.any(Char::isLetterOrDigit) && !reported.containsMatchIn(head)) {
                return "$head $rest"
            }
        }
        suffix.find(text)?.let {
            val head = text.substring(0, it.range.first).trimEnd()
            if (head.any(Char::isLetterOrDigit) && !mention.containsMatchIn(head) &&
                !reported.containsMatchIn(head) && !article.containsMatchIn(head)) {
                return head + it.groupValues[1]
            }
        }
        return anywhereMessage(text)
    }

    private fun prefixCommand(text: String): String? {
        prefix.find(text)?.let {
            // Anything after the name is the message. The name leads, so it is an address.
            return text.substring(it.range.last + 1).trimStart(::addressPunctuation)
        }
        val match = variant.find(text) ?: return null
        val rest = text.substring(match.range.last + 1).trimStart(::addressPunctuation)
        val separator = text.getOrNull(match.range.last + 1)
        val vocative = separator == ',' || separator == '–' || separator == '—'
        // "Peep the window" and "some pep in my step" are about a word, not about him.
        if (!vocative && (rest.isEmpty() || objectWord.containsMatchIn(rest))) return null
        return rest
    }

    /** The name mid-sentence, with any greeting and punctuation at the seam removed. */
    private fun anywhereMessage(text: String): String? {
        val match = anywhere.find(text) ?: return null
        val head = text.substring(0, match.range.first)
        val tail = text.substring(match.range.last + 1)
        if (tail.startsWith("'") || tail.startsWith("’")) return null // Pip's next move
        // The name is a noun here, not an address. Commas mark a vocative even behind "ask"
        // ("ask Pip, what time is it?").
        val commaAfter = tail.firstOrNull()?.let { it == ',' || it == '–' || it == '—' } == true
        if (!commaAfter && (mention.containsMatchIn(head) || committed.containsMatchIn(head) ||
                reported.containsMatchIn(head))) return null
        // Nothing after the name and an article before it: "pass me the pip" isn't an address.
        if (tail.isBlank() && article.containsMatchIn(head)) return null
        val left = leadingGreeting.replace(trailingGreeting.replace(head.trimEnd(), ""), "")
            .trimEnd(::addressPunctuation)
        val right = tail.trimStart(::addressPunctuation)
        return if (left.isBlank()) right else if (right.isBlank()) left else "$left $right"
    }

    /**
     * A live snapshot that names Pip authorizes the rest of the same utterance. That keeps
     * the request when the decoder drops the name from its final result, or revises the
     * command after briefly transcribing the address. Completed results never carry over.
     */
    fun observePartial(transcript: String): Boolean {
        val addressed = addressedMessage(cleaned(transcript)) != null
        if (addressed) named = true
        return addressed
    }

    private fun clearPartial() {
        named = false
    }

    fun hasAddress(transcript: String) = addressedMessage(cleaned(transcript)) != null

    fun arm(now: Long) {
        clearPartial()
        until = now + 10_000L
    }

    fun expire(now: Long): Boolean {
        if (!awaiting || now < until) return false
        reset()
        return true
    }

    fun accept(transcript: String, now: Long): String? {
        expire(now)
        val text = cleaned(transcript)
        val heardName = named
        clearPartial()
        if (text.isEmpty()) return null
        val addressed = addressedMessage(text)
        // An explicit name anywhere in the segment, or a name heard just before this
        // utterance, is enough; the rest of what he heard is the message.
        val message = addressed ?: text.takeIf { awaiting || heardName } ?: return null
        if (message.isBlank()) {
            arm(now)
            return null
        }
        reset()
        return message.take(600).trimEnd { it.isHighSurrogate() }.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /**
     * In a car a hands-free command can arrive without his name. [CarVoice.unaddressedCommand]
     * decides which ones; this only makes the message presentable.
     */
    fun acceptUnaddressed(transcript: String): String? {
        val text = cleaned(transcript)
        if (text.isEmpty()) return null
        reset()
        return text.take(300).trimEnd { it.isHighSurrogate() }.takeIf { it.any(Char::isLetterOrDigit) }
    }

    fun reset() {
        clearPartial()
        until = 0L
    }
}
