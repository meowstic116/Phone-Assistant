package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import kotlin.random.Random

/**
 * A little lemonade stand. Tap it and Pip pours you a glass; the glass is a separate
 * prop you can drag anywhere, drink from, and then throw away.
 */
class LemonadeStand(host: PropHost) : Prop(host) {

    override val boxW = 112f
    override val boxH = 148f

    private val wood = fill(Ink.WOOD)
    private val woodEdge = stroke(0xFF7E5B3B.toInt(), 1f)
    private val cream = fill(0xFFF4EFE2.toInt())
    private val red = fill(0xFFE2564A.toInt())
    private val juice = fill(Ink.LEMON)
    private val glassEdge = stroke(0xFFBFD9E6.toInt(), 1.2f)
    private val glassFill = fill(0x33DCE9F2)
    private val signInk = text(0xFF3A2A1C.toInt(), 9f, true)
    private val tiny = text(0xFF6B4A2C.toInt(), 6.5f)
    private val slicePaint = fill(0xFFFFF1A8.toInt())

    private var lineIndex = 0

    /** Glasses promised and not yet poured, capped at what the stand can hold. */
    private var owed = 0

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Two uprights carrying the awning, and the legs under the counter. Every number
        // here is in the stand's own dp, and the block below is what Pip has to fit around:
        // awning 2–22, open front 22–96, counter 96–112, legs 112–146, sign 115–137.
        canvas.drawRect(dp(7f), dp(22f), dp(11f), dp(112f), wood)
        canvas.drawRect(w - dp(11f), dp(22f), w - dp(7f), dp(112f), wood)
        canvas.drawRect(dp(12f), dp(112f), dp(17f), h - dp(2f), wood)
        canvas.drawRect(w - dp(17f), dp(112f), w - dp(12f), h - dp(2f), wood)

        // Pitcher and a spare cup, at the ends of the counter: the middle is where Pip
        // stands, so nothing on this stand is painted over his face.
        val jug = RectF(dp(6f), dp(72f), dp(26f), dp(96f))
        panel(canvas, jug, 4f, glassEdge, juice)
        canvas.drawArc(RectF(dp(24f), dp(76f), dp(34f), dp(92f)), -70f, 140f, false, glassEdge)
        canvas.drawCircle(dp(10f), dp(74f), dp(4f), slicePaint)
        val cup = RectF(dp(88f), dp(82f), dp(106f), dp(98f))
        canvas.drawRect(cup, glassFill)
        canvas.drawRect(cup, glassEdge)

        // The counter, drawn over his hips and feet, just below his chin when he stands
        // behind it. Sixteen dp thick so his feet stay hidden even when he bobs downwards.
        panel(canvas, RectF(dp(2f), dp(96f), w - dp(2f), dp(112f)), 2f, woodEdge, wood)

        // The sign, which is the entire marketing strategy, hanging on the counter front,
        // below where he stands rather than across his face.
        val board = RectF(dp(14f), dp(115f), w - dp(14f), dp(137f))
        panel(canvas, board, 3f, woodEdge, cream)
        centred(canvas, "LEMONADE", board.centerX(), board.centerY() - dp(4f), signInk)
        centred(canvas, "50c or a compliment", board.centerX(), board.centerY() + dp(7f), tiny)

