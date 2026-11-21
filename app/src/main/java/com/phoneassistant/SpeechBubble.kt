package com.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.View

/** Presentation inside Pip's existing overlay. Showing a reply never opens a new window. */
internal class SpeechBubble(
    context: Context,
    private val limits: () -> Pair<Int, Int>,
    private val onBoundsChanged: () -> Unit,
) {
    private sealed interface Content {
        data object Dots : Content
        data class Text(val value: String, val end: Int, val conversation: Boolean) : Content
    }
    val view = SpeechBubbleView(context).apply { visibility = View.GONE }
    private var content: Content? = null
    val showingConversation get() = (content as? Content.Text)?.conversation == true
    val shown get() = view.visibility == View.VISIBLE
    val fading get() = timing.fading
    private val timing = SpeechBubbleTiming()
    private var ducked = false
    private var screenHidden = false

    fun thinking() {
        if (content != Content.Dots) {
            content = Content.Dots
            timing.hold(null)
        }
        sync()
    }

    fun text(value: String, end: Int = value.length, readMs: Long? = null, conversation: Boolean = false) {
        content = Content.Text(value, end, conversation)
        timing.hold(readMs)
        sync()
    }

    fun hold(ms: Long) { if (content is Content.Text) timing.hold(ms) }
    fun clearThinking() { if (content == Content.Dots) clear() }
    fun clearText() { if (content is Content.Text) clear() }
    fun clear() {
        content = null
        timing.suspend()
        sync()
    }

    fun setDucked(value: Boolean) {
        if (ducked == value) return
        timing.suspend()
        ducked = value
        sync()
    }

    fun setScreenHidden(value: Boolean) {
        if (screenHidden == value) return
        timing.suspend()
        screenHidden = value
        sync()
    }

    fun onFrame() {
        if (content == null) return
        timing.advance(SystemClock.uptimeMillis(), shown && view.isAttachedToWindow && view.isShown && view.hasDrawn,
            ValueAnimator.areAnimatorsEnabled())
        if (timing.finished) clear() else view.alpha = timing.opacity
    }

    fun reflow() = sync()

    private fun sync() {
        val mode = content
        val wasShown = shown
        val previousWidth = view.panelWidth
        val previousHeight = view.panelHeight
        if (mode == null || ducked || screenHidden) {
            view.visibility = View.GONE
        } else {
            val (widthLimit, heightLimit) = limits()
            val line = mode as? Content.Text
            view.display(line?.value, line?.end ?: 0, widthLimit, heightLimit)
            view.alpha = timing.opacity
            view.visibility = View.VISIBLE
        }
        if (wasShown != shown || shown && (view.panelWidth != previousWidth || view.panelHeight != previousHeight)) {
            onBoundsChanged()
        }
    }
}
