package com.phoneassistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When a scheduled task is due, decided from the words the user used. Times without a
 * period land where people mean them: "at 5" is the afternoon one, "tonight at 8" is the
 * evening one, and a time that has already passed today means tomorrow. The clock is the
 * only truth: the result is one wall-clock deadline, stored and compared as a number.
 */
internal object TaskClock {

    private const val DAY_MS = 24L * 60 * 60 * 1_000

    /** "in 20 minutes" style, already bounded by TimedRequest. */
    private val inAWhile = Regex(
        """\bin\s+[0-9]+(?:\.[0-9]+)?\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""",
        RegexOption.IGNORE_CASE)

    /** A day word with no time of its own: tomorrow, tonight, a weekday, part of today. */
    private val dayWord = Regex(
        """\b(tomorrow|tonight|this\s+morning|this\s+afternoon|this\s+evening|next\s+week|""" +
            """(?:on\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b""",
        RegexOption.IGNORE_CASE)

    private val timeOfDay = Regex(
        """\bat\s+(noon|midnight|([0-9]{1,2})(?::([0-9]{2}))?\s*(am|pm)?)\b""",
        RegexOption.IGNORE_CASE)

    private val weekday = Regex(
        """\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE)

    /** The span of words that carries the time, for cutting the request around it. */
    fun expression(text: String): IntRange? {
        val time = timeOfDay.find(text) ?: return inAWhile.find(text)?.range
        val inSpan = inAWhile.find(text)
        if (inSpan != null && inSpan.range.first < time.range.first) return inSpan.range
        val day = dayWord.find(text)
        if (day != null && day.range.last < time.range.first &&
            text.substring(day.range.last + 1, time.range.first).isBlank()
        ) return day.range.first..time.range.last
        return time.range
    }

    /** The wall-clock deadline, or null when the words carry no usable time. */
    fun parse(text: String, now: Long): Long? = parseWords(text, now) ?:
        // The brain hands back bare clock words ("5pm", "17:30"); the sentence parser
        // always carries its own "at", so the bare form gets one here.
        parseWords("at $text", now)

    private fun parseWords(text: String, now: Long): Long? {
        val range = expression(text) ?: return null
        val span = text.substring(range)
        if (inAWhile.find(span)?.value.equals(span.trim(), ignoreCase = true)) {
            return now + (TimedRequest.numericDuration(span) ?: return null)
        }
        val day = dayWord.find(span)
        val time = timeOfDay.find(span) ?: return null

        val at = Calendar.getInstance().apply { timeInMillis = now }
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
        when (val word = day?.value?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")) {
            null -> Unit
            "tomorrow" -> at.add(Calendar.DAY_OF_MONTH, 1)
            "next week" -> at.add(Calendar.DAY_OF_MONTH, 7)
            else -> if (word != "tonight" && !word.startsWith("this ")) {
                val target = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                    .indexOfFirst { word.endsWith(it) }
                if (target < 0) return null
                // The next one of that weekday; today still counts when the hour is ahead.
                at.add(Calendar.DAY_OF_MONTH, (target - (at.get(Calendar.DAY_OF_WEEK) - 1) + 7) % 7)
            }
        }

        val hourWord = time.groupValues[2]
        val minutes = time.groupValues[3].toIntOrNull() ?: 0
        val period = time.groupValues[4].lowercase(Locale.ROOT)
        val hour = when {
            time.groupValues[1].equals("noon", true) -> 12
            time.groupValues[1].equals("midnight", true) -> 0
            period == "pm" -> (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 12 else it + 12 }
            period == "am" -> (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 0 else it }
            // No period said: a named morning is the morning, and "at 5" is the afternoon
            // one people ask for. A bare small hour is never two in the morning.
            Regex("""this\s+morning""", RegexOption.IGNORE_CASE).containsMatchIn(span) ->
                (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 0 else it }
            Regex("""tonight|this\s+(?:afternoon|evening)""", RegexOption.IGNORE_CASE).containsMatchIn(span) ->
                (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 12 else it + 12 }
            else -> (hourWord.toIntOrNull() ?: return null).let { if (it in 1..11) it + 12 else it }
        }
        if (minutes > 59 || hour > 23) return null
        at.set(Calendar.HOUR_OF_DAY, hour)
        at.set(Calendar.MINUTE, minutes)
        // A time already gone rolls to its next real occurrence: tomorrow's five, or next
        // week's Friday. "Tomorrow at nine" can never need this.
        if (at.timeInMillis <= now) {
            at.add(Calendar.DAY_OF_MONTH, if (day != null && weekday.containsMatchIn(day.value)) 7 else 1)
        }
        return at.timeInMillis
    }

    /** "today at 5:00 PM", "tomorrow at 9:15 AM", or "Friday at 8:00 PM" for the menu. */
    fun whenSpoken(fireAt: Long, now: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        val clock = SimpleDateFormat("h:mm a", Locale.ROOT)
        val due = day.format(Date(fireAt))
        val prefix = when {
            due == day.format(Date(now)) -> "today"
            due == day.format(Date(now + DAY_MS)) -> "tomorrow"
            fireAt - now in 1..7 * DAY_MS -> SimpleDateFormat("EEEE", Locale.ROOT).format(Date(fireAt))
            else -> SimpleDateFormat("MMM d", Locale.ROOT).format(Date(fireAt))
        }
        return "$prefix at ${clock.format(Date(fireAt))}"
    }
}
