package com.phoneassistant

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Coalesces touch movement to one window update per display frame. */
internal class WindowDrag(
    private val view: View,
    private val position: () -> DragMotion.Point,
    private val moveTo: (Float, Float) -> Unit,
    private val allowed: () -> Boolean,
    private val grip: (Boolean) -> Unit = {},
    private val dragStarted: () -> Unit = {},
    private val dragFinished: () -> Unit = {},
    private val tapped: (Float, Float) -> Unit
) : View.OnTouchListener {
    private val motion = DragMotion(view.resources.displayMetrics.density)
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var pointer = -1
    private var dragged = false
    private var scheduled = false
    private var pending: DragMotion.Point? = null
    private var coastStart: DragMotion.Point? = null
    private var coastEnd: DragMotion.Point? = null
    private var releasedAt = 0L
    private var finishPending = false
    val held: Boolean get() = pointer != -1
    val moving: Boolean get() = held || finishPending

    private val frame = Runnable { render() }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val wasCoasting = finishPending
            cancel()
            if (!allowed()) return false
            if (wasCoasting) dragFinished()
            val (left, top) = position()
            motion.begin(event.rawX, event.rawY, left, top, event.eventTime)
            pointer = event.getPointerId(0)
            grip(true)
            return true
        }
        if (!held) return false
        if (!allowed() || event.actionMasked == MotionEvent.ACTION_CANCEL || event.pointerCount != 1 ||
            event.getPointerId(0) != pointer) {
            cancel()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
            motion.sample(event.rawX, event.rawY, event.eventTime)
            if (!dragged && motion.beyondSlop(event.rawX, event.rawY, slop)) {
                dragged = true
                dragStarted()
            }
            if (dragged) {
                pending = motion.target(event.rawX, event.rawY)
                schedule()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            pointer = -1
            grip(false)
            if (dragged) {
                finishPending = true
                coastEnd = motion.coast(event.eventTime)
                schedule()
            } else tapped(event.x, event.y)
            dragged = false
        }
        return true
    }

    fun cancel() {
        val gripping = held
        pointer = -1
        dragged = false
        pending = null
        coastStart = null
        coastEnd = null
        finishPending = false
        if (scheduled) view.removeCallbacks(frame)
        scheduled = false
        if (gripping) grip(false)
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        view.postOnAnimation(frame)
    }

    private fun render() {
        scheduled = false
        if (!view.isAttachedToWindow || !allowed()) {
            cancel()
            return
        }
        pending?.let { moveTo(it.x, it.y) }
        pending = null
        if (!finishPending) return
        val offset = coastEnd
        if (offset != null) {
            val start = coastStart
            if (start == null) {
                coastStart = position()
                releasedAt = SystemClock.uptimeMillis()
                schedule()
                return
            }
            val progress = (SystemClock.uptimeMillis() - releasedAt) / DragMotion.COAST_MS.toFloat()
            val fraction = DragMotion.coastFraction(progress)
            moveTo(start.x + offset.x * fraction, start.y + offset.y * fraction)
            if (progress < 1f) {
                schedule()
                return
            }
        }
        cancel()
        dragFinished()
    }
}
