package com.phoneassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.sin

/** Three dots that rise and fade in sequence while a reply is on its way. */
class ThinkingView(context: Context) : View(context), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8ECF4.toInt() }
    private val bornAt = SystemClock.uptimeMillis()

    init {
        setBackgroundResource(R.drawable.overlay_panel)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - bornAt) / 1000f
        val centerY = height / 2f
        val radius = 3.1f * density
        val gap = 9.5f * density
        val firstX = width / 2f - gap

        for (index in 0 until 3) {
            val wave = (sin((t * 2.4f - index * 0.28f) * 6.283f) + 1f) / 2f
            dotPaint.alpha = (120 + 135 * wave).toInt()
            val scale = 0.65f + 0.35f * wave
            canvas.drawCircle(
                firstX + index * gap,
                centerY - wave * 2.2f * density,
                radius * scale,
                dotPaint
            )
        }
    }
}
