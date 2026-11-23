package com.phoneassistant

/**
 * The words that make a task: what the user says to Pip ("remind me at 5pm to stretch",
 * "whenever an ad shows up, skip it") and what the brain answers (its own JSON). Both
 * roads end at the same two requests; anything ambiguous is left to the brain.
 */
internal object TaskCommand {

    sealed class Request {
        abstract val detail: String

        /** One run at a wall-clock time. [whenText] holds the words that say the time. */
        data class Schedule(val whenText: String, override val detail: String) : Request()

        /** Standing watch: when [trigger] shows on screen, do [detail], optionally only in [app]. */
        data class Automate(val trigger: String, override val detail: String, val app: String) : Request()
    }

    private val greeting = Regex("""^(?:hey|hi|ok(?:ay)?|yo)\s+pip[,.!]*\s+""", RegexOption.IGNORE_CASE)
    private val filler = Regex(
        """^(?:please\s+|can you\s+|could you\s+|would you\s+|i want you to\s+|i'd like you to\s+)+""",
        RegexOption.IGNORE_CASE)

    /** "when an ad shows up, skip it" and its cousins. The comma (or "then") splits it. */
    private val condition = Regex(
        """^(?:please\s+)?(?:if|when(?:ever)?|every\s+time)\s+(.+?)\s*(?:[,;]|\s+then\s+|\s+do\s+|\s+and\s+|\s+just\s+)(.+)$""",
        RegexOption.IGNORE_CASE)

    private val connectors = Regex(
        """^(?:remind me to|remind me|to|do|and then|then|and|just|watch for|make sure(?: to)?)\s+""",
        RegexOption.IGNORE_CASE)

    /** A request said straight to Pip, recognized on the phone with no brain call. */
    fun localRequest(text: String): Request? {
        val said = filler.replace(greeting.replace(text.trim(), ""), "").trim()
        if (said.isEmpty()) return null
        automate(said)?.let { return it }
        return schedule(said)
    }

    private fun automate(said: String): Request? {
        val match = condition.find(said) ?: return null
        val trigger = match.groupValues[1].trim().trimEnd('.', '!', '?', ',')
        val detail = connectors.replace(match.groupValues[2].trim(), "").trim()
        if (trigger.isEmpty() || detail.isEmpty()) return null
        return Request.Automate(trigger, detail, "")
    }

    private fun schedule(said: String): Request? {
        val range = TaskClock.expression(said) ?: return null
        val pattern = TaskClock.pattern(said)
        val patternIn = pattern != null &&
            pattern.range.first >= range.first && pattern.range.last <= range.last
        val before = said.substring(0, range.first).trim().trimEnd(',', ';')
        val after = said.substring(range.last + 1).trim().trimStart(',', ';').trim()
        // "at 5pm stretch" and "check the oven at 5pm" are both the whole request;
        // "remind me at 5pm to stretch" puts the task after the time. A pattern with
        // the task between it and the clock ("every day check the oven at 8am") is
        // still part of the time's words, not of the task.
        var detail = if (after.isNotEmpty()) after else before
        if (pattern != null && !patternIn) {
            detail = detail.replaceFirst(pattern.value, "").trim().trimStart(',', ';').trim()
        }
        detail = connectors.replace(detail, "").trim().trimEnd('.', '!', '?', ',')
        if (detail.isEmpty()) return null
        val whenText = if (pattern == null || patternIn) said.substring(range).trim()
        else "${pattern.value} ${said.substring(range).trim()}"
        return Request.Schedule(whenText, detail)
    }

    private fun field(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    /** The brain's task-creation JSON, or null when the line is not one. */
    fun fromReply(line: String): Request? {
        val json = line.trim()
        if (!json.startsWith("{")) return null
        val detail = field(json, "task")?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return null
        return when (field(json, "action")?.lowercase()) {
            "schedule" -> Request.Schedule((field(json, "at") ?: field(json, "when"))?.trim() ?: return null, detail)
            "automate" -> Request.Automate(
                field(json, "trigger")?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return null,
                detail, field(json, "app")?.trim()?.take(80) ?: "")
            else -> null
        }
    }

    /** What Pip says when a task is stored. */
    fun confirmed(task: PipTask, now: Long): String = when (task.kind) {
        PipTask.Kind.SCHEDULED -> "Done. I’ll ${task.detail} ${TaskClock.dueSpoken(task.fireAt, task.repeat, now)}."
        PipTask.Kind.AUTOMATIC -> if (task.app.isNotEmpty()) {
            "Done. Whenever ${task.trigger} in ${task.app}, I’ll ${task.detail}."
        } else "Done. Whenever ${task.trigger}, I’ll ${task.detail}."
    }

    /** What Pip says when a scheduled task is due but the phone is busy. */
    fun deferred(task: PipTask): String =
        "I’m supposed to ${task.detail} right now. I’ll do it when you’re back on the home screen."
}
