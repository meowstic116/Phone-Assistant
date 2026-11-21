package com.phoneassistant

/** One idle clock per toy, including toys made of several windows. Uses uptime. */
internal class ToyLifetime(startedAt: Long) {
    private var expiresAt = startedAt + 15_000L

    fun tapped(now: Long) {
        keepFor(now, 60_000L)
    }

    fun keepFor(now: Long, duration: Long) {
        expiresAt = maxOf(expiresAt, now + duration)
    }

    /** A held toy may finish its gesture, but simply holding or moving it adds no time. */
    fun expired(now: Long, held: Boolean): Boolean = !held && now >= expiresAt
}

/** Consume an approach exactly once, even if it is interrupted or its prop leaves the stage. */
internal class ToyApproach<T : Any>(val owner: T, complete: (Boolean) -> Unit) {
    private var complete: ((Boolean) -> Unit)? = complete

    fun finish(arrived: Boolean) {
        val callback = complete ?: return
        complete = null
        callback(arrived)
    }
}

/** Time advances with visible play, not during screen-off, gesture ducking, or jobs. */
internal class ToyPlayClock {
    private var lastFrame: Long? = null
    var elapsed = 0L
        private set

    fun frame(now: Long, paused: Boolean): Long {
        val previous = lastFrame
        lastFrame = if (paused) null else now
        if (!paused && previous != null) elapsed += (now - previous).coerceIn(0L, 100L)
        return elapsed
    }
}
