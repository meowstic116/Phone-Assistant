package com.phoneassistant

import kotlin.math.sin

/** Drawing poses for the short power-on and power-off animations. */
internal object PipTransition {
    const val ENTRANCE_MS = 560L
    const val EXIT_MS = 410L

    data class Pose(val sx: Float, val sy: Float, val opacity: Float, val lift: Float,
                    val spark: Float, val spread: Float)

    fun pose(entering: Boolean, fraction: Float): Pose {
        val t = fraction.coerceIn(0f, 1f)
        if (entering) {
            val rise = (t / 0.69f).coerceAtMost(1f)
            val rest = 1f - rise
            val size = 1f - rest * rest * rest
            val settle = ((t - 0.40f) / 0.60f).coerceIn(0f, 1f)
            val bounce = envelope(settle) * (1f - settle) * 0.12f
            return Pose(0.20f + 0.80f * size - bounce * 0.55f, 0.04f + 0.96f * size + bounce,
                (t / 0.32f).coerceAtMost(1f), bounce * 25f, envelope(t), t)
        }
        val squash = envelope((t / 0.48f).coerceAtMost(1f))
        val shrinking = ((t - 0.18f) / 0.82f).coerceIn(0f, 1f)
        val remaining = (1f - shrinking) * (1f - shrinking)
        return Pose(0.07f + (0.93f + 0.12f * squash) * remaining,
            0.04f + (0.96f - 0.24f * squash) * remaining, 1f - shrinking,
            0f, envelope(t), 1f - t)
    }

    private fun envelope(t: Float): Float = if (t <= 0f || t >= 1f) 0f else sin(t * Math.PI).toFloat()
}
