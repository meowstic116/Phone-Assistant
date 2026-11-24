package com.phoneassistant

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.sin

private const val LEMON_INK = 0xFF352445.toInt()

/**
 * A little lemonade stand. Tap it and Pip pours you a glass; the glass is a separate
 * prop you can drag anywhere, drink from, and then throw away.
 */
class LemonadeStand(host: PropHost) : Prop(host) {

    override val boxW = 112f
    override val boxH = 148f

    private val edge = stroke(LEMON_INK, 2f)
    private val detail = stroke(LEMON_INK, 1.1f)
    private val ink = fill(LEMON_INK)
    private val mint = fill(0xFF9AEAD2.toInt())
    private val teal = fill(0xFF5BBCB3.toInt())
    private val mintShine = stroke(0xFFD9FFF0.toInt(), 1.7f)
    private val cream = fill(0xFFFFF5CE.toInt())
    private val lemon = fill(0xFFFFD75F.toInt())
    private val pink = fill(0xFFFFA1BA.toInt())
    private val awningShade = fill(0x28A33C72)
    private val glass = fill(0xFFCCF3F9.toInt())
    private val shadow = fill(0x44352445)
    private val signInk = text(LEMON_INK, 9.3f, true)
    private val tiny = text(LEMON_INK, 6.5f)
    private val shape = Path()
    private val awning = Path()
    private val sign = RectF()
    private val rect = RectF()
    private var artWidth = -1f

    private var postRequestedAt: Long? = null

    internal override fun keepsToy(now: Long): Boolean =
        postRequestedAt?.let { host.isPostOwner(this) && now - it < 45_000L } == true

    private fun buildArt(w: Float) {
        if (w == artWidth) return
        artWidth = w
        sign.set(dp(12f), dp(113f), w - dp(12f), dp(139f))
        // The six curved hems share the same outer path as the stripe mask and outline.
        val left = dp(3f)
        val seam = (w - dp(6f)) / 6f
        awning.rewind()
        awning.moveTo(dp(10f), dp(3f))
        awning.quadTo(dp(7f), dp(3f), dp(6f), dp(7f))
        awning.lineTo(left, dp(17f))
        for (i in 0 until 6) {
            awning.quadTo(left + seam * (i + 0.5f), dp(26f), left + seam * (i + 1), dp(17f))
        }
        awning.lineTo(w - dp(6f), dp(7f))
        awning.quadTo(w - dp(7f), dp(3f), w - dp(10f), dp(3f))
        awning.close()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        buildArt(w)

        // Keep the middle 54dp clear for Pip's face and the antenna below the canopy.
        canvas.drawOval(dp(4f), dp(138f), w - dp(4f), dp(147f), shadow)
        for (side in 0..1) {
            val x = if (side == 0) dp(9f) else w - dp(9f)
            rect.set(x - dp(3f), dp(16f), x + dp(3f), dp(103f))
            panel(canvas, rect, 2f, edge, mint)
            canvas.drawLine(x, dp(27f), x, dp(66f), mintShine)
        }
        rect.set(dp(10f), dp(104f), w - dp(10f), dp(144f))
        panel(canvas, rect, 8f, edge, teal)
        for (i in 1..3) {
            val x = dp(10f) + i * (w - dp(20f)) / 4f
            canvas.drawLine(x, dp(130f), x, dp(142f), detail)
        }

        // A lemon pitcher and a little stack of cups live at the counter's corners.
        canvas.drawRoundRect(dp(20f), dp(79f), dp(29f), dp(92f), dp(4f), dp(4f), edge)
        shape.rewind()
        shape.moveTo(dp(4f), dp(73f))
        shape.lineTo(dp(24f), dp(75f))
        shape.lineTo(dp(24f), dp(90f))
        shape.quadTo(dp(24f), dp(96f), dp(19f), dp(97f))
        shape.lineTo(dp(12f), dp(97f))
        shape.quadTo(dp(7f), dp(97f), dp(7f), dp(91f))
        shape.lineTo(dp(7f), dp(78f))
        shape.close()
        canvas.drawPath(shape, glass)
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawOval(dp(4f), dp(81f), dp(31f), dp(110f), lemon)
        canvas.drawLine(dp(10f), dp(80f), dp(10f), dp(89f), mintShine)
        canvas.restore()
        canvas.drawPath(shape, edge)
        canvas.drawCircle(dp(23f), dp(74f), dp(4.5f), cream)
        canvas.drawCircle(dp(23f), dp(74f), dp(4.5f), detail)
        canvas.drawCircle(dp(23f), dp(74f), dp(2.8f), lemon)
        for (i in 0..1) {
            val y = dp(81f + i * 4f)
            rect.set(w - dp(22f), y, w - dp(6f), y + dp(12f))
            panel(canvas, rect, 3f, detail, if (i == 0) pink else cream)
            canvas.drawLine(rect.left + dp(2f), y + dp(3f), rect.right - dp(2f), y + dp(3f), detail)
        }

        rect.set(dp(2f), dp(LemonadeLayout.COUNTER_TOP_DP), w - dp(2f), dp(112f))
        panel(canvas, rect, 5f, edge, mint)
        canvas.drawLine(dp(8f), dp(100f), w - dp(8f), dp(100f), mintShine)
        canvas.drawLine(cx - dp(24f), dp(109f), cx - dp(24f), dp(118f), detail)
        canvas.drawLine(cx + dp(24f), dp(109f), cx + dp(24f), dp(118f), detail)

        canvas.save()
        canvas.rotate(signSway(SystemClock.uptimeMillis()), cx, sign.top)
        panel(canvas, sign, 6f, edge, cream)
        centred(canvas, "LEMONADE", cx, sign.top + dp(8f), signInk)
        centred(canvas, "50¢ or a compliment", cx, sign.top + dp(19f), tiny)
        // Lemon stickers on the sign, with little mint leaves.
        for (side in -1..1 step 2) {
            val x = cx + side * dp(35f)
            val y = sign.top + dp(9f)
            canvas.drawOval(x - dp(4f), y - dp(3f), x + dp(4f), y + dp(3f), lemon)
            canvas.drawOval(x - dp(4f), y - dp(3f), x + dp(4f), y + dp(3f), detail)
            canvas.drawOval(x, y - dp(5f), x + dp(3f), y - dp(2.5f), teal)
        }
        canvas.restore()

        canvas.drawPath(awning, cream)
        canvas.save()
        canvas.clipPath(awning)
        val stripe = (w - dp(6f)) / 6f
        for (i in 0..5 step 2) {
            val left = dp(3f) + stripe * i
            canvas.drawRect(left, dp(3f), left + stripe, dp(24f), pink)
        }
        canvas.drawRect(dp(3f), dp(15f), w - dp(3f), dp(24f), awningShade)
        canvas.restore()
        canvas.drawPath(awning, edge)
        // A grinning lemon badge on the canopy, like the plant pot's little face.
        canvas.drawOval(cx - dp(9f), dp(4.5f), cx + dp(9f), dp(16.5f), lemon)
        canvas.drawOval(cx - dp(9f), dp(4.5f), cx + dp(9f), dp(16.5f), detail)
        canvas.drawCircle(cx - dp(3f), dp(9.5f), dp(0.9f), ink)
        canvas.drawCircle(cx + dp(3f), dp(9.5f), dp(0.9f), ink)
        canvas.drawArc(cx - dp(2f), dp(9.5f), cx + dp(2f), dp(13f), 10f, 160f, false, detail)
    }

