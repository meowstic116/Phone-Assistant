package com.phoneassistant

/** Reading time and fade only progress on frames when the bubble can actually be seen. */
internal class SpeechBubbleTiming {
    private var remaining: Long? = null
    private var lastFrame = 0L
    private var wasVisible = false
    var opacity = 1f
        private set
    val fading get() = opacity < 1f
    val finished get() = opacity <= 0f

    fun hold(readingMs: Long?) {
        remaining = readingMs
        opacity = 1f
        suspend()
    }

    fun suspend() { wasVisible = false }

    fun advance(now: Long, visible: Boolean, animate: Boolean) {
        // Don't charge a stalled UI frame or the gap before a newly attached window is drawn.
        val elapsed = if (visible && wasVisible) (now - lastFrame).coerceIn(0L, 250L) else 0L
        lastFrame = now
        wasVisible = visible
        val left = remaining ?: return
        if (elapsed == 0L) return
        val next = (left - elapsed).coerceAtLeast(-FADE_MS)
        remaining = next
        opacity = if (next > 0L) 1f else if (!animate) 0f else (1f + next.toFloat() / FADE_MS).coerceAtLeast(0f)
    }

    private companion object { const val FADE_MS = 180L }
}

/** A caption may coexist with deliberate toy movement, but doesn't cue a new idle walk. */
internal class CaptionIdlePause {
    private var visible = false
    private var resumeAt = 0L

    fun visibilityChanged(shown: Boolean, now: Long) {
        if (shown == visible) return
        visible = shown
        if (!shown) resumeAt = now + 900L
    }

    fun permitsIdleMotion(now: Long): Boolean = !visible && now >= resumeAt
}
