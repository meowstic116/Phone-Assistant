package com.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager

/** Owns the sole speech overlay. Presentation survives a failed attach or a temporary duck. */
internal class SpeechBubble(
    private val context: Context,
    private val windows: WindowManager,
    private val limits: () -> Pair<Int, Int>,
    private val place: (Int, Int, WindowManager.LayoutParams) -> Unit,
) {
    private sealed interface Content {
        data object Dots : Content
        data class Text(val value: String, val end: Int, val conversation: Boolean) : Content
    }
    private var content: Content? = null
    val showingConversation get() = (content as? Content.Text)?.conversation == true && failures < 8
    private var view: SpeechBubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private val timing = SpeechBubbleTiming()
    private var ducked = false
    private var screenHidden = false
    private val hidden get() = ducked || screenHidden
    private var retryAt = 0L
    private var lastAddedAt = 0L
    private var failures = 0

    fun thinking() {
        if (content == Content.Dots) return
        present(Content.Dots, null)
    }

    fun text(value: String, end: Int = value.length, readMs: Long? = null, conversation: Boolean = false) {
        present(Content.Text(value, end, conversation), readMs)
    }

    private fun present(next: Content, readMs: Long?) {
        val old = content
        if (old !is Content.Text || next !is Content.Text || old.value != next.value) {
            failures = 0
            retryAt = 0L
        }
        content = next
        timing.hold(readMs)
        sync()
    }

    fun hold(ms: Long) { if (content is Content.Text) timing.hold(ms) }
    fun clearThinking() { if (content == Content.Dots) clear() }
    fun clearText() { if (content is Content.Text) clear() }
    fun clear() {
        content = null
        detach()
    }

    private fun detach() {
        val old = view
        view = null
        params = null
        if (old != null) runCatching { windows.removeViewImmediate(old) }
        timing.suspend()
    }

    /** Hide the whole window for a gesture, not just the pixels Android sees as obscuring it. */
    fun setDucked(value: Boolean): Boolean {
        if (ducked != value) timing.suspend()
        ducked = value
        return sync()
    }

    fun setScreenHidden(value: Boolean) {
        if (screenHidden == value) return
        timing.suspend()
        screenHidden = value
        sync()
    }

    fun onFrame() {
        if (content == null) return
        val now = SystemClock.uptimeMillis()
        val current = view
        if (current == null || !current.isAttachedToWindow && now - lastAddedAt >= 500L) {
            if (current != null) failed(now)
            sync(now)
            return
        }
        val drawn = current.isAttachedToWindow && current.hasDrawn
        if (drawn) failures = 0
        timing.advance(now, drawn && !hidden && params?.alpha == 1f, ValueAnimator.areAnimatorsEnabled())
        if (timing.finished) clear() else if (current.alpha != timing.opacity) current.alpha = timing.opacity
    }

    fun reflow() {
        if (content == null) return
        failures = 0
        retryAt = 0L
        sync()
    }

    fun reposition() {
        if (view != null && !timing.fading) sync()
    }

    private fun failed(now: Long, error: Exception? = null) {
        if (error != null && failures == 0) Diag.chatFailure("speech bubble window", error)
        failures++
        retryAt = now + (250L * failures.coerceAtMost(8))
        detach()
    }

    /** Reuse the same WindowManager slot during content changes. Retry if Android rejects it. */
    private fun sync(now: Long = SystemClock.uptimeMillis()): Boolean {
        val mode = content ?: return true
        if (view == null && now < retryAt) return true
        val previous = params
        return try {
            val panel = view ?: SpeechBubbleView(context)
            val (widthLimit, heightLimit) = limits()
            val line = mode as? Content.Text
            panel.display(line?.value, line?.end ?: 0, widthLimit, heightLimit)
            val next = WindowManager.LayoutParams(panel.panelWidth, panel.panelHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "Pip speech bubble"
                alpha = if (hidden) 0f else 1f
            }
            if (timing.fading && previous != null && next.width == previous.width && next.height == previous.height) {
                next.x = previous.x
                next.y = previous.y
            } else place(next.width, next.height, next)
            panel.alpha = timing.opacity
            if (previous == null) {
                windows.addView(panel, next)
                view = panel
                params = next
                lastAddedAt = now
            } else if (next.width != previous.width || next.height != previous.height || next.x != previous.x ||
                next.y != previous.y || next.alpha != previous.alpha) {
                windows.updateViewLayout(panel, next)
                params = next
            }
            true
        } catch (e: Exception) {
            failed(now, e)
            // A rejected gesture layout must not be treated as ready for input dispatch.
            false
        }
    }
}
