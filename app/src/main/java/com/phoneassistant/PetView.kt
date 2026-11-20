package com.phoneassistant

import android.animation.ValueAnimator
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

    var gardenLean = 0f
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

    internal var powerPose: PipTransition.Pose? = null
        set(value) {
            field = value
            invalidate()
        }

    internal var toyReach = 0f
        set(value) { field = value; invalidate() }
    internal var toyReachSide = 1f
    internal var toyReachColor = 0xFF99EDFF.toInt()
    private val toyInk = Paint(Paint.ANTI_ALIAS_FLAG)

    private var doomscrollCue = DoomscrollMotion.Cue.CLEAR
    private var doomscrollAt = 0L
    private var doomscrollSide = -1f
    private val swipeInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF99EDFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    internal fun showDoomscrollCue(cue: DoomscrollMotion.Cue, side: Float = -1f) {
        val now = SystemClock.uptimeMillis()
        doomscrollSide = side
        // If the next reel arrives quickly, let the flick finish and settle into watching.
        if (cue == DoomscrollMotion.Cue.WATCH && doomscrollCue == DoomscrollMotion.Cue.SWIPE) return
        doomscrollCue = cue
        doomscrollAt = now
        invalidate()
    }

    private val powerInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF99EDFF.toInt() }

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

    /** When a frame was last actually drawn, so idle frames can be skipped. */
    private var lastDrawAt = 0L

    /** Whether the eyes were shut on the last frame, so both ends of a blink get drawn. */
    private var drewBlinking = false

    /**
     * Frames actually drawn in the last measured second. Counted in [onDraw] rather than where
     * he decides to redraw, because that is the number the Diagnostics screen is claiming and
     * the one worth checking: a count of decisions proves nothing was painted.
     */
    @Volatile
    var drawsPerSecond: Int = 0
        private set

    private var drawsThisSecond = 0
    private var windowStartedAt = SystemClock.uptimeMillis()

    /**
     * False while the screen is off. Nothing can see him then, and this window would otherwise
     * keep drawing the idle sway sixty times a second into a display that is not on.
     */
    var animating: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) return
            // Waking draws at once rather than waiting out the gap, and counts a fresh window
            // so the first figure after the screen comes back is a real one. The published
            // rate is left alone until then: it is the last thing measured, and zeroing it
            // would read as though he had not started drawing again.
            lastDrawAt = 0L
            drawsThisSecond = 0
            windowStartedAt = SystemClock.uptimeMillis()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    /**
     * Draws only when there is something different to draw.
     *
     * The frame callback itself is the cheapest part of a frame; the draw that follows it is
     * the expensive one, and this window is on the screen for as long as he runs. So an idle
     * Pip — a 1.1dp sway and an occasional blink — is repainted twenty times a second instead
     * of sixty, which is indistinguishable on a body this small. A walk still gets every
     * frame, because a hop that misses one is visible.
     */
    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)
        // Screen off: the callback is kept so waking is instant, but nothing is drawn into a
        // display nobody is looking at.
        if (!animating) return
        val now = SystemClock.uptimeMillis()

        val blinkStarts = now >= nextBlinkAt
        if (blinkStarts) {
            blinkEndsAt = now + 110L
            blinkSeed = (blinkSeed * 1103515245L + 12345L) and 0x7FFFFFFF
            nextBlinkAt = now + 2400L + (blinkSeed % 4200L)
        }
        // Both ends of a blink have to be drawn, or a skipped frame leaves him mid-blink.
        val blinking = now < blinkEndsAt
        val eyesChanged = blinking != drewBlinking
        drewBlinking = blinking

        if (blinkStarts || eyesChanged || now - lastDrawAt >= frameGapMs()) {
            lastDrawAt = now
            invalidate()
        }
    }

    /**
     * How long may pass between redraws. Walking is the one thing that has to be smooth; a
     * talking mouth is the only other thing that moves by itself, since every other mood is a
     * still picture that is drawn when it is set. Everything else gets the idle rate.
     */
    private fun frameGapMs(): Long = when {
        walking -> GAP_WALKING
        mood == Mood.TALKING -> GAP_TALKING
        else -> GAP_IDLE
    }

    /** Folds this draw into the once-a-second count the readout shows. */
    private fun countDraw(now: Long) {
        drawsThisSecond++
        val elapsed = now - windowStartedAt
        if (elapsed < 1_000L) return
        drawsPerSecond = (drawsThisSecond * 1_000L / elapsed).toInt()
        drawsThisSecond = 0
        windowStartedAt = now
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        countDraw(now)
        val t = (now - bornAt) / 1000f

        val bodyW = dp(46f)
        val bodyH = dp(62f)
        val legH = dp(9f)
        val groundY = height - dp(2f)
        val cx = width / 2f

        val attention = if (doomscrollCue == DoomscrollMotion.Cue.CLEAR) null else
            DoomscrollMotion.pose(doomscrollCue, now - doomscrollAt, ValueAnimator.areAnimatorsEnabled())
        val transition = powerPose
        val powerLayer = if (transition != null) {
            drawPowerSpark(canvas, cx, groundY, transition)
            if (transition.opacity <= 0f) return
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                (255 * transition.opacity).toInt())
            canvas.translate(0f, -dp(transition.lift))
            canvas.scale(transition.sx, transition.sy, cx, groundY)
            layer
        } else -1

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
        val poseTilt = tilt + gardenLean * 10f
        canvas.rotate((if (facingRight) poseTilt else -poseTilt) + toyReachSide * toyReach * 4f +
            doomscrollSide * (attention?.lean ?: 0f))

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

        drawFace(canvas, t, now, attention)
        if (attention != null && attention.arm > 0f) drawScrollFlick(canvas, attention)
        if (toyReach > 0f) {
            val end = toyReachSide * dp(20f + 8f * toyReach)
            val armY = dp(10f - 7f * toyReach)
            toyInk.color = toyReachColor
            toyInk.alpha = (255 * toyReach).toInt()
            canvas.drawLine(toyReachSide * dp(15f), dp(12f), end, armY, facePaint)
            canvas.drawCircle(end, armY, dp(2.6f), toyInk)
            canvas.drawCircle(end - toyReachSide * dp(2.5f), armY - dp(7f), dp(1.2f), toyInk)
            canvas.drawCircle(end, armY + dp(6f), dp(1f), toyInk)
        }
        if (gardenLean > 0f) {
            val side = if (facingRight) 1f else -1f
            canvas.drawLine(side * dp(17f), dp(8f), side * dp(27f), dp(12f - gardenLean * 9f), facePaint)
            canvas.drawCircle(side * dp(27f), dp(12f - gardenLean * 9f), dp(2.5f), accentPaint)
        }
        canvas.restore()
        if (powerLayer != -1) canvas.restoreToCount(powerLayer)
    }

    private fun drawScrollFlick(canvas: Canvas, pose: DoomscrollMotion.Pose) {
        val side = doomscrollSide
        val handX = side * dp(21f + 6.5f * pose.arm)
        val handY = dp(pose.handY)
        canvas.drawLine(side * dp(17f), dp(12f), handX, handY, facePaint)
        canvas.drawCircle(handX, handY, dp(2.8f), accentPaint)
        if (pose.trail <= 0f) return
        swipeInk.strokeWidth = dp(1.5f)
        swipeInk.alpha = (230f * pose.trail).toInt()
        val trailX = side * dp(28.5f)
        val y = handY + dp(5f)
        canvas.drawLine(trailX, y, trailX, y + dp(8f), swipeInk)
        canvas.drawLine(trailX, y, trailX - dp(2.5f), y + dp(2.5f), swipeInk)
        canvas.drawLine(trailX, y, trailX + dp(2.5f), y + dp(2.5f), swipeInk)
    }

    private fun drawPowerSpark(canvas: Canvas, cx: Float, groundY: Float, pose: PipTransition.Pose) {
        if (pose.spark <= 0f) return
        val halfWidth = dp(7f + 23f * pose.spread)
        val diameter = dp(2.6f + 8f * pose.spread)
        powerInk.alpha = (125f * pose.spark).toInt()
        canvas.drawOval(cx - halfWidth, groundY - diameter, cx + halfWidth, groundY + dp(1f), powerInk)
        powerInk.alpha = (240f * pose.spark).toInt()
        for (i in 0..3) {
            val side = if (i % 2 == 0) -1f else 1f
            val x = cx + side * dp(9f + pose.spread * (if (i < 2) 20f else 8f))
            val y = groundY - dp((if (i < 2) 6f else 19f) + pose.spread * 24f)
            val r = dp(if (i < 2) 2.5f else 1.6f)
            canvas.drawOval(x - r, y - r * 0.35f, x + r, y + r * 0.35f, powerInk)
            canvas.drawOval(x - r * 0.35f, y - r, x + r * 0.35f, y + r, powerInk)
        }
    }

    private fun drawFace(canvas: Canvas, t: Float, now: Long, attention: DoomscrollMotion.Pose?) {
        val faceCx = screenRect.centerX()
        val gazeCx = faceCx + doomscrollSide * dp(attention?.gazeX ?: 0f)
        val eyeY = screenRect.top + screenRect.height() * 0.36f + dp(attention?.gazeY ?: 0f)
        val eyeDx = dp(9.5f)
        val eyeR = dp(3.2f)
        val blinking = now < blinkEndsAt

        when {
            blinking || mood == Mood.SLEEPY -> {
                canvas.drawLine(gazeCx - eyeDx - eyeR, eyeY, gazeCx - eyeDx + eyeR, eyeY, facePaint)
                canvas.drawLine(gazeCx + eyeDx - eyeR, eyeY, gazeCx + eyeDx + eyeR, eyeY, facePaint)
            }
            attention != null -> {
                for (side in -1..1 step 2) {
                    val x = gazeCx + side * eyeDx
                    canvas.drawCircle(x, eyeY, eyeR, accentPaint)
                    canvas.drawCircle(x + doomscrollSide * dp(1f), eyeY - dp(0.8f), dp(1f), screenPaint)
                }
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

    /**
     * Filled version of a stroke paint, for the little legs and antenna light. One scratch
     * paint is reused for all of them: this runs inside onDraw, every frame.
     */
    private val fillScratch = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun Paint.fillOnly(): Paint = fillScratch.apply {
        set(this@fillOnly)
        style = Paint.Style.FILL
    }

    companion object {
        /**
         * Minimum gap between redraws, in milliseconds, by what is on the move. These are the
         * numbers the loop keeps; anything describing a frame rate has to come from them.
         */
        private const val GAP_WALKING = 0L
        private const val GAP_TALKING = 33L
        private const val GAP_IDLE = 50L

        /**
         * What those gaps come to per second, for the Diagnostics readout.
         *
         * Worked out here rather than written out on that screen: a gap of 33 ms is thirty
         * frames a second, not thirty-three, and a hand-written copy of these figures has
         * already been wrong once.
         */
        fun ratesText(): String {
            fun rate(gap: Long) = (1_000L / gap).toString()
            return "about ${rate(GAP_IDLE)} a second idle, ${rate(GAP_TALKING)} talking, " +
                "every frame while walking"
        }
    }
}