    /**
     * The board's angle this instant: two slow sines, so it never repeats on an obvious
     * beat, and never swings far enough to look like it is falling off.
     */
    private fun signSway(now: Long): Float {
        val t = (now - bornAt) / 1000f
        return sin(t * 1.7f) * 1.2f + sin(t * 3.1f + 0.8f) * 0.4f
    }

    override fun onFrame() {
        // Keep the hanging sign swinging while the stand is out.
        invalidate()
    }

    private fun station() = LemonadeLayout.station(posX, posY, windowW, host.petHeight(), dp(1f))

    internal fun atCounter(): Boolean {
        if (!host.isPostOwner(this) || inToyMotion || movingByUser) return false
        val target = station()
        return host.petIsNear(target.x, target.y, dp(10f))
    }

    /** How many glasses are on the screen right now. */
    private fun glassesOut(): Int = host.propsOnScreen().count { it is LemonadeGlass }

    /** One jug, two glasses: whether there is space for another pour. */
    fun hasRoom(): Boolean = glassesOut() < 2

    /**
     * Pours a glass, beside the stand. Returns false when there is no room for another.
     * [announce] is false for the second glass of a pair, so he does not say the same serve
     * line twice in a row.
     */
    fun serve(announce: Boolean = true): Boolean {
        // A stand that has left the stage cannot pour for it: its window is only still on
        // the screen while it fades.
        if (leaving) return false
        if (!hasRoom()) {
            if (announce) {
                host.say(Lines.pick(NO_ROOM_LINES))
            }
            return false
        }

        val glass = LemonadeGlass(host)
        val gap = dp(5f)
        val rightRoom = host.stageWidth() - (posX + windowW)
        val x = if (rightRoom > glass.windowW + gap) {
            posX + windowW + gap
        } else {
            posX - glass.windowW - gap
        }
        val y = posY + windowH - glass.windowH - dp(2f)

        if (!glass.bring(x, y)) return false

        host.feel(PetView.Mood.HAPPY, 2200L)
        // Squeeze, then liquid: the two halves of the same action, spaced so they read in
        // order instead of piling onto one another.
        host.sound(ToyAudio.Sound.SQUEAK, 0.85f)
        host.postDelayedSound(ToyAudio.Sound.POUR, 130L, 0.8f)
        if (announce) host.say(Lines.pick(SERVE_LINES))
        return true
    }

