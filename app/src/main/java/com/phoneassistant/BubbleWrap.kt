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
import com.phoneassistant.PopSheet.Milestone

/**
 * A sheet of bubble wrap. You can tap bubbles, or let Pip hop through every bubble left.
 */
class BubbleWrap(host: PropHost) : Prop(host) {

    override val boxW = 150f
    override val boxH = 154f

    private val game = PopSheet()
    private val cols = game.columns
    private val rows = game.rows
    private val total = game.total
    private val burstAt = LongArray(total)
    private var removeAt = 0L
    private val hops = BubbleHopSchedule(bornAt)

    // If play is already going, let the remaining jumps and the victory line finish
    // instead of cutting him off at the usual idle timeout.
    internal override fun keepsToy(now: Long): Boolean =
        removeAt > now || (game.count < total && hops.keepsSheet(now))

    internal fun reservesPetInput(now: Long): Boolean = game.count < total && hops.reservesInput(now)

    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheetHeight = -1f
    private val sheetEdge = stroke(0xFF392557.toInt(), 2.2f)
    private val streak = stroke(0x9CFFFFFF.toInt(), 2.4f)
    private val light = stroke(0xAAFFF9E7.toInt(), 1.2f)
    private val confetti = intArrayOf(0xFFFF76A3.toInt(), 0xFFFFD16A.toInt(), 0xFF71E6C0.toInt(), 0xFF7ECAFF.toInt())

    /** One dome paint, recoloured per bubble so the sheet does not look printed. */
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tints = intArrayOf(0xFFFFA2C1.toInt(), 0xFFFFD688.toInt(), 0xFF9CF0CA.toInt(), 0xFF94D9FF.toInt(), 0xFFC5A8FF.toInt())
    private val domeBase = fill(0xD59066C9.toInt())
    private val domeShadow = fill(0x583B255D)
    private val domeRim = stroke(0xFF65438E.toInt(), 1.15f)
    private val domeGloss = fill(0xE6FFFFFF.toInt())

    /** A band of light that crosses the plastic now and then: the sheet's only idle motion. */
    private val sheen = Sheen(dp(86f), 0x14FFFFFF.toInt(), SHEEN_PERIOD_MS)
    private val sheetClip = Path()
    private val sheetRect = RectF()

    private val crater = fill(0x4A553477)
    private val craterRim = stroke(0x7F5C3C83, 1.0f)
    private val burstRing = stroke(0xFFFFFFFF.toInt(), 1.3f)
    private val spark = fill(0xFFFFFFFF.toInt())

    /** Scratch rect, reused every frame: onDraw runs on the main thread only. */
    private val area = RectF()

    private fun pad(): Float = dp(9f)
    private fun gridTop(): Float = pad()
    private fun gridBottom(): Float = height - dp(9f)
    private fun cellW(): Float = (width - pad() * 2f) / cols
    private fun cellH(): Float = (gridBottom() - gridTop()) / rows

    private fun domeRadius() = minOf(cellW(), cellH()) * 0.39f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()

        // Plastic with a light running across it, so it reads as a sheet and not a card.
        if (sheetHeight != h) {
            sheet.shader = LinearGradient(
                0f, 0f, w * 0.4f, h,
                0xFFF1C9FF.toInt(), 0xFFC28CE9.toInt(), Shader.TileMode.CLAMP
            )
            sheetHeight = h
        }
        area.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        canvas.drawRoundRect(area, dp(16f), dp(16f), sheet)
        canvas.drawRoundRect(area, dp(16f), dp(16f), sheetEdge)
        canvas.drawLine(dp(12f), h - dp(5f), w - dp(12f), h - dp(5f), streak)

