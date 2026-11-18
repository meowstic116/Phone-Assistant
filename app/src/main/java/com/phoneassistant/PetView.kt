package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View

/**
 * Draws the character: a small phone-shaped creature with a face on its screen.
 * Everything is procedural, so there are no sprite sheets or image assets.
 */
class PetView(context: android.content.Context) : View(context), Choreographer.FrameCallback {

    enum class Mood { NEUTRAL, HAPPY, SLEEPY, THINKING, TALKING, SMUG, ANNOYED }

    var mood: Mood = Mood.NEUTRAL
        set(value) {
            field = value
            invalidate()
        }

    var walking: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var facingRight: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Plopped down on the floor with a canvas, legs folded. */
    var sitting: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF232936.toInt() }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A4356.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1118.toInt() }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7DD3FC.toInt() }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DD3FC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
    }

    private val bodyRect = RectF()
    private val screenRect = RectF()
    private val shape = Path()

    private val bornAt = SystemClock.uptimeMillis()
    private var nextBlinkAt = bornAt + 2200L
    private var blinkEndsAt = 0L
    private var blinkSeed = bornAt

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        val now = SystemClock.uptimeMillis()
        if (soonToBlink(now)) {
            blinkEndsAt = now + 110L
            blinkSeed = (blinkSeed * 1103515245L + 12345L) and 0x7FFFFFFF
            nextBlinkAt = now + 2400L + (blinkSeed % 4200L)
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun soonToBlink(now: Long) = now >= nextBlinkAt

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - bornAt) / 1000f

        val bodyW = dp(46f)
        val bodyH = dp(62f)
        val legH = dp(9f)
        val groundY = height - dp(2f)
        val cx = width / 2f

        val bob = when {
            sitting -> 0f
            walking -> kotlin.math.abs(kotlin.math.sin(t * 9f)) * dp(2.4f)
            else -> kotlin.math.sin(t * 2.1f) * dp(1.1f)
        }
        val tilt = when {
            sitting -> 0f
            walking -> kotlin.math.sin(t * 9f) * 5f
            else -> kotlin.math.sin(t * 1.3f) * 1.4f
        }
        val bodyCenterY = if (sitting) {
            groundY - dp(13f) - bodyH / 2f
        } else {
            groundY - legH - bodyH / 2f - bob
        }

        if (sitting) {
            // Folded legs: just a flat little puddle under him.
            bodyRect.set(cx - dp(24f), groundY - dp(8f), cx + dp(24f), groundY)
            canvas.drawRoundRect(bodyRect, dp(4f), dp(4f), rimPaint.fillOnly())
        } else {
            // Stubby legs, alternating while walking.
            val stepPhase = if (walking) kotlin.math.sin(t * 9f) else 0f
            val legW = dp(7f)
            val legTop = groundY - legH - bob
            val leftLift = if (walking) maxOf(0f, stepPhase) * dp(3.5f) else 0f
            val rightLift = if (walking) maxOf(0f, -stepPhase) * dp(3.5f) else 0f

            bodyRect.set(cx - dp(11f), legTop - leftLift, cx - dp(11f) + legW, groundY - leftLift)
            canvas.drawRoundRect(bodyRect, dp(3f), dp(3f), rimPaint.fillOnly())
            bodyRect.set(cx + dp(4f), legTop - rightLift, cx + dp(4f) + legW, groundY - rightLift)
            canvas.drawRoundRect(bodyRect, dp(3f), dp(3f), rimPaint.fillOnly())
        }

        canvas.save()
        canvas.translate(cx, bodyCenterY)
        canvas.rotate(if (facingRight) tilt else -tilt)

        // Antenna with a tiny light that brightens while thinking.
        canvas.drawLine(0f, -bodyH / 2f, 0f, -bodyH / 2f - dp(6f), rimPaint)
        val lightPaint = if (mood == Mood.THINKING) accentPaint else rimPaint
        canvas.drawCircle(0f, -bodyH / 2f - dp(7f), dp(2.2f), lightPaint.fillOnly())

        bodyRect.set(-bodyW / 2f, -bodyH / 2f, bodyW / 2f, bodyH / 2f)
        canvas.drawRoundRect(bodyRect, dp(11f), dp(11f), bodyPaint)
        canvas.drawRoundRect(bodyRect, dp(11f), dp(11f), rimPaint)

        screenRect.set(
            -bodyW / 2f + dp(4f),
            -bodyH / 2f + dp(8f),
            bodyW / 2f - dp(4f),
            bodyH / 2f - dp(10f)
        )
        canvas.drawRoundRect(screenRect, dp(7f), dp(7f), screenPaint)

        drawFace(canvas, t, now)
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, t: Float, now: Long) {
        val faceCx = screenRect.centerX()
        val eyeY = screenRect.top + screenRect.height() * 0.36f
        val eyeDx = dp(9.5f)
        val eyeR = dp(3.2f)
        val blinking = now < blinkEndsAt

        when {
            blinking || mood == Mood.SLEEPY -> {
                canvas.drawLine(faceCx - eyeDx - eyeR, eyeY, faceCx - eyeDx + eyeR, eyeY, facePaint)
                canvas.drawLine(faceCx + eyeDx - eyeR, eyeY, faceCx + eyeDx + eyeR, eyeY, facePaint)
            }
            mood == Mood.HAPPY -> {
                drawHappyEye(canvas, faceCx - eyeDx, eyeY, eyeR)
                drawHappyEye(canvas, faceCx + eyeDx, eyeY, eyeR)
            }
            mood == Mood.SMUG -> {
                // Half-lidded and unbearably pleased with himself.
                canvas.drawCircle(faceCx - eyeDx, eyeY, eyeR, accentPaint)
                canvas.drawCircle(faceCx + eyeDx, eyeY, eyeR, accentPaint)
                val lidY = eyeY - eyeR * 0.25f
                canvas.drawRect(
                    faceCx - eyeDx - eyeR, eyeY - eyeR,
                    faceCx - eyeDx + eyeR, lidY, screenPaint
                )
                canvas.drawRect(
                    faceCx + eyeDx - eyeR, eyeY - eyeR,
                    faceCx + eyeDx + eyeR, lidY, screenPaint
                )
                canvas.drawLine(faceCx - eyeDx - eyeR, lidY, faceCx - eyeDx + eyeR, lidY, facePaint)
                canvas.drawLine(faceCx + eyeDx - eyeR, lidY, faceCx + eyeDx + eyeR, lidY, facePaint)
            }
            mood == Mood.ANNOYED -> {
                canvas.drawCircle(faceCx - eyeDx, eyeY + dp(0.9f), eyeR * 0.85f, accentPaint)
                canvas.drawCircle(faceCx + eyeDx, eyeY + dp(0.9f), eyeR * 0.85f, accentPaint)
                // Angry eyebrows: the entire vocabulary of being fed up.
                canvas.drawLine(
                    faceCx - eyeDx - eyeR, eyeY - dp(4.6f),
                    faceCx - eyeDx + eyeR, eyeY - dp(2.3f), facePaint
                )
                canvas.drawLine(
                    faceCx + eyeDx - eyeR, eyeY - dp(2.3f),
                    faceCx + eyeDx + eyeR, eyeY - dp(4.6f), facePaint
                )
            }
            else -> {
                val lookUp = if (mood == Mood.THINKING) -dp(1.6f) else 0f
                canvas.drawCircle(faceCx - eyeDx, eyeY + lookUp, eyeR, accentPaint)
                canvas.drawCircle(faceCx + eyeDx, eyeY + lookUp, eyeR, accentPaint)
                if (lookUp != 0f) {
                    canvas.drawCircle(faceCx - eyeDx + eyeR * 0.35f, eyeY + lookUp - eyeR * 0.35f, eyeR * 0.4f, screenPaint)
                    canvas.drawCircle(faceCx + eyeDx + eyeR * 0.35f, eyeY + lookUp - eyeR * 0.35f, eyeR * 0.4f, screenPaint)
                }
            }
        }

        val mouthY = screenRect.top + screenRect.height() * 0.64f
        when (mood) {
            Mood.TALKING -> {
                val open = (0.5f + 0.5f * kotlin.math.sin(t * 13f)) * dp(3.4f)
                bodyRect.set(faceCx - dp(4f), mouthY - open / 2f, faceCx + dp(4f), mouthY + open / 2f)
                canvas.drawOval(bodyRect, accentPaint)
            }
            Mood.HAPPY -> {
                shape.reset()
                shape.moveTo(faceCx - dp(5f), mouthY - dp(2f))
                shape.quadTo(faceCx, mouthY + dp(3f), faceCx + dp(5f), mouthY - dp(2f))
                canvas.drawPath(shape, facePaint)
            }
            Mood.SLEEPY -> canvas.drawLine(faceCx - dp(3f), mouthY, faceCx + dp(3f), mouthY, facePaint)
            Mood.SMUG -> {
                shape.reset()
                shape.moveTo(faceCx - dp(5f), mouthY)
                shape.quadTo(faceCx + dp(1f), mouthY + dp(3.2f), faceCx + dp(6f), mouthY - dp(2.6f))
                canvas.drawPath(shape, facePaint)
            }
            Mood.ANNOYED -> {
                shape.reset()
                shape.moveTo(faceCx - dp(4.5f), mouthY + dp(2.6f))
                shape.quadTo(faceCx, mouthY - dp(2.2f), faceCx + dp(4.5f), mouthY + dp(2.6f))
                canvas.drawPath(shape, facePaint)
                // Steam, because he is being dramatic about it.
                canvas.drawCircle(faceCx - dp(13f), mouthY - dp(7f), dp(1.8f), facePaint)
                canvas.drawCircle(faceCx + dp(13f), mouthY - dp(9.5f), dp(1.4f), facePaint)
            }
            else -> {
                shape.reset()
                shape.moveTo(faceCx - dp(3.5f), mouthY)
                shape.quadTo(faceCx, mouthY + dp(1.8f), faceCx + dp(3.5f), mouthY)
                canvas.drawPath(shape, facePaint)
            }
        }
    }

    private fun drawHappyEye(canvas: Canvas, x: Float, y: Float, r: Float) {
        shape.reset()
        shape.moveTo(x - r, y + r * 0.5f)
        shape.quadTo(x, y - r * 1.5f, x + r, y + r * 0.5f)
        canvas.drawPath(shape, facePaint)
    }

    /** Filled version of a stroke paint, for the little legs and antenna light. */
    private fun Paint.fillOnly(): Paint = Paint(this).apply { style = Paint.Style.FILL }
}
