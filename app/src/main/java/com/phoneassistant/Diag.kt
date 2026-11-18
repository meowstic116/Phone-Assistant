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

    private fun now() = SystemClock.elapsedRealtime()
}
