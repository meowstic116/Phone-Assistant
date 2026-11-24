package com.phoneassistant

import kotlin.math.sin

/**
 * The plant's idle breeze: a slow, small rock of the whole plant, so it looks alive even
 * when nothing at all is happening. The phase comes from elapsed time rather than from
 * frames, so it never jumps and never drifts. A grown plant has caught more sun and
 * sails a little further than a sprout.
 */
internal object PlantSway {

    /** One full sway, in milliseconds: a slow breeze, not a nervous wobble. */
    private const val PERIOD_MS = 4_400.0

    /** Degrees of sway at the smallest stage. */
    private const val BASE_SWAY = 1.15f

    /** Extra degrees per stage of growth. */
    private const val PER_STAGE = 0.4f

    /** The sway in degrees at [now], around rest: positive one way, negative the other. */
    fun angle(bornAt: Long, now: Long, stage: Int): Float {
        val t = (now - bornAt).toDouble() / PERIOD_MS
        return (sin(t * 2.0 * Math.PI) * (BASE_SWAY + PER_STAGE * stage.coerceIn(0, 3))).toFloat()
    }
}
