package com.phoneassistant

import kotlin.math.hypot

/** Recent screen-space movement, independent of the moving window's local coordinates. */
internal class DragMotion(private val density: Float) {
    data class Point(val x: Float, val y: Float)
    private data class Sample(val x: Float, val y: Float, val time: Long)
    private val samples = ArrayDeque<Sample>()
    private var lastMovement = 0L
    private var anchor = Point(0f, 0f)
    private var from = Point(0f, 0f)

    fun begin(x: Float, y: Float, left: Float, top: Float, now: Long) {
        samples.clear()
        anchor = Point(x, y)
        from = Point(left, top)
        lastMovement = now
        samples.add(Sample(x, y, now))
    }

    fun beyondSlop(x: Float, y: Float, slop: Float): Boolean =
        hypot(x - anchor.x, y - anchor.y) > slop

    fun target(x: Float, y: Float) = Point(from.x + x - anchor.x, from.y + y - anchor.y)

    fun sample(x: Float, y: Float, now: Long) {
        val recent = samples.last()
        if (hypot(x - recent.x, y - recent.y) < 0.5f || now < recent.time) return
        if (recent.time == now) samples.removeLast()
        samples.add(Sample(x, y, now))
        lastMovement = now
        while (samples.size > 2 && (samples.size > 12 || now - samples[1].time >= HISTORY_MS)) {
            samples.removeFirst()
        }
    }

    fun coast(now: Long): Point? {
        val last = samples.last()
        val first = samples.firstOrNull { last.time - it.time in 12L..HISTORY_MS } ?: return null
        val release = (1f - (now - lastMovement).coerceAtLeast(0L) / 80f).coerceIn(0f, 1f)
        val factor = release * COAST_MS / (3f * (last.time - first.time))
        val dx = (last.x - first.x) * factor
        val dy = (last.y - first.y) * factor
        val distance = hypot(dx, dy)
        if (distance < 3f * density) return null
        val cap = minOf(1f, 52f * density / distance)
        return Point(dx * cap, dy * cap)
    }

    companion object {
        const val COAST_MS = 245L
        private const val HISTORY_MS = 115L

        /** Ease to a full stop without snapping back or overshooting. */
        fun coastFraction(fraction: Float): Float {
            val remainder = 1f - fraction.coerceIn(0f, 1f)
            return 1f - remainder * remainder * remainder
        }
    }
}