        // Striped awning, a canopy over his antenna with a couple of dp to spare.
        val awnTop = dp(2f)
        val awnBottom = dp(22f)
        canvas.save()
        canvas.clipRect(dp(2f), awnTop, w - dp(2f), awnBottom)
        canvas.drawRect(dp(2f), awnTop, w - dp(2f), awnBottom, cream)
        val stripe = (w - dp(4f)) / 6f
        var i = 0
        while (i < 6) {
            canvas.drawRect(
                dp(2f) + stripe * i, awnTop,
                dp(2f) + stripe * (i + 1), awnBottom, red
            )
            i += 2
        }
        canvas.restore()
    }

    /**
     * Where Pip's middle has to sit to look like he is serving from behind the counter,
     * worked out from PetView's own drawing rather than guessed.
     *
     * His window is 86dp tall and his middle is 43dp down it, so this puts the top of his
     * window 24dp below the top of the stand. Measured from there, and from the layout
     * above: antenna tip 27.8, head 37, face 45–89, mouth 73.2, hips 95 onwards, feet 108.
     * His idle bob moves him 1.1dp either way and his idle tilt can swing his chin 1.9dp
     * lower, so the worst cases are an antenna tip at 28.9 against an awning that ends at
     * 22, and a chin at 92 against a counter that starts at 96. Everything from his hips
     * down is behind the counter (his feet clear it by 2.9dp), the sign hangs entirely
     * below him, and no part of his face, head or antenna sits behind wood.
     */
    fun behindCounterY(): Float = posY + windowH - dp(81f)

    /** How many glasses are on the screen right now. */
    private fun glassesOut(): Int = host.propsOnScreen().count { it is LemonadeGlass }

    /** One jug, two glasses: whether there is space for another pour. */
    fun hasRoom(): Boolean = glassesOut() < 2

    /** Pours a glass, beside the stand. Returns false when there is no room for another. */
    fun serve(): Boolean {
        if (!hasRoom()) {
            host.say("that is a two-glass stand. I have one jug and a lot of pride.")
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
        host.say(next(SERVE_LINES))
        return true
    }

    override fun onTap(x: Float, y: Float) {
        // This window is drawn over his, so taps aimed at him arrive here. Anything in the
        // box his body occupies above the counter is a poke, not an order.
        if (y < dp(96f) && x > dp(29f) && x < dp(83f)) {
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
    fun takePost(say: Boolean, onArrive: (() -> Unit)? = null) {
        host.petHoldAt(centreX, behindCounterY()) {
            if (say) host.say(POST_LINES.random())
            onArrive?.invoke()
        }
    }

    /** Dragged somewhere else: he walks back round behind it instead of being left behind. */
    override fun onDragFinished() {
        takePost(say = true)
    }

    /**
     * Walks him round behind the counter and pours when he actually gets there, so the glass
     * appears with him behind the stand rather than the instant he answers. No timer: a long
     * walk across the screen must not pour early, and the only things that cancel this walk
     * are deliberate — a chat or a tap on him.
     *
     * Each accepted request adds one glass to the debt, capped by the space the stand has
     * left, and the debt is decremented before the glass is poured. That is what makes a
     * pour exactly once no matter how many times the post's arrival is reached — a resumed
     * walk and the already-here check both end up in [pourPending], and the second one finds
     * nothing owed.
     */
    fun pourOnArrival() {
        val room = (2 - glassesOut()).coerceAtLeast(0)
        owed = (owed + 1).coerceAtMost(room)
        if (owed <= 0) return
        takePost(say = false) { pourPending() }
    }

    private fun pourPending() {
        if (owed <= 0) return
        owed--
        // serve() keeps its own room check, so a debt that was capped cannot overfill the
        // stand if a glass appeared in the meantime.
        if (!serve()) {
            owed = 0
            return
        }
        if (owed > 0 && hasRoom()) pourPending()
    }

    /** Off the screen: let him wander again, and forget anything still owed. */
    override fun onDetachedFromWindow() {
        owed = 0
        host.petRelease()
        super.onDetachedFromWindow()
    }

    override fun describe(): String =
        "your lemonade stand, with you standing behind the counter " +
            "(glasses out right now: ${glassesOut()})"

    private fun next(lines: List<String>): String {
        lineIndex = (lineIndex + Random.nextInt(1, lines.size)) % lines.size
        return lines[lineIndex]
    }

    companion object {
        val POST_LINES = listOf(
            "*dusts the counter* right. my spot. where were we.",
            "back behind the counter. the queue starts here, and it is you.",
            "*straightens the sign* still fifty cents. still mostly lemon."
        )

        val SERVE_LINES = listOf(
            "one lemonade. fifty cents. I will also accept a compliment.",
            "*pours carefully* this is the good jug. don't tell the other jug.",
            "here. it's mostly lemon. the rest is attitude.",
            "fresh squeezed. well. squeezed. mostly.",
            "*slides you a glass* I have a whole business now. try not to be impressed."
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

    private val rim = stroke(0xFFBFD9E6.toInt(), 1.4f)
    private val body = fill(0x2EDCE9F2)
    private val juice = fill(Ink.LEMON)
    private val straw = stroke(0xFFE2564A.toInt(), 2f)
    private val ice = fill(0x66FFFFFF)
    private val glassPath = Path()

    override fun onTap(x: Float, y: Float) {
        if (!drunk) {
            drunk = true
            wobble = 1f
            invalidate()
            host.feel(PetView.Mood.HAPPY, 1800L)
            host.say(SIP_LINES.random())
        } else {
            host.dropProp(this)
            host.say(CLEAR_LINES.random())
        }
    }

    override fun onFrame() {
        if (wobble <= 0f) return
        wobble -= 0.06f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = dp(8f)
        val bottom = h - dp(4f)
        val topHalf = w * 0.34f
        val bottomHalf = w * 0.26f

        glassPath.reset()
        glassPath.moveTo(cx - topHalf, top)
        glassPath.lineTo(cx + topHalf, top)
        glassPath.lineTo(cx + bottomHalf, bottom)
        glassPath.lineTo(cx - bottomHalf, bottom)
        glassPath.close()

        canvas.drawPath(glassPath, body)

        if (!drunk) {
            canvas.save()
            canvas.clipPath(glassPath)
            val levelTop = top + (bottom - top) * 0.18f
            canvas.drawRect(cx - topHalf, levelTop, cx + topHalf, bottom, juice)
            // Ice, floating with the wobble.
            canvas.save()
            val iceY = levelTop + dp(4f) + wobble * dp(2f)
            canvas.rotate(wobble * 8f, cx, iceY)
            canvas.drawRoundRect(
                RectF(cx - dp(7f), iceY, cx + dp(1f), iceY + dp(7f)),
                dp(1.5f), dp(1.5f), ice
            )
            canvas.restore()
            canvas.restore()
        }

        canvas.drawPath(glassPath, rim)

        // Straw, bent, and a lemon wheel on the rim.
        val strawTop = if (drunk) dp(2f) else dp(6f)
        canvas.drawLine(cx + dp(6f), bottom - dp(6f), cx + dp(2f), strawTop, straw)
        canvas.drawLine(cx + dp(2f), strawTop, cx - dp(6f), strawTop, straw)
        canvas.drawCircle(cx - topHalf + dp(2f), top, dp(5f), fill(0xFFFFF1A8.toInt()))
        canvas.drawCircle(cx - topHalf + dp(2f), top, dp(5f), stroke(0xFFE0B93A.toInt(), 1f))

        if (drunk) {
            val fizz = text(Ink.DIM, 6.5f)
            centred(canvas, "empty", cx, bottom - dp(6f), fizz)
        }
    }

    override fun describe(): String =
        if (drunk) "an empty lemonade glass" else "a full glass of Pip's lemonade"

    companion object {
        val SIP_LINES = listOf(
            "*watches you drink* ... well?",
            "that's the good jug. I knew it.",
            "*nods professionally* hydration. my gift to you.",
            "sip responsibly. there is no more."
        )
        val CLEAR_LINES = listOf(
            "*takes the glass back* I'll wash that. eventually.",
            "glass back. I'm not your busboy. I'm your bartender.",
            "thank you for your business. tip jar is my ego."
        )
    }
}
