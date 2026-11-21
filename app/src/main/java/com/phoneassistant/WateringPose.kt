package com.phoneassistant

import kotlin.math.cos
import kotlin.math.sin

/** Shared coordinates so the plant's can and Pip's arm agree across overlay windows. */
internal object WateringPose {
    private const val CAN_FROM_POT = 35f
    private const val CAN_ABOVE_POT = 24f
    const val POT_DEPTH_DP = 37f
    const val HANDLE_FROM_CAN = 17f
    private const val REACH = 27f
    private const val GRIP_ABOVE_FEET = CAN_ABOVE_POT + POT_DEPTH_DP
    private const val SPOUT_FROM_CAN = 21f
    private const val SPOUT_Y = -4.5f

    fun direction(facesRight: Boolean) = if (facesRight) 1f else -1f

    fun grip(potX: Float, potY: Float, facesRight: Boolean, density: Float) = DragMotion.Point(
        potX - direction(facesRight) * (CAN_FROM_POT + HANDLE_FROM_CAN) * density,
        potY - CAN_ABOVE_POT * density
    )

    /** Where Pip's centre and feet go, given the handle he needs to reach. */
    fun stance(grip: DragMotion.Point, facesRight: Boolean, density: Float) = DragMotion.Point(
        grip.x - direction(facesRight) * REACH * density,
        grip.y + GRIP_ABOVE_FEET * density
    )

    fun tilt(fraction: Float): Float = sin(Math.PI * fraction.coerceIn(0f, 1f)).toFloat() * 33f

    fun spout(grip: DragMotion.Point, facesRight: Boolean, density: Float, tilt: Float): DragMotion.Point {
        val angle = Math.toRadians(tilt.toDouble())
        val x = (HANDLE_FROM_CAN + SPOUT_FROM_CAN) * density
        val y = SPOUT_Y * density
        return DragMotion.Point(
            grip.x + direction(facesRight) * (x * cos(angle) - y * sin(angle)).toFloat(),
            grip.y + (x * sin(angle) + y * cos(angle)).toFloat()
        )
    }
}
