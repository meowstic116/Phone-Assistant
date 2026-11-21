package com.phoneassistant

/** A window may be taller than the usable display (rotation, split screen, or a keyboard). */
internal object OverlayBounds {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right get() = x + width
        val bottom get() = y + height
    }

    /** Both children keep their screen position when the shared window expands. */
    fun union(pet: Box, bubble: Box?): Box {
        if (bubble == null) return pet
        val left = minOf(pet.x, bubble.x)
        val top = minOf(pet.y, bubble.y)
        return Box(left, top, maxOf(pet.right, bubble.right) - left, maxOf(pet.bottom, bubble.bottom) - top)
    }

    fun clamp(value: Float, start: Float, end: Float) = value.coerceIn(start, end.coerceAtLeast(start))

    /** A keyboard sample that needs no move must not replace an in-flight toy destination. */
    fun keyboardEscapeY(currentY: Float, wantedY: Float, topLimit: Float): Float? =
        wantedY.coerceAtLeast(topLimit).takeIf { currentY - it >= 1f }

    fun panelY(anchorY: Int, anchorHeight: Int, panelHeight: Int, gap: Int, top: Int, bottom: Int): Int {
        val above = anchorY - panelHeight - gap
        val requested = if (above >= top) above else anchorY + anchorHeight + gap
        return requested.coerceIn(top, (bottom - panelHeight).coerceAtLeast(top))
    }
}
