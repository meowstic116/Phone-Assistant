package com.phoneassistant

/** A reply has a text presentation even when speech never starts. Offsets never move back. */
internal class ReplyBubbleState(val fullLength: Int, spoken: Boolean) {
    var visibleEnd = if (spoken) 0 else fullLength
        private set
    val waiting get() = visibleEnd == 0 && fullLength != 0

    fun reveal(end: Int?): Boolean {
        val next = (end ?: fullLength).coerceIn(0, fullLength)
        if (next <= visibleEnd) return false
        visibleEnd = next
        return true
    }
}
