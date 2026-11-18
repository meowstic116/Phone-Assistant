package com.phoneassistant

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A sheet of bubble wrap. Tap bubbles to pop them — and Pip has a go too: he walks over,
 * presses a few himself, says something about it, and leaves the rest to you.
 */
class BubbleWrap(host: PropHost) : Prop(host) {

    override val boxW = 198f
    override val boxH = 246f

    private val cols = 5
    private val rows = 7
    private val total = cols * rows

    private val popped = BooleanArray(total)
    private val burstAt = LongArray(total)
    private var count = 0
    private var removeAt = 0L

    /** His turn: a few bubbles, then he leaves it alone. */
    private var playStarted = false
    private var pipPops = 0
    private var nextPipPop = 0L

    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheetHeight = -1f
    private val sheetEdge = stroke(0xFF93A3B6.toInt(), 1.2f)
    private val streak = stroke(0x77FFFFFF.toInt(), 2.4f)

    /** One dome paint, recoloured per bubble so the sheet does not look printed. */
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tints = intArrayOf(0xFFF8FBFF.toInt(), 0xFFEAF1FC.toInt(), 0xFFF2F8FF.toInt())
    private val domeBase = fill(0x40859CC4.toInt())
    private val domeShadow = fill(0x2E3A4A63.toInt())
    private val domeRim = stroke(0xCCFFFFFF.toInt(), 1.1f)
    private val domeGloss = fill(0xE6FFFFFF.toInt())

    /** A band of light that crosses the plastic now and then: the sheet's only idle motion. */
    private val sheen = Sheen(dp(86f), 0x14FFFFFF.toInt(), SHEEN_PERIOD_MS)
    private val sheetClip = Path()
    private val sheetRect = RectF()

    private val crater = fill(0x2A5A6A82.toInt())
    private val craterRim = stroke(0x3D5A6A82.toInt(), 0.9f)
    private val burstRing = stroke(0xFFFFFFFF.toInt(), 1.3f)
    private val spark = fill(0xFFFFFFFF.toInt())

    /** Scratch rect, reused every frame: onDraw runs on the main thread only. */
    private val area = RectF()

    private fun pad(): Float = dp(9f)
    private fun cellW(): Float = (width - pad() * 2f) / cols
    private fun cellH(): Float = (height - pad() * 2f) / rows

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()

        // Plastic with a light running across it, so it reads as a sheet and not a card.
        if (sheetHeight != h) {
            sheet.shader = LinearGradient(
                0f, 0f, w * 0.4f, h,
                0xFFF9FBFF.toInt(), 0xFFC9D6E9.toInt(), Shader.TileMode.CLAMP
            )
            sheetHeight = h
        }
        area.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        canvas.drawRoundRect(area, dp(16f), dp(16f), sheet)
        canvas.drawRoundRect(area, dp(16f), dp(16f), sheetEdge)
        canvas.drawLine(dp(18f), dp(38f), dp(66f), dp(10f), streak)

