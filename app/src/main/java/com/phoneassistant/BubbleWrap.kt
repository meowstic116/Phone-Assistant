package com.phoneassistant

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock

/**
 * A sheet of bubble wrap. Tap bubbles to pop them; Pip keeps count, gets competitive
 * about it, and clears the sheet away once every bubble is gone.
 */
class BubbleWrap(host: PropHost) : Prop(host) {

    override val boxW = 198f
    override val boxH = 246f

    private val cols = 5
    private val rows = 7
    private val total = cols * rows

    private val popped = BooleanArray(total)
    private var count = 0
    private var removeAt = 0L

    private val sheet = fill(0xE6E9EEF5.toInt())
    private val sheetEdge = stroke(0xFF9AA6B8.toInt(), 1.2f)
    private val bubble = fill(0x33FFFFFF)
    private val bubbleRing = stroke(0x66B9C6D6.toInt(), 1f)
    private val flatBubble = stroke(0x33808FA6.toInt(), 0.8f)
    private val header = text(0xFF5C6675.toInt(), 7.5f, true)

    private fun cellW(): Float = (width - dp(16f)) / cols
    private fun cellH(): Float = (height - dp(30f)) / rows

    override fun onDraw(canvas: Canvas) {
        panel(
            canvas,
            RectF(dp(1f), dp(1f), width - dp(1f), height - dp(1f)),
            14f, sheetEdge, sheet
        )
        centred(
            canvas,
            "BUBBLE WRAP  ·  $count / $total",
            width / 2f,
            dp(15f),
            header
        )

        val cw = cellW()
        val chh = cellH()
        val radius = minOf(cw, chh) * 0.34f

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = dp(8f) + cw * (col + 0.5f)
                val cy = dp(24f) + chh * (row + 0.5f)
                if (popped[row * cols + col]) {
                    canvas.drawCircle(cx, cy, radius * 0.55f, flatBubble)
                } else {
                    canvas.drawCircle(cx, cy, radius, bubble)
                    canvas.drawCircle(cx, cy, radius, bubbleRing)
                    canvas.drawCircle(
                        cx - radius * 0.3f, cy - radius * 0.3f,
                        radius * 0.22f, fill(0x66FFFFFF)
                    )
                }
            }
        }
    }

    override fun onTap(x: Float, y: Float) {
        val col = ((x - dp(8f)) / cellW()).toInt()
        val row = ((y - dp(24f)) / cellH()).toInt()
        if (col !in 0 until cols || row !in 0 until rows) return

        val index = row * cols + col
        if (popped[index]) return

        popped[index] = true
        count++
        invalidate()

        when {
            count == 1 -> {
                host.feel(PetView.Mood.HAPPY, 1200L)
                host.say("oh. oh we're doing this. okay.")
            }
            count == 10 -> host.say("ten already. your thumbs have no chill.")
            count == total / 2 -> host.say("halfway. I'm not saying I'm impressed. I'm saying nothing.")
            count == total -> {
                host.feel(PetView.Mood.HAPPY, 3500L)
                host.say("ALL of them. every single bubble. you're a menace and I respect it.")
                removeAt = SystemClock.uptimeMillis() + 4200L
            }
        }
    }

    override fun onFrame() {
        if (removeAt > 0L && SystemClock.uptimeMillis() > removeAt) {
            removeAt = 0L
            host.dropProp(this)
        }
    }

    override fun describe(): String =
        "a sheet of bubble wrap with $count of $total bubbles popped (by you)"
}