        val cw = cellW()
        val ch = cellH()
        val radius = domeRadius()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = pad() + cw * (col + 0.5f)
                val cy = gridTop() + ch * (row + 0.5f)
                val index = row * cols + col
                if (game.popped(index)) {
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
        dome.color = tints[(index / cols + index % cols) % tints.size]
        canvas.drawCircle(cx - r * 0.04f, cy - r * 0.07f, r * 0.84f, dome)
        canvas.drawCircle(cx, cy, r * 0.96f, domeRim)
        area.set(cx - r * 0.6f, cy - r * 0.7f, cx + r * 0.04f, cy - r * 0.14f)
        canvas.drawOval(area, domeGloss)
        area.set(cx - r * 0.62f, cy + r * 0.08f, cx + r * 0.62f, cy + r * 0.96f)
        canvas.drawArc(area, 25f, 62f, false, light)
        canvas.drawCircle(cx + r * 0.43f, cy - r * 0.33f, r * 0.11f, domeGloss)
    }

    /** A flattened bubble, its crease, and a short burst when it has just gone. */
    private fun drawCrater(canvas: Canvas, cx: Float, cy: Float, r: Float, index: Int, now: Long) {
        canvas.drawCircle(cx, cy, r * 0.66f, crater)
        canvas.drawCircle(cx, cy, r * 0.66f, craterRim)
        area.set(cx - r * 0.52f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.52f)
        canvas.drawArc(area, 198f, 72f, false, craterRim)
        canvas.drawLine(cx - r * 0.23f, cy - r * 0.16f, cx + r * 0.24f, cy + r * 0.18f, craterRim)

        if (burstAt[index] == 0L) return
        val since = now - burstAt[index]
        if (since !in 0..BURST_MS) return

        val t = since / BURST_MS.toFloat()
        val spread = r * (0.6f + t * 1.4f)
        burstRing.alpha = ((1f - t) * 200f).toInt()
        canvas.drawCircle(cx, cy, spread, burstRing)
        for (corner in 0 until 8) {
            val angle = (corner * 45 + 23) * DEGREES
            spark.color = confetti[(corner + index) % confetti.size]
            spark.alpha = ((1f - t) * 245f).toInt()
            val x = cx + cos(angle) * spread
            val y = cy + sin(angle) * spread
            val r1 = dp(0.5f) + dp(2.1f) * (1f - t)
            canvas.drawRoundRect(x - r1, y - r1, x + r1, y + r1, r1 * 0.35f, r1 * 0.35f, spark)
        }
    }

    override fun onTap(x: Float, y: Float) {
        val index = indexAt(x, y) ?: return
        if (game.popped(index)) return
        pop(index, SystemClock.uptimeMillis(), byPip = false)
    }

    private fun indexAt(x: Float, y: Float): Int? {
        if (x < pad() || x >= width - pad() || y < gridTop() || y >= gridBottom()) return null
        val col = ((x - pad()) / cellW()).toInt()
        val row = ((y - gridTop()) / cellH()).toInt()
        if (col !in 0 until cols || row !in 0 until rows) return null
        return row * cols + col
    }

    private fun pop(index: Int, now: Long, byPip: Boolean) {
        val milestone = game.pop(index, byPip) ?: return
        burstAt[index] = now
        invalidate()
        // The whole toy is this one sound, so it is the loudest thing in the box: a pop with
        // a little more body under it when Pip lands on one, since his weight is the joke.
        sound(ToyAudio.Sound.POP, if (byPip) 1f else 0.85f)

        if (milestone == Milestone.COMPLETE) {
            host.feel(PetView.Mood.HAPPY, 3500L)
            host.sound(ToyAudio.Sound.SPARKLE, 0.9f)
            host.say(Lines.pick(if (byPip) PIP_FINISH_LINES else ALL_POPPED_LINES))
            removeAt = now + CLEAR_DELAY_MS
            return
        }
        val lines = if (byPip) when (milestone) {
            Milestone.FIRST -> PIP_POP_LINES
            Milestone.FIVE -> PIP_FIVE_LINES
            Milestone.TEN -> PIP_TEN_LINES
            Milestone.FIFTEEN -> PIP_FIFTEEN_LINES
            else -> null
        } else when (milestone) {
            Milestone.FIRST -> FIRST_POP_LINES
            Milestone.HALFWAY -> HALFWAY_LINES
            else -> null
        }
        if (lines != null) {
            host.feel(PetView.Mood.HAPPY, 1_200L)
            host.say(Lines.pick(lines))
        }
    }

