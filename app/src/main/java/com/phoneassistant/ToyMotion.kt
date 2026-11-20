package com.phoneassistant

import kotlin.math.sin

/** Profiles for the different ways Pip tosses a toy out or gathers it back up. */
internal enum class ToyMotion(val color: Int, val tilt: Float, val arc: Float, val duration: Long) {
    STAND(0xFFFFD37B.toInt(), 3f, 6f, 395L),
    CUP(0xFFFFEB90.toInt(), 10f, 16f, 335L),
    CANVAS(0xFFC5A4FF.toInt(), -15f, 14f, 455L),
    GOAL(0xFF81EDC9.toInt(), -7f, 11f, 410L),
    BALL(0xFF8FCBFF.toInt(), 145f, 37f, 465L),
    SCORE(0xFFFFC99F.toInt(), 8f, 18f, 375L),
    WRAP(0xFFFFA8CD.toInt(), -13f, 24f, 470L),
    PLANT(0xFF91E8A4.toInt(), 9f, 19f, 465L);

    data class Pose(val progress: Float, val lift: Float, val xScale: Float, val yScale: Float,
                    val turn: Float, val alpha: Float)

    fun pose(entering: Boolean, fraction: Float): Pose {
        val t = fraction.coerceIn(0f, 1f)
        val wave = if (t in 0.001f..0.999f) sin(t * Math.PI).toFloat() else 0f
        if (entering) {
            val rest = 1f - t
            val travel = 1f - rest * rest * rest
            val settle = wave * t * if (this == PLANT || this == BALL) 0.20f else 0.10f
            val fold = if (this == WRAP || this == CANVAS) 0.08f else 0.18f
            val rotation = if (this == BALL) -tilt * rest else tilt * rest + wave * (if (this == WRAP) 9f else 0f)
            return Pose(travel, wave * arc, 0.19f + 0.81f * travel + settle * 0.35f,
                fold + (1f - fold) * travel + settle, rotation, (t * 4.2f).coerceAtMost(1f))
        }
        val travel = t * t * (3f - 2f * t)
        val size = 1f - travel * 0.92f
        return Pose(travel, wave * arc * 0.42f, size,
            size * (1f - if (this == CANVAS || this == WRAP) 0.42f * wave else 0.15f * wave),
            travel * -tilt * 0.65f, (1f - t * t).coerceAtLeast(0f))
    }
}
