package com.phoneassistant

/** A resize can apply child offsets before Android has moved the enclosing window. */
internal class OverlayFrameSync {
    private var requested: OverlayBounds.Box? = null
    private var waitUntil = 0L
    private var requestedPetOffset = DragMotion.Point(0f, 0f)

    fun request(next: OverlayBounds.Box, now: Long, petOffset: DragMotion.Point = DragMotion.Point(0f, 0f)) {
        val previous = requested
        if (previous != null && (previous.width != next.width || previous.height != next.height ||
            (previous != next && requestedPetOffset != petOffset))) {
            // Give WindowManager a few frames, but never freeze on a device that fits the window differently.
            waitUntil = now + 70L
        }
        requested = next
        requestedPetOffset = petOffset
    }

    fun canDraw(actual: OverlayBounds.Box, now: Long, screenCoordinates: Boolean): Boolean {
        val target = requested ?: return true
        if (actual.width == target.width && actual.height == target.height &&
            (!screenCoordinates || actual.x == target.x && actual.y == target.y)) {
            waitUntil = 0L
        }
        return now >= waitUntil
    }
}
