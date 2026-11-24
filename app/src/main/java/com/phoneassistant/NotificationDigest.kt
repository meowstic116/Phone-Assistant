package com.phoneassistant

import java.util.Locale

/** The words for "read me my notifications", matched on the phone with no model. */
internal object NotificationDigest {
    private val spaces = Regex("\\s+")

    /** Asking shapes: reads and questions about notifications, never actions on them. */
    private val read = Regex(
        "^(?:(?:hey )?pip[, ]+)?(?:please )?(?:(?:can|could|would) (?:you )?)?" +
            "(?:read|tell me|sing)(?: me)?(?: (?:out|aloud|to me))?" +
            "(?: (?:my|the|these|all(?: my)?|of my|any))?" +
            "(?: (?:unread|new|recent|pending|phone'?s?))* notifications?" +
            "(?: (?:out loud|aloud|to me|for me))?$",
        RegexOption.IGNORE_CASE)
    private val ask = Regex(
        "^(?:(?:hey )?pip[, ]+)?(?:please )?(?:(?:can|could|would) (?:you )?)?" +
            "(?:what(?:'s| is| are| do)?|do i have|have i got|any)" +
            "(?: my| the| these| any| of my| phone'?s?| unread| new| recent| pending)*" +
            " notifications?(?: do i have)?(?: (?:out loud|aloud|to me|for me))?$",
        RegexOption.IGNORE_CASE)

    /** True when the words ask for the notifications read out, and nothing more. */
    fun request(text: String): Boolean {
        val line = text.trim().trimEnd('?', '.', '!').replace(spaces, " ").lowercase(Locale.ROOT)
            .removeSuffix(" please")
        return read.matches(line) || ask.matches(line)
    }
}
