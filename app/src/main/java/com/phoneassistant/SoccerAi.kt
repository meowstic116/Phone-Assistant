package com.phoneassistant

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.exp

/** Local opponent for the overlay pitch. Coordinates and speeds are in dp. */
internal object SoccerAi {
    const val RUN_SPEED = 215f
    const val CONTACT = 25f
    const val THINK_MS = 100L
    const val KICK_COOLDOWN_MS = 560L
    private const val FAR = 115f
    private const val GUARD_DEPTH = 38f

    data class Ball(val centre: DragMotion.Point, val vx: Float, val vy: Float)
    data class Pitch(val left: Float, val right: Float, val top: Float, val bottom: Float,
                     val goalLeft: Float, val goalRight: Float, val goalY: Float)
    enum class Role { CHASE, GUARD }
    data class Plan(val role: Role, val feet: DragMotion.Point)

    private fun distance(a: DragMotion.Point, b: DragMotion.Point): Float = hypot(a.x - b.x, a.y - b.y)

    /** Reflect a short prediction at the side walls; the actual ball still uses frame physics. */
    private fun reflected(value: Float, lower: Float, upper: Float): Float {
        val span = upper - lower
        if (span <= 0f) return lower
        val offset = ((value - lower) % (span * 2) + span * 2) % (span * 2)
        return lower + if (offset <= span) offset else span * 2 - offset
    }

    fun plan(feet: DragMotion.Point, ball: Ball, pitch: Pitch, userPressing: Boolean): Plan {
        val nearBall = distance(feet, ball.centre)
        val guardY = (pitch.goalY - GUARD_DEPTH).coerceIn(pitch.top, pitch.bottom)
        val incoming = ball.vy > 80f && ball.centre.y < pitch.goalY
        val secondsToGuard = if (incoming) ((guardY - ball.centre.y) / ball.vy).coerceIn(0f, 0.8f) else 0f
        val defendX = if (incoming) reflected(ball.centre.x + ball.vx * SoccerBallTuning.rollSeconds(secondsToGuard), pitch.left, pitch.right)
            else ball.centre.x
        val goalMiddle = (pitch.goalLeft + pitch.goalRight) / 2f
        val guardX = if (incoming) defendX else goalMiddle + (defendX - goalMiddle) * 0.32f
        if (nearBall > FAR && (userPressing || incoming)) {
            return Plan(Role.GUARD, DragMotion.Point(guardX.coerceIn(pitch.goalLeft, pitch.goalRight), guardY))
        }

        // Pick the earliest point Pip can actually reach. If none is reachable yet, keep
        // gaining ground on the last prediction rather than waiting for the ball to stop.
        var target = ball.centre
        for (step in 0..6) {
            val seconds = step * 0.12f
            val roll = SoccerBallTuning.rollSeconds(seconds)
            val x = reflected(ball.centre.x + ball.vx * roll, pitch.left, pitch.right)
            val y = reflected(ball.centre.y + ball.vy * roll, pitch.top, pitch.bottom)
            target = DragMotion.Point(x, (y + 9f).coerceAtMost(pitch.bottom))
            if (distance(feet, target) <= RUN_SPEED * seconds + CONTACT * 0.75f) break
        }
        return Plan(Role.CHASE, target)
    }

    fun aim(ball: DragMotion.Point, targetX: Float, targetY: Float, error: Float): Float =
        ((targetX - ball.x) / (abs(targetY - ball.y).coerceAtLeast(42f) * 0.6f) + error).coerceIn(-1.35f, 1.35f)

    /** Return the x where the ball crosses a goal line, not where it ends the frame. */
    fun crossingX(from: DragMotion.Point, to: DragMotion.Point, line: Float): Float? {
        if (line !in minOf(from.y, to.y)..maxOf(from.y, to.y) || to.y == from.y) return null
        val t = (line - from.y) / (to.y - from.y)
        return from.x + (to.x - from.x) * t
    }

    fun contact(feet: DragMotion.Point, from: DragMotion.Point, to: DragMotion.Point): DragMotion.Point? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        val along = (feet.x - from.x) * dx + (feet.y - from.y) * dy
        val t = if (lengthSquared == 0f) 0f else (along / lengthSquared).coerceIn(0f, 1f)
        val closest = DragMotion.Point(from.x + dx * t, from.y + dy * t)
        return closest.takeIf { distance(feet, it) <= CONTACT }
    }
}

/** Speeds in dp/second; distance damping is shared by play and Pip's prediction. */
internal object SoccerBallTuning {
    const val PLAYER_KICK = 610f
    const val PIP_KICK = 465f
    const val SAVE_KICK = 375f
    const val CLEAR_KICK = 300f
    const val REBOUND = 0.52f
    const val STOP_SPEED = 12f
    private const val DRAG = 0.83f

    fun retainedSpeed(seconds: Float): Float = exp(-DRAG * seconds)
    fun rollSeconds(seconds: Float): Float = (1f - retainedSpeed(seconds)) / DRAG
}