        val cw = cellW()
        val ch = cellH()
        val radius = minOf(cw, ch) * 0.30f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = pad() + cw * (col + 0.5f)
                val cy = pad() + ch * (row + 0.5f)
                val index = row * cols + col
                if (popped[index]) {
                    drawCrater(canvas, cx, cy, radius, index, now)
                } else {
                    drawDome(canvas, cx, cy, radius, index)
                }
            }
        }

        drawSheen(canvas, now)
    }

    /**
     * Plastic catches the light as it settles. Slow, faint, and clipped to the sheet, so an
     * untouched sheet is not a still image — without redrawing it the rest of the time.
     */
    private fun drawSheen(canvas: Canvas, now: Long) {
        if (!sheen.visible(now, bornAt)) return

        val left = dp(1f)
        val top = dp(1f)
        val right = width - dp(1f)
        val bottom = height - dp(1f)
        sheetRect.set(left, top, right, bottom)
        sheetClip.reset()
        sheetClip.addRoundRect(sheetRect, dp(16f), dp(16f), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(sheetClip)
        sheen.draw(canvas, left, top, right, bottom, now, bornAt)
        canvas.restore()
    }

    /** A full bubble: shadow underneath, pale dome, rim, one gloss and a bounce of light. */
    private fun drawDome(canvas: Canvas, cx: Float, cy: Float, r: Float, index: Int) {
        canvas.drawCircle(cx + r * 0.06f, cy + r * 0.2f, r * 0.96f, domeShadow)
        canvas.drawCircle(cx, cy, r, domeBase)
        dome.color = tints[index % tints.size]
        canvas.drawCircle(cx - r * 0.04f, cy - r * 0.07f, r * 0.84f, dome)
        canvas.drawCircle(cx, cy, r * 0.96f, domeRim)
        area.set(cx - r * 0.6f, cy - r * 0.7f, cx + r * 0.04f, cy - r * 0.14f)
        canvas.drawOval(area, domeGloss)
        area.set(cx - r * 0.62f, cy + r * 0.08f, cx + r * 0.62f, cy + r * 0.96f)
        canvas.drawArc(area, 25f, 62f, false, domeRim)
    }

    /** A flattened bubble, its crease, and a short burst when it has just gone. */
    private fun drawCrater(canvas: Canvas, cx: Float, cy: Float, r: Float, index: Int, now: Long) {
        canvas.drawCircle(cx, cy, r * 0.66f, crater)
        canvas.drawCircle(cx, cy, r * 0.66f, craterRim)
        area.set(cx - r * 0.52f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.52f)
        canvas.drawArc(area, 198f, 72f, false, craterRim)

        if (burstAt[index] == 0L) return
        val since = now - burstAt[index]
        if (since !in 0..BURST_MS) return

        val t = since / BURST_MS.toFloat()
        val spread = r * (0.6f + t * 1.4f)
        burstRing.alpha = ((1f - t) * 200f).toInt()
        canvas.drawCircle(cx, cy, spread, burstRing)
        spark.alpha = ((1f - t) * 220f).toInt()
        for (corner in 0 until 4) {
            val angle = (corner * 90 + 45) * DEGREES
            canvas.drawCircle(
                cx + cos(angle) * spread,
                cy + sin(angle) * spread,
                dp(0.5f) + dp(1.4f) * (1f - t),
                spark
            )
        }
    }

    override fun onTap(x: Float, y: Float) {
        val index = indexAt(x, y) ?: return
        if (popped[index]) return
        pop(index, SystemClock.uptimeMillis(), byPip = false)
    }

    private fun indexAt(x: Float, y: Float): Int? {
        val col = ((x - pad()) / cellW()).toInt()
        val row = ((y - pad()) / cellH()).toInt()
        if (col !in 0 until cols || row !in 0 until rows) return null
        return row * cols + col
    }

    private fun pop(index: Int, now: Long, byPip: Boolean) {
        popped[index] = true
        burstAt[index] = now
        count++
        invalidate()

        if (count == total) {
            host.feel(PetView.Mood.HAPPY, 3500L)
            host.say("every single one. that was thorough.")
            removeAt = now + CLEAR_DELAY_MS
            return
        }
        if (byPip) return
        when (count) {
            1 -> {
                host.feel(PetView.Mood.HAPPY, 1200L)
                host.say("right. we're doing this.")
            }
            10 -> host.say("ten. you're taking this seriously.")
            total / 2 -> host.say("halfway. no comment.")
        }
    }

    override fun onFrame() {
        val now = SystemClock.uptimeMillis()

        if (removeAt > 0L && now > removeAt) {
            removeAt = 0L
            host.dropProp(this)
            return
        }

        if (!playStarted && isAttachedToWindow) {
            playStarted = true
            nextPipPop = now + 900L
            // He comes over first, so the bubbles that go are the ones under him.
            host.petWalkTo(
                centreX,
                posY + windowH + host.petHeight() * 0.5f + dp(4f),
                null
            )
        }

        if (pipPops < PIP_POPS && count < total && now > nextPipPop &&
            host.petIsNear(centreX, posY + windowH, dp(140f))
        ) {
            pressBubble(now)
        }

        // Repaint for a burst, or while the light is crossing the sheet. Nothing else on an
        // untouched sheet changes, so the rest of the cycle costs nothing.
        if (burstAt.any { it > 0L && now - it in 0..BURST_MS }) {
            invalidate()
        } else if (sheen.visible(now, bornAt)) {
            invalidate()
        }
    }

    /** One bubble, pressed by him: taken from the bottom rows, nearest where he stands. */
    private fun pressBubble(now: Long) {
        val nearest = (rows - 1 downTo 0).asSequence()
            .flatMap { row -> (0 until cols).asSequence().map { col -> row * cols + col } }
            .filter { !popped[it] }
            .take(6)
            .toList()
        val index = nearest.randomOrNull() ?: return
        pop(index, now, byPip = true)
        pipPops++
        host.feel(PetView.Mood.HAPPY, 900L)
        if (pipPops == 1) host.say("*presses one* still satisfying.")
        nextPipPop = now + 1300L + Random.nextLong(900L)
    }

    override fun describe(): String =
        "a sheet of bubble wrap with $count of $total bubbles popped, some by you"

    companion object {
        /** How long the little burst stays on screen after a pop. */
        private const val BURST_MS = 260L

        /** How long the finished sheet stays up before he clears it away. */
        private const val CLEAR_DELAY_MS = 4_200L

        /** How many bubbles he helps with before leaving the rest to them. */
        private const val PIP_POPS = 3

        private const val DEGREES = Math.PI.toFloat() / 180f

        private const val SHEEN_PERIOD_MS = 5_600L
    }
}
