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
    private val word = Regex("""[\p{L}\p{N}]+""")
    private var previousPartial: List<String>? = null
    private var stablePartial: List<String>? = null
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
            if (head.any(Char::isLetterOrDigit) && rest.any(Char::isLetterOrDigit) && !mention.containsMatchIn(head) && !reported.containsMatchIn(head)) {
                return "$head $rest"
            }
        }
        suffix.find(text)?.let {
            val head = text.substring(0, it.range.first).trimEnd()
            if (head.any(Char::isLetterOrDigit) && !mention.containsMatchIn(head) && !reported.containsMatchIn(head)) {
                return head + it.groupValues[1]
            }
        }
        return null
    }

    private fun prefixCommand(text: String): String? = prefix.find(text)?.let {
        text.substring(it.range.last + 1).trimStart(::addressPunctuation)
    }

    // A decoder can revise the beginning of a segment just before it ends. Recover only
    // when two decoded snapshots agreed on an address AND the same first two command words.
    // A discarded/revised command or a name on its own cannot unlock unaddressed speech.
    fun observePartial(transcript: String): Boolean {
        val text = cleaned(transcript)
        val address = prefixCommand(text)
        val addressedStart = address?.let(::commandStart)
        if (addressedStart != null && addressedStart == previousPartial) stablePartial = addressedStart
        else if (stablePartial != (addressedStart ?: commandStart(text))) stablePartial = null
        previousPartial = addressedStart
        return address != null
    }

    private fun commandStart(text: String): List<String>? = word.findAll(text).take(2)
        .map { it.value.lowercase(java.util.Locale.ROOT) }.toList().takeIf { it.size == 2 }

    private fun clearPartial() {
        previousPartial = null
        stablePartial = null
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
        val partial = stablePartial
        clearPartial()
        if (text.isEmpty()) return null
        val addressed = addressedMessage(text)
        val message = addressed ?: text.takeIf { awaiting || partial != null && partial == commandStart(it) } ?: return null
        if (message.isBlank()) {
            arm(now)
            return null
        }
        reset()
        return message.take(600).trimEnd { it.isHighSurrogate() }.takeIf { it.any(Char::isLetterOrDigit) }
    }

    fun reset() {
        clearPartial()
        until = 0L
    }
}
