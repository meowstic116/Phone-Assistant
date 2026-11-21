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

    internal var gardenGrip: DragMotion.Point? = null
        set(value) { field = value; invalidate() }

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

    private class PickupBlend {
        private var from = 0f
        private var to = 0f
        private var at = 0L
        private var duration = 0L

        fun value(now: Long) = PickupMotion.blend(from, to, now - at, duration)
        fun set(target: Float, now: Long, durationMs: Long) {
            from = value(now)
            to = target
            at = now
            duration = durationMs
        }
        fun visible(now: Long) = to > 0f || from > 0f && now - at < duration
    }

    private val pickupLift = PickupBlend()
    private val pickupCarry = PickupBlend()
    private var gripped = false
    private var carriedAt = 0L
    private var pullAt = 0L
    private var lastPull = 0f

    internal fun setGrip(held: Boolean) {
        gripped = held
        val now = SystemClock.uptimeMillis()
        val duration = when {
            !ValueAnimator.areAnimatorsEnabled() -> 0L
            held -> PickupMotion.LIFT_MS
            else -> PickupMotion.RELEASE_MS
        }
        pickupLift.set(if (held) 1f else 0f, now, duration)
        if (!held) pickupCarry.set(0f, now, duration)
        invalidate()
    }

    internal fun startCarry() {
        if (!gripped) return
        carriedAt = SystemClock.uptimeMillis()
        lastPull = 0f
        pickupCarry.set(1f, carriedAt, if (ValueAnimator.areAnimatorsEnabled()) PickupMotion.LIFT_MS else 0L)
        invalidate()
    }

    internal fun pullPickup(horizontal: Float) {
        if (!gripped || kotlin.math.abs(horizontal) < 0.01f) return
        val now = SystemClock.uptimeMillis()
        lastPull = pullAt(now) * 0.45f + horizontal.coerceIn(-1f, 1f) * 0.55f
        pullAt = now
    }

    private fun pullAt(now: Long) = lastPull * (1f - (now - pullAt) / 170f).coerceIn(0f, 1f)

    private val carryInk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 1.8f
        strokeCap = Paint.Cap.ROUND
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

        if (blinkStarts || eyesChanged || now - lastDrawAt >= frameGapMs(now)) {
            lastDrawAt = now
            invalidate()
        }
    }

    /**
     * Walking and being carried need smooth redraws; talking is slower and idle is cheapest.
     */
    private fun frameGapMs(now: Long): Long = when {
        walking || pickupLift.visible(now) || pickupCarry.visible(now) -> GAP_WALKING
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
        val groundY = ToyFooting.feetY(0f, height, dp(1f))
        val cx = width / 2f

        val transition = powerPose
        val pickup = if (transition == null && (pickupLift.visible(now) || pickupCarry.visible(now))) {
            PickupMotion.pose(pickupLift.value(now), pickupCarry.value(now), now - carriedAt, pullAt(now),
                ValueAnimator.areAnimatorsEnabled())
        } else null
        val carry = pickup?.carry ?: 0f
        val carried = carry > 0.12f
        val attention = if (carried || doomscrollCue == DoomscrollMotion.Cue.CLEAR) null else
            DoomscrollMotion.pose(doomscrollCue, now - doomscrollAt, ValueAnimator.areAnimatorsEnabled())
        val powerLayer = if (transition != null) {
            drawPowerSpark(canvas, cx, groundY, transition)
            if (transition.opacity <= 0f) return
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                (255 * transition.opacity).toInt())
            canvas.translate(0f, -dp(transition.lift))
            canvas.scale(transition.sx, transition.sy, cx, groundY)
            layer
        } else -1

        val pickupLayer = if (pickup != null) {
            val layer = canvas.save()
            canvas.translate(0f, -dp(pickup.lift))
            canvas.scale(pickup.scale, pickup.scale, cx, height * 0.5f)
            layer
        } else -1

        val onFloor = sitting && !carried
        val bob = when {
            carry > 0f -> dp(carry)
            onFloor -> 0f
            walking -> kotlin.math.abs(kotlin.math.sin(t * 9f)) * dp(2.4f)
            else -> kotlin.math.sin(t * 2.1f) * dp(1.1f)
        }
        val tilt = when {
            onFloor -> 0f
            walking -> kotlin.math.sin(t * 9f) * 5f
            else -> kotlin.math.sin(t * 1.3f) * 1.4f
        }
        val bodyCenterY = if (onFloor) {
            groundY - dp(13f) - bodyH / 2f
        } else {
            groundY - legH - bodyH / 2f - bob
        }

        if (onFloor) {
            // Folded legs: just a flat little puddle under him.
            bodyRect.set(cx - dp(24f), groundY - dp(8f), cx + dp(24f), groundY)
            canvas.drawRoundRect(bodyRect, dp(4f), dp(4f), rimPaint.fillOnly())
        } else {
            // Stubby legs, alternating while walking.
            val stepPhase = if (walking) kotlin.math.sin(t * 9f) else 0f
            val legW = dp(ToyFooting.SHOE_WIDTH_DP)
            val legTop = groundY - legH - bob
            val leftLift = if (walking) maxOf(0f, stepPhase) * dp(3.5f) else 0f
            val rightLift = if (walking) maxOf(0f, -stepPhase) * dp(3.5f) else 0f

            fun foot(left: Float, lift: Float, angle: Float) {
                val saved = if (angle != 0f) canvas.save() else -1
                if (saved != -1) canvas.rotate(angle, left + legW / 2f, legTop)
                bodyRect.set(left, legTop - lift, left + legW, groundY - lift - dp(carry * 0.7f))
                canvas.drawRoundRect(bodyRect, dp(3f), dp(3f), rimPaint.fillOnly())
                if (saved != -1) canvas.restoreToCount(saved)
            }
            val spread = dp(ToyFooting.SHOE_CENTRE_DP)
            foot(cx - spread - legW / 2f, leftLift, pickup?.leftFoot ?: 0f)
            foot(cx + spread - legW / 2f, rightLift, pickup?.rightFoot ?: 0f)
        }

        canvas.save()
        canvas.translate(cx, bodyCenterY)
        val poseTilt = tilt + gardenLean * 10f
        canvas.rotate((if (facingRight) poseTilt else -poseTilt) + toyReachSide * toyReach * 4f +
            doomscrollSide * (attention?.lean ?: 0f) + (pickup?.tilt ?: 0f))

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

        if (carried) drawCarryFace(canvas, carry) else drawFace(canvas, t, now, attention)
        if (carry > 0f) drawCarryArms(canvas, carry, pickup?.tilt ?: 0f)
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
        canvas.restore()
        gardenGrip?.let { grip ->
            val shoulder = cx + WateringPose.direction(facingRight) * dp(17f)
            // Body tilt is decorative; the hand stays on the can's fixed pivot.
            canvas.drawLine(shoulder, bodyCenterY - dp(8f), grip.x, grip.y, facePaint)
            canvas.drawCircle(grip.x, grip.y, dp(2.5f), accentPaint)
        }
        if (pickupLayer != -1) canvas.restoreToCount(pickupLayer)
        if (powerLayer != -1) canvas.restoreToCount(powerLayer)
    }

    private fun drawCarryFace(canvas: Canvas, carry: Float) {
        val eyeY = screenRect.top + screenRect.height() * 0.34f
        for (side in -1..1 step 2) {
            val x = side * dp(9.5f)
            canvas.drawOval(x - dp(3.4f), eyeY - dp(4f), x + dp(3.4f), eyeY + dp(4f), accentPaint)
            canvas.drawCircle(x + side * dp(0.7f), eyeY + dp(0.5f), dp(1.3f), screenPaint)
        }
        val mouthY = screenRect.top + screenRect.height() * 0.65f
        canvas.drawOval(-dp(2.1f + carry * 0.8f), mouthY - dp(2.1f),
            dp(2.1f + carry * 0.8f), mouthY + dp(2.7f), facePaint.fillOnly())
    }

    private fun drawCarryArms(canvas: Canvas, carry: Float, tilt: Float) {
        carryInk.color = accentPaint.color
        carryInk.alpha = (255 * carry).toInt()
        for (side in -1..1 step 2) {
            val handX = side * dp(22f + 4.5f * carry)
            val handY = dp(8f - (4f + side * tilt * 0.45f) * carry)
            canvas.drawLine(side * dp(22f), dp(10f), handX, handY, carryInk)
            canvas.drawCircle(handX, handY, dp(1.8f), carryInk)
        }
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
