package com.phoneassistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** The task worker waits for the UI to park Pip; Stop and timeouts never authorize an action. */
internal class JobApproachGate {
    private val completed = CountDownLatch(1)
    private val result = AtomicReference<Boolean?>(null)

    fun finish(arrived: Boolean) {
        if (result.compareAndSet(null, arrived)) completed.countDown()
    }

    fun await(active: () -> Boolean): Boolean {
        // A stopped/replaced task shouldn't hold up the single worker for the next one.
        return try {
            repeat(80) {
                if (!active()) return false
                if (completed.await(20L, TimeUnit.MILLISECONDS)) return result.get() == true && active()
            }
            finish(false)
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/** A short, frame-driven walk rather than a position snap even across a large display. */
internal class JobApproach(
    private val from: DragMotion.Point,
    val destination: DragMotion.Point,
    density: Float
) {
    private val distance = hypot(destination.x - from.x, destination.y - from.y) / density.coerceAtLeast(0.01f)
    val durationMs = if (distance < 1f) 0L else (distance * 0.9f).toLong().coerceIn(130L, 620L)
    private var elapsedMs = 0L
    val arrived: Boolean get() = elapsedMs >= durationMs

    fun advance(frameMs: Long): DragMotion.Point {
        elapsedMs += frameMs.coerceIn(0L, 50L)
        if (arrived) return destination
        val t = elapsedMs.toFloat() / durationMs
        // Ease in and out a little while keeping the middle of the walk brisk.
        val eased = t * t * (3f - 2f * t)
        return DragMotion.Point(from.x + (destination.x - from.x) * eased, from.y + (destination.y - from.y) * eased)
    }
}
