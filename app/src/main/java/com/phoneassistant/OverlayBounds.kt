package com.phoneassistant

/** A window may be taller than the usable display (rotation, split screen, or a keyboard). */
internal object OverlayBounds {
    data class Box(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right get() = x + width
        val bottom get() = y + height
    }

    /** Position the independently sized panel near its anchor without changing the anchor. */
    fun anchoredPanel(anchor: Box, width: Int, height: Int, screenWidth: Int, top: Int, bottom: Int,
                      sideMargin: Int, gap: Int): Box = Box(
        (anchor.x + (anchor.width - width) / 2).coerceIn(sideMargin, (screenWidth - width - sideMargin).coerceAtLeast(sideMargin)),
        panelY(anchor.y, anchor.height, height, gap, top, bottom), width, height)

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
