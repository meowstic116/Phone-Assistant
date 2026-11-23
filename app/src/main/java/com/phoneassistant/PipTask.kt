package com.phoneassistant

/**
 * One stored thing Pip does on his own. An automatic task stands until it is deleted from
 * the tasks menu, waiting for its trigger to show on the screen; a scheduled task runs
 * once at its wall-clock time and is then gone. Nothing here reads the clock or the
 * screen: the store keeps numbers, the runner decides when they are due.
 */
internal data class PipTask(
    val id: String,
    val kind: Kind,
    val detail: String,
    val trigger: String,
    val app: String,
    val fireAt: Long,
    val createdAt: Long,
    val repeat: Repeat = Repeat.NONE,
    val announced: Boolean = false
) {
    enum class Kind { AUTOMATIC, SCHEDULED }

    /** Which days a scheduled time lands on again. NONE runs once. */
    enum class Repeat { NONE, DAILY, WEEKLY, WEEKDAYS, WEEKENDS }

    /** The first deadline a time's words resolve to, and the pattern after it. */
    data class When(val fireAt: Long, val repeat: Repeat)

    /** One line of storage: tab-separated fields, kept single-line by [clean]. */
    fun encode(): String =
        listOf(id, kind.name, detail, trigger, app, fireAt.toString(), createdAt.toString(),
            repeat.name, if (announced) "1" else "0").joinToString("\t")

    companion object {
        const val MAX_FIELD = 300

        fun clean(value: String) = value.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_FIELD)

        fun newId(now: Long, salt: Int) =
            "t" + now.toString(36) + "-" + (salt and 0xFFFF).toString(36)

        fun decode(line: String): PipTask? {
            val parts = line.split("\t")
            if (parts.size !in 8..9 || parts[0].isEmpty()) return null
            val kind = runCatching { Kind.valueOf(parts[1]) }.getOrNull() ?: return null
            val fireAt = parts[5].toLongOrNull() ?: return null
            val createdAt = parts[6].toLongOrNull() ?: return null
            val repeat = if (parts.size == 8) Repeat.NONE
            else runCatching { Repeat.valueOf(parts[7]) }.getOrNull() ?: return null
            val announced = parts[if (parts.size == 8) 7 else 8] == "1"
            return PipTask(parts[0], kind, parts[2], parts[3], parts[4], fireAt, createdAt, repeat, announced)
        }

        /** A request becomes a stored task; scheduled ones resolve their words into a time. */
        fun from(request: TaskCommand.Request, now: Long, salt: Int): PipTask? {
            val detail = clean(request.detail)
            if (detail.isEmpty()) return null
            return when (request) {
                is TaskCommand.Request.Schedule -> {
                    val due = TaskClock.parse(request.whenText, now) ?: return null
                    PipTask(newId(now, salt), Kind.SCHEDULED, detail, "", "", due.fireAt, now, due.repeat)
                }
                is TaskCommand.Request.Automate -> {
                    val trigger = clean(request.trigger)
                    if (trigger.isEmpty()) return null
                    PipTask(newId(now, salt), Kind.AUTOMATIC, detail, trigger, clean(request.app), 0L, now)
                }
            }
        }
    }
}
