package com.phoneassistant

import kotlin.math.sin

/** Draw-only pickup feedback. Window bounds and finger tracking are unchanged. */
internal object PickupMotion {
    const val SCALE = 1.045f
    const val LIFT_MS = 90L
    const val RELEASE_MS = 130L

    fun blend(from: Float, to: Float, elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return to
        val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * t * (2f - t)
    }

    data class Pose(val scale: Float, val lift: Float, val tilt: Float,
                    val leftFoot: Float, val rightFoot: Float, val carry: Float)

    fun pose(lift: Float, carry: Float, carriedMs: Long, pull: Float, animated: Boolean): Pose {
        val held = lift.coerceIn(0f, 1f)
        val raised = carry.coerceIn(0f, 1f)
        val swing = if (animated) sin(carriedMs.coerceAtLeast(0L) * 0.015f) else 0f
        val lean = if (animated) pull.coerceIn(-1f, 1f) else 0f
        return Pose(
            1f + (SCALE - 1f) * held, 0.8f * held,
            (swing * 4f + lean * 6f) * raised,
            (5f + 17f * swing) * raised, (-5f + 17f * swing) * raised, raised
        )
    }
}