    override fun onTap(x: Float, y: Float) {
        // This window is drawn over his, so taps aimed at him arrive here. Anything in the
        // box his body occupies above the counter is a poke, not an order.
        // Only while he is actually standing there: the same box over an empty counter is
        // not an invisible button.
        if (atCounter() && LemonadeLayout.isPipTap(x, y, width, dp(1f))) {
            host.pokePet()
            return
        }
        serve()
    }

    /**
     * Puts him behind the counter and keeps him there. A plain walk would not do: when he
     * arrived his idle timer would pick a new spot within seconds and leave the counter
     * empty, so the stand takes a post he holds until it is taken off the screen.
     */
    fun takePost(say: Boolean = false) {
        if (leaving) return
        postRequestedAt = SystemClock.uptimeMillis()
        val target = station()
        host.petHoldAt(this, target.x, target.y) {
            postRequestedAt = null
            if (say) host.say(Lines.pick(POST_LINES))
        }
    }

    override fun onDragFinished() = takePost(say = true)

    /** Serve as soon as the glass is actually placed; walking back is independent. */
    fun pourNow(): Boolean {
        val served = serve()
        takePost()
        return served
    }

    override fun describe(): String {
        val base = "your lemonade stand (glasses out right now: ${glassesOut()})"
        return if (atCounter()) {
            "$base, and you are standing behind the counter"
        } else {
            "$base, and you are not behind it right now"
        }
    }

    companion object {
        /** Long enough to read as two pours, short enough not to feel like a pause. */

        val NO_ROOM_LINES = listOf(
            "two glasses. I'm not made of lemons.",
            "that's the glass limit. the jug is doing its best.",
            "drink the ones you've got. the glassware budget is real."
        )

        val POST_LINES = listOf(
            "*dusts the counter* back to it. biggest business on this phone.",
            "back behind the counter. the queue is still just you. loyal, though.",
            "*straightens the sign* still fifty cents. still mostly lemon."
        )

        val SERVE_LINES = listOf(
            "one lemonade. fifty cents, or a compliment.",
            "*pours* the good jug. don't tell the other jug.",
            "mostly lemon. the rest is technique.",
            "freshly squeezed. give or take.",
            "*slides it over* one business, fully operational.",
            "the recipe is lemon and confidence. mostly confidence."
        )
    }
}

/**
 * One glass. First tap drinks it (and Pip takes it personally if you don't finish),
 * second tap clears the empty glass away.
 */
class LemonadeGlass(host: PropHost) : Prop(host) {

    override val boxW = 48f
    override val boxH = 68f

    private var drunk = false
    private var wobble = 0f

    private val rim = stroke(LEMON_INK, 1.9f)
    private val body = fill(0xD3D3FAFF.toInt())
    private val juice = fill(0xFFFFD75F.toInt())
    private val juiceShade = fill(0xFFF9B64E.toInt())
    private val shadow = fill(0x44352445)
    private val straw = stroke(0xFFFFA1BA.toInt(), 2.3f)
    private val strawEdge = stroke(LEMON_INK, 3.9f)
    private val shine = stroke(0xEBFFFFFF.toInt(), 1.3f)
    private val pith = stroke(0xFFECAF48.toInt(), 0.95f)
    private val ice = fill(0xFFFFF2CE.toInt())
    private val iceEdge = stroke(0xFFD99646.toInt(), 0.8f)
    private val glassPath = Path()
    private val liquid = Path()
    private val strawPath = Path()
    private val iceRect = RectF()

    override fun onTap(x: Float, y: Float) {
        if (!drunk) {
            drunk = true
            wobble = if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
            invalidate()
            host.feel(PetView.Mood.HAPPY, 1800L)
            // Two swallows: the second one shortens, which is how a real glass ends.
            host.sound(ToyAudio.Sound.GLUG, 0.75f)
            host.postDelayedSound(ToyAudio.Sound.GLUG, 210L, 0.6f)
            host.say(Lines.pick(SIP_LINES))
        } else {
            host.sound(ToyAudio.Sound.TAP, 0.6f)
            host.dropProp(this)
            host.say(Lines.pick(CLEAR_LINES))
        }
    }

