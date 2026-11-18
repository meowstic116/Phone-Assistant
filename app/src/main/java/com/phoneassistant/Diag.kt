package com.phoneassistant

import android.os.SystemClock

/**
 * What has gone wrong lately, kept so the diagnostics screen can show it.
 *
 * Two kinds of failure live here: a request the brain refused or never completed, which the
 * user already sees in a bubble, and an exception that was swallowed to keep the overlay
 * alive, which nobody saw. Recording the second kind is the point — a frame that throws every
 * 33 ms used to be invisible from the inside and from the outside.
 *
 * It also keeps the last phone job, step by step. That is not a failure in itself, but it is
 * what makes one readable: a job that goes wrong says one sentence in a bubble and stops, and
 * from the outside that is indistinguishable from a job that did nothing at all.
 *
 * Everything is in memory only. This is a readout, not a log the app depends on.
 */
object Diag {

    /** One failure: what it was, when it first happened, and how many times since. */
    data class Event(val text: String, val at: Long, val count: Int = 1)

    /** The last refused or failed request. Null until the brain has been asked something. */
    @Volatile
    var lastApiError: Event? = null
        private set

    /** When a reply last came back successfully, on the uptime clock. */
    @Volatile
    var lastApiOkAt = 0L
        private set

    /** The last exception nobody reported, and a count if it keeps happening. */
    @Volatile
    var lastCaught: Event? = null
        private set

    /** One thing a job tried: what it did, how that went, and whether Android took it. */
    data class Step(val text: String, val outcome: String, val ok: Boolean)

    /**
     * The last phone job from the words that asked for it to the line that ended it.
     *
     * [id] is the job's ticket. A job that has been replaced must not write into the record
     * of the one that replaced it, so every write names its own — a cancelled job can still
     * be finishing its last API call while the next one is already working.
     */
    data class Job(
        val id: Int,
        val request: String,
        val startedAt: Long,
        val steps: List<Step> = emptyList(),
        val reason: String? = null,
        val endedAt: Long = 0L
    ) {
        val running: Boolean get() = reason == null
    }

    /** The last job, live while it runs and left in place afterwards. Null until one starts. */
    @Volatile
    var lastJob: Job? = null
        private set

    /** A job is beginning; the previous record is replaced rather than added to. */
    fun jobStarted(id: Int, request: String) {
        lastJob = Job(id, oneLine(request, 200), now())
    }

    /** One step, as it happened, with what it was aimed at and what came back. */
    fun jobStep(id: Int, text: String, outcome: String, ok: Boolean) {
        val current = lastJob ?: return
        if (current.id != id) return
        lastJob = current.copy(
            steps = current.steps + Step(oneLine(text, 120), oneLine(outcome, 120), ok)
        )
    }

    /** Why the job stopped. The first reason wins: only one thing ends a job. */
    fun jobEnded(id: Int, reason: String) {
        val current = lastJob ?: return
        if (current.id != id || !current.running) return
        lastJob = current.copy(reason = oneLine(reason, 200), endedAt = now())
    }

    /** The brain refused, or could not be reached at all. [detail] is cut to fit one screen. */
    fun apiFailed(what: String, detail: String) {
        val clean = detail.replace("\n", " ").trim().take(200)
        lastApiError = Event(if (clean.isEmpty()) what else "$what · $clean", now())
    }

    fun apiOk() {
        lastApiOkAt = now()
    }

    /**
     * An exception nothing is going to show the user. A repeat of the same one keeps the first
     * time — that is when it started — and counts up, so a per-frame failure reads as ×900
     * rather than as one event that keeps changing hands.
     */
    fun caught(what: String, t: Throwable) {
        val text = describe(what, t)
        val previous = lastCaught
        lastCaught = if (previous != null && previous.text == text) {
            previous.copy(count = previous.count + 1)
        } else {
            Event(text, now())
        }
    }

    /**
     * "just now", "8s ago", "3m ago" — enough to tell a live fault from an old one. Shared
     * with the floating readout, which has to show the same age for the same event.
     */
    fun ago(at: Long): String {
        if (at <= 0L) return "never"
        val ms = SystemClock.elapsedRealtime() - at
        return when {
            ms < 1_500L -> "just now"
            ms < 60_000L -> "${ms / 1000}s ago"
            ms < 3_600_000L -> "${ms / 60_000}m ago"
            else -> "${ms / 3_600_000}h ago"
        }
    }

    private fun describe(what: String, t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name.substringAfterLast('.') }
        val message = t.message?.replace("\n", " ")?.trim()?.take(140).orEmpty()
        return if (message.isEmpty()) "$what · $name" else "$what · $name: $message"
    }

    private fun oneLine(text: String, max: Int): String =
        text.replace("\n", " ").trim().take(max)

    private fun now() = SystemClock.elapsedRealtime()
}
