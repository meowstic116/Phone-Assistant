package com.phoneassistant

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.sin

/** One surface for the dots, captions, and full text. Its layout doesn't depend on visible words. */
internal class SpeechBubbleView(context: Context) : View(context) {
    private val density get() = resources.displayMetrics.density
    private val fontSize get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    private fun dp(value: Float) = (value * density).toInt().coerceAtLeast(1)
    private val ink = 0xFFE8ECF4.toInt()
    private val pen = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = fontSize
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private var text: String? = null
    private var shown = -1
    private var widthLimit = 0
    private var heightLimit = 0
    private var bodyWidth = 0
    private var lines = 1
    private var padX = 0
    private var padY = 0
    private var body: StaticLayout? = null
    private var styledBody: SpannableString? = null
    private val unrevealedInk = ForegroundColorSpan(Color.TRANSPARENT)
    private var dotsStartedAt = 0L
    var panelWidth = 1
        private set
    var panelHeight = 1
        private set
    var hasDrawn = false
        private set

    init { setBackgroundResource(R.drawable.overlay_panel) }

    fun display(value: String?, end: Int, maxWidth: Int, maxHeight: Int) {
        val newLayout = text != value || widthLimit != maxWidth || heightLimit != maxHeight || pen.textSize != fontSize
        if (!newLayout && shown == end) return
        if ((text != null && value == null) || dotsStartedAt == 0L) dotsStartedAt = SystemClock.uptimeMillis()
        pen.textSize = fontSize
        text = value
        shown = end
        widthLimit = maxWidth
        heightLimit = maxHeight
        if (value == null) {
            panelWidth = dp(52f).coerceAtMost(maxWidth)
            panelHeight = dp(28f).coerceAtMost(maxHeight)
            body = null
            styledBody = null
            contentDescription = "Pip is thinking"
        } else {
            if (newLayout) {
                padX = dp(12f).coerceAtMost((maxWidth - 1) / 2)
                padY = dp(8f).coerceAtMost((maxHeight - 1) / 2)
                bodyWidth = ceil(Layout.getDesiredWidth(value, pen)).toInt()
                    .coerceIn((dp(48f) - padX * 2).coerceIn(1, maxWidth - padX * 2), maxWidth - padX * 2)
                val font = pen.fontMetricsInt
                val lineHeight = (font.bottom - font.top + dp(2f)).coerceAtLeast(1)
                lines = ((maxHeight - padY * 2) / lineHeight).coerceAtLeast(1)
                val styled = SpannableString(value)
                styledBody = styled
                val full = layout(styled)
                body = full
                panelWidth = bodyWidth + padX * 2
                panelHeight = (full.height + padY * 2).coerceIn(dp(26f).coerceAtMost(maxHeight), maxHeight)
            }
            // This span changes only ink, not measurement. Keep line breaks and the
            // StaticLayout for the whole utterance, including during word-by-word reveal.
            val visibleEnd = end.coerceIn(0, value.length)
            styledBody?.apply {
                if (visibleEnd == length) removeSpan(unrevealedInk)
                else setSpan(unrevealedInk, visibleEnd, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            contentDescription = value.take(visibleEnd)
        }
        invalidate()
    }

    /** Layout.BREAK_STRATEGY_SIMPLE is the same constant value as LineBreaker's. */
    @SuppressLint("WrongConstant")
    private fun layout(value: CharSequence) = StaticLayout.Builder.obtain(value, 0, value.length, pen, bodyWidth)
        .setLineSpacing(dp(2f).toFloat(), 1f).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).build()

    override fun onDetachedFromWindow() {
        hasDrawn = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        hasDrawn = width > 0 && height > 0
        val label = body
        if (label != null) {
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(padX.toFloat(), padY.toFloat())
            label.draw(canvas)
            canvas.restore()
        } else {
            val t = (SystemClock.uptimeMillis() - dotsStartedAt) / 1000f
            val gap = 9.5f * density
            for (i in 0 until 3) {
                val wave = (sin((t * 2.4f - i * 0.28f) * 6.283f) + 1f) / 2f
                dot.alpha = (120 + 135 * wave).toInt()
                canvas.drawCircle(width / 2f + (i - 1) * gap, height / 2f - wave * 2.2f * density,
                    3.1f * density * (0.65f + 0.35f * wave), dot)
            }
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
        }
    }
}