    override fun onFrame() {
        if (wobble <= 0f) return
        wobble = (wobble - 0.06f).coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val top = dp(13f)
        val bottom = height - dp(6f)
        val topHalf = dp(16f)
        val foot = dp(10f)
        canvas.drawOval(cx - dp(19f), bottom - dp(2f), cx + dp(19f), bottom + dp(5f), shadow)
        canvas.save()
        canvas.rotate(sin((1f - wobble) * 14f) * wobble * 6f, cx, bottom - dp(8f))

        glassPath.rewind()
        glassPath.moveTo(cx - topHalf, top)
        glassPath.lineTo(cx + topHalf, top)
        glassPath.lineTo(cx + foot + dp(1f), bottom - dp(5f))
        glassPath.quadTo(cx + foot, bottom, cx + foot - dp(3f), bottom)
        glassPath.lineTo(cx - foot + dp(3f), bottom)
        glassPath.quadTo(cx - foot, bottom, cx - foot - dp(1f), bottom - dp(5f))
        glassPath.close()

        canvas.drawPath(glassPath, body)
        canvas.save()
        canvas.clipPath(glassPath)
        if (!drunk) {
            val level = top + dp(10f)
            liquid.rewind()
            liquid.moveTo(cx - topHalf, level)
            liquid.cubicTo(cx - dp(7f), level - dp(8f), cx + dp(6f), level + dp(7f), cx + topHalf, level)
            liquid.lineTo(cx + topHalf, bottom)
            liquid.lineTo(cx - topHalf, bottom)
            liquid.close()
            canvas.drawPath(liquid, juice)
            canvas.drawOval(cx - dp(21f), bottom - dp(6f), cx + dp(21f), bottom + dp(8f), juiceShade)
            cube(canvas, cx - dp(6f), level + dp(5f), -20f)
            cube(canvas, cx + dp(6f), level + dp(11f), 15f)
            canvas.drawCircle(cx - dp(5f), bottom - dp(15f), dp(1.3f), ice)
            canvas.drawCircle(cx + dp(4f), bottom - dp(8f), dp(1f), ice)
            canvas.drawCircle(cx + dp(7f), bottom - dp(20f), dp(1.2f), ice)
        } else {
            canvas.drawOval(cx - dp(5f), bottom - dp(4f), cx + dp(5f), bottom - dp(1f), juice)
        }
        canvas.drawLine(cx - dp(11f), top + dp(7f), cx - dp(8f), bottom - dp(8f), shine)
        canvas.restore()
        canvas.drawPath(glassPath, rim)

        val bendX = cx + dp(9f)
        val bendY = dp(4f)
        strawPath.rewind()
        strawPath.moveTo(cx + dp(4f), top + dp(13f))
        strawPath.lineTo(bendX, bendY)
        strawPath.lineTo(cx + dp(17f), bendY + dp(2f))
        canvas.drawPath(strawPath, strawEdge)
        canvas.drawPath(strawPath, straw)
        canvas.drawLine(bendX - dp(2f), bendY + dp(7f), bendX - dp(1.3f), bendY + dp(4.5f), shine)
        canvas.drawLine(cx - topHalf, top, cx + topHalf, top, rim)

        val wheelX = cx - dp(12f)
        val wheelY = top - dp(2f)
        canvas.drawCircle(wheelX, wheelY, dp(6.6f), juice)
        canvas.drawCircle(wheelX, wheelY, dp(6.6f), rim)
        canvas.drawCircle(wheelX, wheelY, dp(4.7f), ice)
        canvas.drawLine(wheelX - dp(4f), wheelY + dp(1.7f), wheelX + dp(4f), wheelY - dp(1.7f), pith)
        canvas.drawLine(wheelX - dp(1.8f), wheelY - dp(4f), wheelX + dp(1.8f), wheelY + dp(4f), pith)
        canvas.restore()
    }

    private fun cube(canvas: Canvas, x: Float, y: Float, turn: Float) {
        canvas.save()
        canvas.rotate(turn, x, y)
        iceRect.set(x - dp(3.1f), y - dp(3.1f), x + dp(3.1f), y + dp(3.1f))
        panel(canvas, iceRect, 1.6f, iceEdge, ice)
        canvas.restore()
    }

    override fun describe(): String =
        if (drunk) "an empty lemonade glass" else "a full glass of Pip's lemonade"

    companion object {
        val SIP_LINES = listOf(
            "*watches* well?",
            "the good jug. I knew it.",
            "*nods* hydration. you're welcome.",
            "sip carefully. there isn't more."
        )
        val CLEAR_LINES = listOf(
            "*takes the glass* this gets washed. eventually.",
            "glass back. bartender, not busboy.",
            "thank you for your business. the tip jar is my ego."
        )
    }
}
