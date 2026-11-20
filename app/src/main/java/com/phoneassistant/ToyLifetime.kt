package com.phoneassistant

/** One idle clock per toy, including toys made of several windows. Uses uptime. */
internal class ToyLifetime(startedAt: Long) {
    private var expiresAt = startedAt + 15_000L

    fun tapped(now: Long) {
        expiresAt = now + 60_000L
    }

    /** A held toy may finish its gesture, but simply holding or moving it adds no time. */
    fun expired(now: Long, held: Boolean): Boolean = !held && now >= expiresAt
}
