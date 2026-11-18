package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.os.SystemClock
import kotlin.math.abs

/**
 * A pot plant. Tap to water it and it grows a stage at a time; tap it too fast and Pip
 * accuses you of drowning it.
 */
class Plant(host: PropHost) : Prop(host) {

    override val boxW = 98f
    override val boxH = 134f

    private val maxStage = 4
    private var stage = 0
    private var water = 0
    private var lastWaterAt = 0L
    private var growAt = 0L
    private var warnedFlood = false
    private var dropUntil = 0L

    /** Degrees of lean toward Pip, eased rather than set, so a step is a sway and not a jump. */
    private var lean = 0f

    private val pot = fill(0xFFC46B4B.toInt())
    private val potEdge = stroke(0xFF8B4530.toInt(), 1.2f)
    private val soil = fill(0xFF4A3A2E.toInt())
    private val stem = stroke(0xFF4E8B52.toInt(), 2.2f)
    private val leaf = fill(0xFF74B97A.toInt())
    private val petal = fill(0xFFF2796B.toInt())
    private val centre = fill(0xFFFFD166.toInt())
    private val droplet = fill(0xCC7DD3FC.toInt())
    private val hint = text(0xFF6B7A8C.toInt(), 6.5f)
    private val path = Path()

    override fun onTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val tooQuick = now - lastWaterAt < 320L
        lastWaterAt = now
        dropUntil = now + 500L

        if (tooQuick) {
            water++
            if (water >= 5 && !warnedFlood) {
                warnedFlood = true
                water = 0
                host.feel(PetView.Mood.ANNOYED, 2400L)
                host.say(Lines.pick(OVERWATER_LINES))
            }
            invalidate()
            return
        }

        if (stage >= maxStage) {
            host.feel(PetView.Mood.SMUG, 2000L)
            host.say(Lines.pick(FULL_LINES))
            return
        }

        water++
        if (water >= 3) {
            water = 0
            growAt = now + 1400L
            host.feel(PetView.Mood.HAPPY, 1400L)
        }
        invalidate()
    }

    /**
     * Plants turn toward the light, and here the light is a small phone-shaped creature
     * standing somewhere on the same screen.
     */
    private fun leanTowardsPip() {
        val target = if (!host.petOnScreen()) {
            0f
        } else {
            ((host.petCentreX() - centreX) / dp(240f)).coerceIn(-1f, 1f) * 8f
        }
        val next = lean + (target - lean) * 0.08f
        if (abs(next - lean) < 0.02f) return
        lean = next
        invalidate()
    }

    override fun onFrame() {
        val now = SystemClock.uptimeMillis()
        leanTowardsPip()
        if (dropUntil > 0L && now > dropUntil) {
            dropUntil = 0L
            invalidate()
        }
        if (warnedFlood && now - lastWaterAt > 4000L) warnedFlood = false

        if (growAt > 0L && now >= growAt) {
            growAt = 0L
            stage++
            invalidate()
            if (stage >= maxStage) {
                host.feel(PetView.Mood.SMUG, 4000L)
                host.say("*pats the pot* we grew that. well. I grew that. you watered.")
            } else {
                host.say(GROW_LINES[(stage - 1).coerceIn(0, GROW_LINES.size - 1)])
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val potTop = h - dp(38f)

        // Water still soaking in.
        if (dropUntil > 0L) {
            for (i in 0 until 3) {
                canvas.drawCircle(
                    cx - dp(10f) + dp(10f) * i,
                    potTop - dp(34f) - dp(6f) * i,
                    dp(2.4f),
                    droplet
                )
            }
        }

        // Stem and leaves, one stage at a time.
        val growth = stage / maxStage.toFloat()
        val stemTop = if (stage == 0) potTop - dp(4f) else potTop - dp(10f) - dp(46f) * growth
        if (stage > 0) {
            canvas.save()
            canvas.rotate(lean, cx, potTop)
            path.reset()
            path.moveTo(cx, potTop)
            path.quadTo(cx + dp(6f), (potTop + stemTop) / 2f, cx, stemTop)
            canvas.drawPath(path, stem)

            for (i in 1..stage) {
                val ly = potTop - dp(6f) - dp(10f) * i
                val side = if (i % 2 == 0) 1f else -1f
                path.reset()
                path.moveTo(cx, ly)
                path.quadTo(cx + side * dp(16f), ly - dp(10f), cx + side * dp(23f), ly - dp(2f))
                path.quadTo(cx + side * dp(14f), ly + dp(4f), cx, ly)
                canvas.drawPath(path, leaf)
            }
            canvas.restore()
        }

        if (stage >= maxStage) {
            canvas.save()
            canvas.rotate(lean, cx, potTop)
            for (i in 0 until 5) {
                val angle = Math.toRadians((i * 72).toDouble())
                canvas.drawCircle(
                    cx + dp(8f) * Math.cos(angle).toFloat(),
                    stemTop + dp(8f) * Math.sin(angle).toFloat(),
                    dp(5f), petal
                )
            }
            canvas.drawCircle(cx, stemTop, dp(4f), centre)
            canvas.restore()
        }

        // The pot itself, wider at the top.
        path.reset()
        path.moveTo(cx - dp(24f), potTop)
        path.lineTo(cx + dp(24f), potTop)
        path.lineTo(cx + dp(18f), h - dp(4f))
        path.lineTo(cx - dp(18f), h - dp(4f))
        path.close()
        canvas.drawPath(path, pot)
        canvas.drawPath(path, potEdge)
        canvas.drawRect(cx - dp(24f), potTop, cx + dp(24f), potTop + dp(7f), soil)

        centred(canvas, "water $water/3", cx, h - dp(20f), hint)
    }

    override fun describe(): String =
        if (stage >= maxStage) "a fully grown plant on your screen, covered in flowers"
        else "a pot plant at stage $stage of $maxStage that you were watering"

    companion object {
        val OVERWATER_LINES = listOf(
            "ease off. you're drowning it.",
            "that's plenty. it's not a fish.",
            "easy. the pot has limits."
        )

        val FULL_LINES = listOf(
            "that's full size. I'm a plant dad now.",
            "grown. that's as far as it goes.",
            "nothing left to water. it's finished."
        )

        val GROW_LINES = listOf(
            "*peers at the pot* something's happening. slowly.",
            "a new leaf. noted.",
            "it's growing. plants are just slow applause.",
            "*hovers* nearly there."
        )
    }
}
