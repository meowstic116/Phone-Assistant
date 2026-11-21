package com.phoneassistant

/** Limit recorder recreation if Android repeatedly supplies no capture or kills the stream. */
internal class WakeCaptureRecovery {
    private val attempts = ArrayDeque<Long>()

    fun tryRestart(now: Long): Boolean {
        while (attempts.firstOrNull()?.let { now - it >= 30_000L } == true) attempts.removeFirst()
        if (attempts.size >= 2) return false
        attempts.addLast(now)
        return true
    }

    fun stalled(now: Long, lastPcmAt: Long) = now - lastPcmAt >= 4_000L
}
