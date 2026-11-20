package com.phoneassistant

import kotlin.math.sin

/** Visual-only cues emitted by the feed runner; these never schedule a phone action. */
internal object DoomscrollMotion {
    const val SWIPE_MS = 425L
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val COMMAND = "Open Instagram and doomscroll"
    enum class Cue { WATCH, SWIPE, CLEAR }
    data class Pose(val lean: Float, val gazeX: Float, val gazeY: Float,
                    val arm: Float = 0f, val handY: Float = 0f, val trail: Float = 0f)

    fun pose(cue: Cue, ageMs: Long, animated: Boolean): Pose? {
        if (cue == Cue.CLEAR) return null
        if (!animated) return Pose(0f, 3f, -2f)
        val time = ageMs.coerceAtLeast(0L)
        if (cue == Cue.SWIPE && time < SWIPE_MS) {
            val reach = (time / 70f).coerceIn(0f, 1f)
            val flick = ((time - 70L) / 150f).coerceIn(0f, 1f)
            val spring = 1f - (1f - flick) * (1f - flick)
            val retract = ((time - 220L) / 205f).coerceIn(0f, 1f)
            val trail = if (time < 70L) 0f else sin(spring * Math.PI / 2).toFloat() * (1f - retract)
            return Pose(5f + 4f * trail, 3.3f, (-2.3f * retract) + (2f - 6f * spring) * (1f - retract),
                reach * (1f - retract), (13f - 31f * spring) * (1f - retract), trail)
        }
        val watching = if (cue == Cue.SWIPE) time - SWIPE_MS else time
        val lean = if (cue == Cue.SWIPE) 1f else (watching / 170f).coerceIn(0f, 1f)
        return Pose(5.0f * lean, 3.3f * lean,
            (-2.3f + 0.9f * sin(watching / 520.0).toFloat()) * lean)
    }
}
