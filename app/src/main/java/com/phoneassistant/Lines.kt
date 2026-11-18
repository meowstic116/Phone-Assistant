package com.phoneassistant

/**
 * Picks a canned line, never the same one twice in a row. His lines are short now, so hearing
 * the identical sentence again reads like a bug rather than a running gag.
 */
object Lines {

    /** The last line handed out, so the next pick can avoid it. */
    private var last: String? = null

    fun pick(lines: List<String>): String {
        // With one option there is nothing to swap it for, so a lone line is said again.
        val pool = if (lines.size > 1) lines.filter { it != last } else lines
        return pool.random().also { last = it }
    }
}
