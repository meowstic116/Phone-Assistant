package com.phoneassistant

import kotlin.math.abs

/** The counter's painted opening and Pip's standing face use one set of anchors. */
internal object LemonadeLayout {
    const val COUNTER_TOP_DP = 96f
    private const val FACE_BOTTOM_FROM_SOLES_DP = 19f
    private const val FACE_CLEARANCE_DP = 6f

    fun station(standX: Float, standY: Float, standWidth: Int, petHeight: Int, pxPerDp: Float): DragMotion.Point {
        val feet = standY + (COUNTER_TOP_DP + FACE_BOTTOM_FROM_SOLES_DP - FACE_CLEARANCE_DP) * pxPerDp
        return DragMotion.Point(standX + standWidth / 2f, ToyFooting.centreForFeet(feet, petHeight, pxPerDp))
    }

    /** The counter window is above Pip; forward only touches within the opening over him. */
    fun isPipTap(x: Float, y: Float, standWidth: Int, pxPerDp: Float): Boolean =
        abs(x - standWidth / 2f) <= 24f * pxPerDp && y >= 24f * pxPerDp && y < COUNTER_TOP_DP * pxPerDp
}
