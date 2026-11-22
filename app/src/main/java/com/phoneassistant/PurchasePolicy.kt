package com.phoneassistant

/**
 * The one thing Pip still stops to confirm: a request to spend money. Paying, buying, or
 * checking out is never something he does from a spoken command; he asks the user to do it.
 * Everything else he can do with his hands is run straight away.
 */
internal object PurchasePolicy {
    // Words that only ever mean money, including the spellings a decoder produces.
    private val money = Regex(
        """\b(?:buy|buys|buying|bought|purchase|purchases|purchasing|checkout|check-out|pay|paying|payment|prepay|pre-order|preorder|subscribe|subscription|renew|top[ -]?up|place (?:an|my|the|another|your)? ?order|order (?:me|another|more|a|an|the|some|two|three|four|five|six|\d)|cart|basket)\b""",
        RegexOption.IGNORE_CASE
    )
    // "Check out" is usually looking at something; it is money only with shopping context.
    private val checkOut = Regex("""\bcheck(?:ing)? out\b""", RegexOption.IGNORE_CASE)
    private val shopping = Regex("""\b(?:cart|basket|order|buy|pay|price|total|items?|checkout|shopping|purchase)\b""",
        RegexOption.IGNORE_CASE)
    // Idioms that use the same words without money changing hands.
    private val idiom = Regex(
        """\b(?:buy (?:me )?(?:some|a little) time|buy time|pay attention|paying attention|pay (?:it|no|close) (?:forward|mind)|(?:order|check out) of|in order to|out of order|order(?:ing)? (?:the )?(?:operations|business|alphabetically)|renew (?:our|the|my) (?:conversation|acquaintance))\b""",
        RegexOption.IGNORE_CASE
    )

    /** True when the request is about spending money, so a person must confirm it. */
    fun asksToPay(text: String): Boolean {
        val line = text.trim()
        if (line.isEmpty()) return false
        if (checkOut.containsMatchIn(line) && shopping.containsMatchIn(line) && !idiom.containsMatchIn(line)) return true
        val withoutIdioms = idiom.replace(line, " ")
        return money.containsMatchIn(withoutIdioms)
    }
}
