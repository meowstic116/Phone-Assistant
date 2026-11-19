package com.phoneassistant

import java.util.concurrent.atomic.AtomicLong

/** Both chat surfaces share an epoch; a late reply must not become a new phone job. */
internal object ChatTurns {
    private val latest = AtomicLong()
    fun begin(): Long = latest.incrementAndGet()
    fun current(): Long = latest.get()
    fun isCurrent(turn: Long): Boolean = latest.get() == turn
}

/** A job ID and its running flag change together. Callbacks may arrive after completion. */
internal class JobLifetime {
    private var generation = 0
    private var active: Int? = null

    @Synchronized fun start(): Int? {
        if (active != null) return null
        return (++generation).also { active = it }
    }

    @Synchronized fun running(): Boolean = active != null
    @Synchronized fun active(id: Int): Boolean = active == id
    @Synchronized fun current(id: Int): Boolean = generation == id

    @Synchronized fun complete(id: Int): Boolean {
        if (active != id) return false
        active = null
        return true
    }

    @Synchronized fun cancel(): Int? {
        val previous = active
        active = null
        // Invalidate already-posted delivery, even if the worker just finished.
        generation++
        return previous
    }
}
