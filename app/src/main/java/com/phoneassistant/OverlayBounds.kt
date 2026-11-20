package com.phoneassistant

/** A window may be taller than the usable display (rotation, split screen, or a keyboard). */
internal object OverlayBounds {
    fun clamp(value: Float, start: Float, end: Float) = value.coerceIn(start, end.coerceAtLeast(start))

    fun panelY(anchorY: Int, anchorHeight: Int, panelHeight: Int, gap: Int, top: Int, bottom: Int): Int {
        val above = anchorY - panelHeight - gap
        val requested = if (above >= top) above else anchorY + anchorHeight + gap
        return requested.coerceIn(top, (bottom - panelHeight).coerceAtLeast(top))
    }
}
