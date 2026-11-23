package com.phoneassistant

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When a scheduled task is due, decided from the words the user used. Times without a
 * period land where people mean them: "at 5" is the afternoon one, "tonight at 8" is the
 * evening one, and a time that has already passed today means tomorrow. A recurring time
 * ("every day at 8am", "weekdays at 7", "every friday at 8pm", "at 8pm every day") carries
 * its pattern in the same words: the parse yields the first occurrence and the pattern
 * that finds the ones after it. The clock is the only truth: every result is one
 * wall-clock deadline, stored and compared as a number.
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

    /** The words that make a time recurring, and which days it lands on. */
    private val repeatWord = Regex(
        """\b(every\s+day|daily|nightly|every\s+(?:morning|afternoon|evening|night)|""" +
            """weekdays?|every\s+weekday|weekends?|every\s+weekend|every\s+week|""" +
            """every\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?|""" +
            """(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s)\b""",
        RegexOption.IGNORE_CASE)

    /** A part of a day said around the time ("at 7 every friday morning"). */
    private val partOfDay = Regex("""\b(?:morning|afternoon|evening|night)\b""", RegexOption.IGNORE_CASE)

    /** The words that may sit between a time's own parts without being task words. */
    private val bridge = Regex(
        """\s*(?:on|every|each|morning|afternoon|evening|night)(?:\s+(?:on|every|each|morning|afternoon|evening|night))*\s*""",
        RegexOption.IGNORE_CASE)

    private val timeOfDay = Regex(
        """\bat\s+(noon|midnight|([0-9]{1,2})(?::([0-9]{2}))?\s*(am|pm)?)\b""",
        RegexOption.IGNORE_CASE)

    /** A bare clock word ("5pm", "8am", "17:30") that never means an ordinary number. */
    private val bareClock = Regex(
        """(?<![\p{L}\p{N}])([0-9]{1,2}(?::[0-9]{2})?\s*(?:am|pm)|noon|midnight)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE)

    private val weekday = Regex(
        """\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        RegexOption.IGNORE_CASE)

    private val morningWord = Regex("""\b(?:this\s+|every\s+)?morning\b""", RegexOption.IGNORE_CASE)
    private val eveningWord = Regex(
        """\b(?:tonight|(?:this|every)\s+(?:afternoon|evening|night)|nightly)\b""", RegexOption.IGNORE_CASE)

    /** The span of words that carries the time, for cutting the request around it. */
    fun expression(text: String): IntRange? {
        val time = timeOfDay.find(text) ?: return inAWhile.find(text)?.range
        val inSpan = inAWhile.find(text)
        if (inSpan != null && inSpan.range.first < time.range.first) return inSpan.range
        // A day or repeat word belongs to the time when it is up against it, in front
        // ("tomorrow at 9", "every day at 8") or behind ("at 8pm tomorrow", "at 8pm every
        // day"), with only connective words between. When both say something ("every
        // friday"), the repeat word speaks for both.
        val repeat = repeatWord.find(text)?.takeIf { against(text, it.range, time.range) }
        val day = dayWord.find(text)?.takeIf { against(text, it.range, time.range) }
        val around = repeat ?: day
        val first = minOf(around?.range?.first ?: time.range.first, time.range.first)
        var last = maxOf(around?.range?.last ?: time.range.last, time.range.last)
        // A part of the day just past the words ("at 7 every friday morning") says which
        // seven o'clock; it belongs to the hour even when it trails.
        while (true) {
            val tail = partOfDay.find(text, last + 1) ?: break
            if (!text.substring(last + 1, tail.range.first).isBlank()) break
            last = tail.range.last
        }
        return first..last
    }

    /** The repeat words of a sentence, wherever they sit. */
    fun pattern(text: String): MatchResult? = repeatWord.find(text)

    private fun against(text: String, a: IntRange, b: IntRange): Boolean {
        val mid = if (a.last < b.first) text.substring(a.last + 1, b.first)
        else if (b.last < a.first) text.substring(b.last + 1, a.first)
        else return false
        return mid.isBlank() || bridge.matches(mid)
    }

    /** The first wall-clock deadline and its pattern, or null for words with no usable time. */
    fun parse(text: String, now: Long): PipTask.When? = parseWords(text, now) ?:
        // The brain hands back bare clock words ("5pm", "every day 8am"); sentences carry
        // their own "at", so a bare one gets it here.
        parseWords(bareClock.replace(text) { "at ${it.value}" }, now)

    private fun parseWords(text: String, now: Long): PipTask.When? {
        val range = expression(text) ?: return null
        val span = text.substring(range)
        if (inAWhile.find(span)?.value.equals(span.trim(), ignoreCase = true)) {
            return PipTask.When(now + (TimedRequest.numericDuration(span) ?: return null), PipTask.Repeat.NONE)
        }
        val time = timeOfDay.find(span) ?: return null

        val at = Calendar.getInstance().apply { timeInMillis = now }
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
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
            morningWord.containsMatchIn(span) ->
                (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 0 else it }
            eveningWord.containsMatchIn(span) ->
                (hourWord.toIntOrNull() ?: return null).let { if (it == 12) 12 else it + 12 }
            else -> (hourWord.toIntOrNull() ?: return null).let { if (it in 1..11) it + 12 else it }
        }
        if (minutes > 59 || hour > 23) return null
        at.set(Calendar.HOUR_OF_DAY, hour)
        at.set(Calendar.MINUTE, minutes)

        val repeat = repeatOf(span)
        if (repeat != null) {
            // A recurring time lands on its weekday when it names one, then repeats from
            // the first occurrence that is still ahead. "Every week" names none and takes
            // whatever day the clock words sit on.
            if (repeat == PipTask.Repeat.WEEKLY) {
                val target = weekdayTarget(span)
                if (target >= 0) {
                    at.add(Calendar.DAY_OF_MONTH, -((at.get(Calendar.DAY_OF_WEEK) - target + 7) % 7))
                }
            }
            return PipTask.When(next(at.timeInMillis, repeat, now), repeat)
        }

        val day = dayWord.find(span)
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
        // A time already gone rolls to its next real occurrence: tomorrow's five, or next
        // week's Friday. "Tomorrow at nine" can never need this.
        if (at.timeInMillis <= now) {
            at.add(Calendar.DAY_OF_MONTH, if (day != null && weekday.containsMatchIn(day.value)) 7 else 1)
        }
        return PipTask.When(at.timeInMillis, PipTask.Repeat.NONE)
    }

    /** The next occurrence of a recurring time: strictly after [now], and on its days. */
    fun next(fireAt: Long, repeat: PipTask.Repeat, now: Long): Long {
        if (repeat == PipTask.Repeat.NONE) return fireAt
        var at = fireAt
        val cal = Calendar.getInstance()
        // Steps are whole days or weeks, so wall-clock times survive daylight saving.
        while (true) {
            cal.timeInMillis = at
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val offDay =
                (repeat == PipTask.Repeat.WEEKDAYS && (day == Calendar.SATURDAY || day == Calendar.SUNDAY)) ||
                (repeat == PipTask.Repeat.WEEKENDS && day != Calendar.SATURDAY && day != Calendar.SUNDAY)
            if (at > now && !offDay) return at
            cal.add(Calendar.DAY_OF_MONTH, if (repeat == PipTask.Repeat.WEEKLY) 7 else 1)
            at = cal.timeInMillis
        }
    }

    private fun repeatOf(span: String): PipTask.Repeat? {
        val word = repeatWord.find(span)?.value?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
            ?: return null
        return when {
            word == "daily" || word == "every day" || word == "nightly" ||
                word.startsWith("every morning") || word.startsWith("every afternoon") ||
                word.startsWith("every evening") || word.startsWith("every night") ->
                PipTask.Repeat.DAILY
            word.startsWith("weekday") || word.endsWith("weekday") -> PipTask.Repeat.WEEKDAYS
            word.startsWith("weekend") || word.endsWith("weekend") -> PipTask.Repeat.WEEKENDS
            else -> PipTask.Repeat.WEEKLY
        }
    }

    private fun weekdayTarget(span: String): Int {
        val word = repeatWord.find(span)?.value?.lowercase(Locale.ROOT) ?: return -1
        return when {
            word.contains("monday") -> Calendar.MONDAY
            word.contains("tuesday") -> Calendar.TUESDAY
            word.contains("wednesday") -> Calendar.WEDNESDAY
            word.contains("thursday") -> Calendar.THURSDAY
            word.contains("friday") -> Calendar.FRIDAY
            word.contains("saturday") -> Calendar.SATURDAY
            word.contains("sunday") -> Calendar.SUNDAY
            else -> -1
        }
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

    /** "every day at 8:00 AM" or "today at 5:00 PM" — how the due time is said out loud. */
    fun dueSpoken(fireAt: Long, repeat: PipTask.Repeat, now: Long): String {
        if (repeat == PipTask.Repeat.NONE) return whenSpoken(fireAt, now)
        val clock = SimpleDateFormat("h:mm a", Locale.ROOT).format(Date(fireAt))
        val pattern = when (repeat) {
            PipTask.Repeat.DAILY -> "every day"
            PipTask.Repeat.WEEKDAYS -> "weekdays"
            PipTask.Repeat.WEEKENDS -> "weekends"
            PipTask.Repeat.WEEKLY -> "every " + SimpleDateFormat("EEEE", Locale.ROOT).format(Date(fireAt))
            PipTask.Repeat.NONE -> ""
        }
        return "$pattern at $clock"
    }
}