    override fun onFrame() {
        val now = SystemClock.uptimeMillis()

        if (!held && removeAt > 0L && now > removeAt) {
            removeAt = 0L
            host.dropProp(this)
            return
        }

        if (!movingByUser && width > 0 && height > 0 && hops.due(now)) {
            game.nextBubble()?.let { jumpTo(it, now) }
        }

        // Repaint for a burst, or while the light is crossing the sheet. Nothing else on an
        // untouched sheet changes, so the rest of the cycle costs nothing.
        var repaintBurst = false
        for (i in burstAt.indices) {
            if (burstAt[i] == 0L) continue
            repaintBurst = true
            if (now - burstAt[i] > BURST_MS) burstAt[i] = 0L
        }
        if (repaintBurst || sheen.visible(now, bornAt)) {
            invalidate()
        }
    }

    private fun jumpTo(index: Int, now: Long) {
        val cx = posX.toInt() + pad() + cellW() * (index % cols + 0.5f)
        val cy = posY.toInt() + gridTop() + cellH() * (index / cols + 0.5f)
        val landing = bubbleLanding(cx, cy, domeRadius(), host.petCentreX(), host.petWidth(), host.stageWidth(), dp(1f))
        val started = host.petHopOn(this, landing.x, landing.y) { landed ->
            val at = SystemClock.uptimeMillis()
            hops.finished(at, landed)
            if (landed && !leaving) pop(index, at, byPip = true)
        }
        hops.attempted(now, started)
    }

    override fun describe(): String =
        "a sheet of bubble wrap with ${game.count} of $total bubbles popped, ${game.pipCount} by Pip"

    companion object {
        /**
         * Milestone lines, in sets rather than one each: a sheet is a little ritual, and the
         * same four sentences every time made it feel like a script being read out.
         */
        val FIRST_POP_LINES = listOf(
            "and so it begins. best sound on this entire phone.",
            "one down. I felt that in my antenna.",
            "that's the ice broken. the sheet is now a project."
        )

        val HALFWAY_LINES = listOf(
            "halfway. this is the best part of any parcel, and it isn't close.",
            "half the sheet gone. I've never been prouder of packaging.",
            "halfway. pace yourself. it's a marathon of tiny pops."
        )

        val ALL_POPPED_LINES = listOf(
            "every single one. honestly? a masterclass.",
            "all of them. *quiet applause* most productive thing this phone has done today.",
            "finished. I'd offer a trophy, but you'd only pop that too."
        )

        private val PIP_POP_LINES = listOf(
            "*hop* that's one. excellent floor.",
            "first pop. these legs finally have a job."
        )
        private val PIP_FIVE_LINES = listOf(
            "five hops. this is basically a tiny gym.",
            "five. I would like to thank my left foot."
        )
        private val PIP_TEN_LINES = listOf(
            "ten pops! I'm calling this a performance.",
            "double digits. the cardboard never stood a chance."
        )
        private val PIP_FIFTEEN_LINES = listOf(
            "fifteen. not even out of pixels.",
            "fifteen jumps. I'll be accepting an extremely small medal."
        )
        private val PIP_FINISH_LINES = listOf(
            "*sticks the landing* last bubble. that's a clean sheet.",
            "no bubbles left. my feet would like a tiny round of applause.",
            "and that's the last hop. I retire undefeated in packaging."
        )

        /** How long the little burst stays on screen after a pop. */
        private const val BURST_MS = 260L

        /** How long the finished sheet stays up before he clears it away. */
        private const val CLEAR_DELAY_MS = 4_200L

        private const val DEGREES = Math.PI.toFloat() / 180f

        private const val SHEEN_PERIOD_MS = 5_600L
    }
}
