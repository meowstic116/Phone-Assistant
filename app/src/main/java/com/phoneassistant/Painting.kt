package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.random.Random

/**
 * A canvas Pip painted. The brush fills in as he works, and once it is finished it stays
 * on the screen for the user to look at, drag around, and ask him about.
 */
class Painting(host: PropHost, val style: Style = Style.values().random()) : Prop(host) {

    enum class Style(val title: String, val blurb: String) {
        DOG_DEBT(
            "The Dog Still Owes Me Twenty Bucks",
            "a portrait of a dog in a tiny hat who refuses to discuss the money"
        ),
        GIANT_PIP(
            "Me, Destroying the City (Artist's Impression)",
            "a painting of Pip as a hundred-foot monster stomping a skyline, eyes glowing"
        ),
        SANDWICH(
            "The Sandwich I Have Earned",
            "an enormous sandwich wearing a crown, with one tiny, patient person beside it"
        ),
        TOASTER(
            "Toaster, Portrait of a King",
            "a toaster in sunglasses and a crown, plugged into a deeply relaxed lemon"
        ),
        FISH_MOON(
            "Fish, But an Astronaut",
            "a fish in a space helmet planting a tiny flag on the moon"
        )
    }

    override val boxW = 172f
    override val boxH = 136f

    var finished = false
        private set

    /** 0 to 1, how much of the painting has been painted. */
    private var brush = 0f

    private val frame = fill(0xFF8A653F.toInt())
    private val frameEdge = stroke(0xFF5C4126.toInt(), 1.2f)
    private val cloth = fill(0xFFF7F1E3.toInt())
    private val clothEdge = stroke(0xFFD9CFBB.toInt(), 1f)
    private val artArea = RectF()
    private val scratch = RectF()
    private val path = Path()

    private val sheen = Sheen(dp(52f), 0x22FFFFFF.toInt(), SHEEN_PERIOD_MS)

    override fun onFrame() {
        if (brush < 1f) {
            brush = (brush + 0.008f).coerceAtMost(1f)
            if (brush >= 1f) finished = true
            invalidate()
            return
        }
        // Finished, so the sheen is the only thing still moving. Redraw while it is on
        // screen and leave the rest of its cycle as a still frame: a canvas this detailed
        // is the most expensive thing on the screen to repaint for no reason.
        if (sheen.visible(SystemClock.uptimeMillis(), bornAt)) invalidate()
    }

    override fun onTap(x: Float, y: Float) {
        val wasFinished = finished
        host.feel(PetView.Mood.HAPPY, 2200L)
        host.say(
            if (!wasFinished) {
                Lines.pick(WET_LINES)
            } else {
                Lines.pick(TAP_LINES)
            }
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        panel(canvas, RectF(dp(1f), dp(1f), w - dp(1f), h - dp(1f)), 4f, frameEdge, frame)
        artArea.set(dp(8f), dp(8f), w - dp(8f), h - dp(16f))
        canvas.drawRect(artArea, cloth)

        canvas.save()
        // Only reveal as much of the picture as has been painted so far.
        canvas.clipRect(
            artArea.left, artArea.top,
            artArea.left + artArea.width() * brush, artArea.bottom
        )
        drawArt(canvas)
        canvas.restore()

        drawSheen(canvas)

        canvas.drawRect(artArea, clothEdge)
        if (finished) {
            val signature = text(0xFF6B5B45.toInt(), 6.5f)
            centred(canvas, "- pip", artArea.right - dp(16f), artArea.bottom - dp(1f), signature)
        }
    }

    /**
     * A slow highlight crossing the wet paint every few seconds, clipped to the part that
     * has actually been painted. Paint takes a while to dry, so it keeps going afterwards.
     */
    private fun drawSheen(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (!sheen.visible(now, bornAt)) return
        val paintedRight = artArea.left + artArea.width() * brush
        canvas.save()
        canvas.clipRect(artArea.left, artArea.top, paintedRight, artArea.bottom)
        sheen.draw(
            canvas,
            artArea.left, artArea.top, artArea.right, artArea.bottom,
            now, bornAt
        )
        canvas.restore()
    }

    private fun drawArt(canvas: Canvas) {
        when (style) {
            Style.DOG_DEBT -> {
                canvas.drawRect(artArea, fill(0xFF2C3A4A.toInt()))
                val cx = artArea.centerX() - dp(4f)
                val headTop = artArea.top + artArea.height() * 0.26f
                val headHalf = artArea.width() * 0.22f
                val headBottom = artArea.bottom - artArea.height() * 0.1f

                // Ears, flopping unbecomingly.
                scratch.set(
                    cx - headHalf - dp(10f), headTop + dp(4f),
                    cx - headHalf + dp(3f), headBottom - dp(6f)
                )
                panel(canvas, scratch, 6f, stroke(0xFF8A5F3C.toInt(), 1.2f), fill(0xFFB98A5E.toInt()))
                scratch.set(
                    cx + headHalf - dp(3f), headTop + dp(4f),
                    cx + headHalf + dp(10f), headBottom - dp(6f)
                )
                panel(canvas, scratch, 6f, stroke(0xFF8A5F3C.toInt(), 1.2f), fill(0xFFB98A5E.toInt()))

                // Head.
                scratch.set(cx - headHalf, headTop, cx + headHalf, headBottom)
                panel(canvas, scratch, 12f, stroke(0xFFC9A27C.toInt(), 1.4f), fill(0xFFE8C9A0.toInt()))

                // Snout, nose, and eyes that know exactly what they owe.
                scratch.set(cx - dp(16f), headBottom - dp(27f), cx + dp(16f), headBottom - dp(6f))
                panel(canvas, scratch, 8f, stroke(0x338A5F3C.toInt(), 1f), fill(0xFFF3DDC0.toInt()))
                canvas.drawCircle(cx, headBottom - dp(22f), dp(4f), fill(0xFF2C2029.toInt()))
                canvas.drawCircle(cx - dp(9f), headTop + dp(16f), dp(3.4f), fill(0xFF2C2029.toInt()))
                canvas.drawCircle(cx + dp(9f), headTop + dp(16f), dp(3.4f), fill(0xFF2C2029.toInt()))
                canvas.drawLine(
                    cx - dp(14f), headTop + dp(10f),
                    cx - dp(5f), headTop + dp(12f), stroke(0xFF2C2029.toInt(), 1.4f)
                )
                canvas.drawLine(
                    cx + dp(5f), headTop + dp(12f),
                    cx + dp(14f), headTop + dp(10f), stroke(0xFF2C2029.toInt(), 1.4f)
                )

                // The tiny hat, which is doing a lot of work.
                panel(
                    canvas,
                    RectF(cx - dp(19f), headTop - dp(3f), cx + dp(19f), headTop - dp(9f)),
                    2f, stroke(0xFF1B2432.toInt(), 1f), fill(0xFF38465C.toInt())
                )
                panel(
                    canvas,
                    RectF(cx - dp(12f), headTop - dp(20f), cx + dp(12f), headTop - dp(8f)),
                    3f, stroke(0xFF1B2432.toInt(), 1f), fill(0xFF38465C.toInt())
                )

                // The IOU, pinned where the dog cannot reach it.
                val note = RectF(
                    artArea.right - dp(50f), artArea.bottom - dp(32f),
                    artArea.right - dp(5f), artArea.bottom - dp(9f)
                )
                panel(canvas, note, 2f, stroke(0xFFC9C2AF.toInt(), 1f), fill(0xFFF7F1E3.toInt()))
                centred(
                    canvas, "IOU 20", note.centerX(), note.centerY() - dp(3f),
                    text(0xFF6B5B45.toInt(), 7.5f, true)
                )
                centred(
                    canvas, "signed: the dog", note.centerX(), note.centerY() + dp(6f),
                    text(0xFF9A8E79.toInt(), 5f)
                )
            }

            Style.GIANT_PIP -> {
                canvas.drawRect(artArea, fill(0xFF2B1F33.toInt()))
                // A very dramatic sunset, all two colours of it.
                canvas.drawCircle(
                    artArea.left + artArea.width() * 0.74f,
                    artArea.top + artArea.height() * 0.24f,
                    dp(16f), fill(0xFFF2796B.toInt())
                )

                // The skyline, mostly doomed.
                val base = artArea.bottom - dp(3f)
                val heights = listOf(0.3f, 0.52f, 0.36f, 0.66f, 0.44f, 0.32f, 0.56f)
                val bw = artArea.width() / heights.size
                val dark = fill(0xFF171021.toInt())
                heights.forEachIndexed { i, fraction ->
                    val left = artArea.left + bw * i
                    val top = base - artArea.height() * fraction * 0.62f
                    canvas.drawRect(left + dp(1f), top, left + bw - dp(1f), base, dark)
                    // One lit window, because somebody is still at the office.
                    canvas.drawRect(
                        left + bw * 0.42f, top + dp(5f),
                        left + bw * 0.42f + dp(3f), top + dp(8f), fill(0xFFF6C445.toInt())
                    )
                }

                // Pip, enormous, mid-stomp.
                val cx = artArea.left + artArea.width() * 0.4f
                val bodyTop = artArea.top + artArea.height() * 0.1f
                val bodyBottom = base - dp(12f)
                canvas.drawRect(cx - dp(11f), bodyBottom, cx - dp(5f), base, dark)
                canvas.drawRect(cx + dp(4f), bodyBottom, cx + dp(10f), base, dark)
                scratch.set(
                    cx - artArea.width() * 0.13f, bodyTop,
                    cx + artArea.width() * 0.13f, bodyBottom
                )
                panel(canvas, scratch, 9f, stroke(0xFF7DD3FC.toInt(), 1.8f), fill(0xFF232936.toInt()))

                // Antenna, and two furious glowing eyes.
                canvas.drawLine(cx, bodyTop, cx, bodyTop - dp(7f), stroke(0xFF7DD3FC.toInt(), 1.4f))
                canvas.drawCircle(cx, bodyTop - dp(8f), dp(2.2f), fill(0xFFF2796B.toInt()))
                canvas.drawCircle(cx - dp(9f), bodyTop + dp(11f), dp(4.2f), fill(0xFFF2796B.toInt()))
                canvas.drawCircle(cx + dp(9f), bodyTop + dp(11f), dp(4.2f), fill(0xFFF2796B.toInt()))
                path.reset()
                path.moveTo(cx - dp(8f), bodyTop + dp(24f))
                path.quadTo(cx, bodyTop + dp(30f), cx + dp(9f), bodyTop + dp(23f))
                canvas.drawPath(path, stroke(0xFF7DD3FC.toInt(), 1.6f))

                // A tiny person, fleeing. Sensible.
                val px = artArea.left + artArea.width() * 0.82f
                canvas.drawCircle(px, base - dp(15f), dp(3f), fill(0xFFE8C9A0.toInt()))
                canvas.drawLine(px, base - dp(12f), px, base - dp(3f), stroke(0xFFE8C9A0.toInt(), 1.6f))
                canvas.drawLine(px, base - dp(8f), px + dp(5f), base - dp(11f), stroke(0xFFE8C9A0.toInt(), 1.6f))
            }

            Style.SANDWICH -> {
                canvas.drawRect(artArea, fill(0xFF3A2E2A.toInt()))
                // A cloth, to make it feel expensive.
                val clothTop = artArea.bottom - artArea.height() * 0.16f
                canvas.drawRect(artArea.left, clothTop, artArea.right, artArea.bottom, fill(0xFF8B4B3A.toInt()))
                canvas.drawLine(
                    artArea.left, clothTop, artArea.right, clothTop,
                    stroke(0xFFB47A62.toInt(), 1.2f)
                )

                val cx = artArea.centerX() - dp(12f)
                val base = clothTop + dp(2f)
                val w = artArea.width() * 0.46f
                val crust = stroke(0xFFB9873F.toInt(), 1.2f)

                // Bottom bun.
                scratch.set(cx - w / 2f, base - dp(9f), cx + w / 2f, base)
                panel(canvas, scratch, 5f, crust, fill(0xFFD9A05B.toInt()))

                // Lettuce, doing a zigzag because it is excited.
                path.reset()
                path.moveTo(cx - w / 2f, base - dp(9f))
                var i = 0
                while (i < 6) {
                    path.lineTo(cx - w / 2f + (w / 6f) * (i + 0.5f), base - dp(16f))
                    path.lineTo(cx - w / 2f + (w / 6f) * (i + 1f), base - dp(9f))
                    i++
                }
                path.close()
                canvas.drawPath(path, fill(0xFF74B97A.toInt()))

                // Cheese with ambitions.
                path.reset()
                path.moveTo(cx - w / 2f + dp(3f), base - dp(16f))
                path.lineTo(cx + w / 2f - dp(4f), base - dp(18f))
                path.lineTo(cx + w / 2f - dp(11f), base - dp(6f))
                path.close()
                canvas.drawPath(path, fill(0xFFF6C445.toInt()))

                // Tomato slices.
                canvas.drawCircle(cx - w * 0.17f, base - dp(19f), dp(6f), fill(0xFFE2564A.toInt()))
                canvas.drawCircle(cx + w * 0.18f, base - dp(18f), dp(5f), fill(0xFFE2564A.toInt()))

                // Top bun, sesame seeds and all.
                scratch.set(cx - w / 2f, base - dp(35f), cx + w / 2f, base - dp(18f))
                panel(canvas, scratch, 9f, crust, fill(0xFFE0AC66.toInt()))
                for (k in 0 until 4) {
                    canvas.drawCircle(
                        cx - w * 0.26f + dp(11f) * k,
                        base - dp(29f) - (if (k % 2 == 0) 0f else dp(2f)),
                        dp(1.3f), fill(0xFFF7E3C0.toInt())
                    )
                }

                // Crown, because a sandwich this good has been through a lot.
                val crownBottom = base - dp(35f)
                val halfCrown = dp(13f)
                path.reset()
                path.moveTo(cx - halfCrown, crownBottom)
                path.lineTo(cx - halfCrown, crownBottom - dp(9f))
                path.lineTo(cx - halfCrown / 2f, crownBottom - dp(4f))
                path.lineTo(cx, crownBottom - dp(13f))
                path.lineTo(cx + halfCrown / 2f, crownBottom - dp(4f))
                path.lineTo(cx + halfCrown, crownBottom - dp(9f))
                path.lineTo(cx + halfCrown, crownBottom)
                path.close()
                canvas.drawPath(path, fill(0xFFFFD166.toInt()))

                // The tiny, patient person.
                val px = artArea.right - dp(24f)
                val skin = stroke(0xFFE8C9A0.toInt(), 1.6f)
                canvas.drawCircle(px, base - dp(17f), dp(3.4f), fill(0xFFE8C9A0.toInt()))
                canvas.drawLine(px, base - dp(14f), px, base - dp(3f), skin)
                canvas.drawLine(px - dp(5f), base - dp(10f), px + dp(5f), base - dp(10f), skin)
                canvas.drawLine(px, base - dp(3f), px - dp(4f), base, skin)
                canvas.drawLine(px, base - dp(3f), px + dp(4f), base, skin)
            }

            Style.TOASTER -> {
                canvas.drawRect(artArea, fill(0xFF20303F.toInt()))
                val base = artArea.bottom - dp(14f)
                canvas.drawRect(artArea.left, base, artArea.right, artArea.bottom, fill(0xFF6B4A2C.toInt()))

                val cx = artArea.left + artArea.width() * 0.4f
                val bw = dp(48f)
                val bh = dp(34f)

                // Toast, jumping ship.
                scratch.set(cx - dp(13f), base - bh - dp(13f), cx + dp(11f), base - bh + dp(2f))
                panel(canvas, scratch, 3f, stroke(0xFFB9873F.toInt(), 1f), fill(0xFFE0AC66.toInt()))
                scratch.set(cx + dp(1f), base - bh - dp(18f), cx + dp(19f), base - bh)
                panel(canvas, scratch, 3f, stroke(0xFFB9873F.toInt(), 1f), fill(0xFFE8B978.toInt()))

                // Body and slot.
                scratch.set(cx - bw / 2f, base - bh, cx + bw / 2f, base)
                panel(canvas, scratch, 7f, stroke(0xFF3A4356.toInt(), 1.6f), fill(0xFF9AA6B8.toInt()))
                canvas.drawRect(
                    cx - bw * 0.3f, base - bh + dp(1f),
                    cx + bw * 0.3f, base - bh + dp(5f), fill(0xFF3A4356.toInt())
                )

                // Sunglasses, because it is above all of this.
                canvas.drawCircle(cx - dp(8f), base - dp(21f), dp(5.2f), fill(0xFF0E1118.toInt()))
                canvas.drawCircle(cx + dp(8f), base - dp(21f), dp(5.2f), fill(0xFF0E1118.toInt()))
                canvas.drawRect(
                    cx - dp(3f), base - dp(22f),
                    cx + dp(3f), base - dp(20f), fill(0xFF0E1118.toInt())
                )
                canvas.drawCircle(cx + bw * 0.32f, base - dp(9f), dp(2.6f), fill(0xFFF2796B.toInt()))

                // Crown.
                val crownBottom = base - bh - dp(1f)
                val halfCrown = dp(12f)
                path.reset()
                path.moveTo(cx - halfCrown, crownBottom)
                path.lineTo(cx - halfCrown, crownBottom - dp(8f))
                path.lineTo(cx - halfCrown / 2f, crownBottom - dp(4f))
                path.lineTo(cx, crownBottom - dp(12f))
                path.lineTo(cx + halfCrown / 2f, crownBottom - dp(4f))
                path.lineTo(cx + halfCrown, crownBottom - dp(8f))
                path.lineTo(cx + halfCrown, crownBottom)
                path.close()
                canvas.drawPath(path, fill(0xFFFFD166.toInt()))

                // The cord, going somewhere very silly.
                val lemonX = artArea.right - dp(20f)
                path.reset()
                path.moveTo(cx + bw / 2f, base - dp(6f))
                path.quadTo(cx + bw / 2f + dp(16f), base + dp(8f), lemonX - dp(10f), base - dp(12f))
                canvas.drawPath(path, stroke(0xFF2C2029.toInt(), 1.4f))

                // A lemon, completely unbothered by being a power source.
                canvas.drawCircle(lemonX, base - dp(13f), dp(9f), fill(Ink.LEMON))
                canvas.drawCircle(lemonX, base - dp(13f), dp(9f), stroke(0xFFC9A227.toInt(), 1f))
                canvas.drawCircle(lemonX + dp(2f), base - dp(19f), dp(1.6f), fill(0xFF0E1118.toInt()))
                canvas.drawCircle(lemonX + dp(7f), base - dp(17f), dp(1.6f), fill(0xFF0E1118.toInt()))
            }

            Style.FISH_MOON -> {
                canvas.drawRect(artArea, fill(0xFF0E1626.toInt()))

                // Stars, which are just dots with confidence.
                val stars = listOf(
                    Triple(0.12f, 0.18f, 1.2f), Triple(0.3f, 0.1f, 0.9f),
                    Triple(0.62f, 0.14f, 1.4f), Triple(0.88f, 0.3f, 1f),
                    Triple(0.2f, 0.4f, 0.8f), Triple(0.52f, 0.28f, 1.1f)
                )
                stars.forEach { (fx, fy, r) ->
                    canvas.drawCircle(
                        artArea.left + artArea.width() * fx,
                        artArea.top + artArea.height() * fy,
                        dp(r), fill(0xFFE8ECF4.toInt())
                    )
                }

                // The moon, curving across the bottom of the canvas.
                scratch.set(
                    artArea.left - artArea.width() * 0.2f,
                    artArea.bottom - artArea.height() * 0.34f,
                    artArea.right + artArea.width() * 0.2f,
                    artArea.bottom + artArea.height() * 0.5f
                )
                canvas.drawOval(scratch, fill(0xFFD8D2C4.toInt()))
                canvas.drawCircle(
                    artArea.left + artArea.width() * 0.28f,
                    artArea.bottom - dp(9f), dp(5f), fill(0xFFBEB8AA.toInt())
                )
                canvas.drawCircle(
                    artArea.left + artArea.width() * 0.74f,
                    artArea.bottom - dp(14f), dp(7f), fill(0xFFBEB8AA.toInt())
                )

                // One fish, in one helmet.
                val cx = artArea.left + artArea.width() * 0.42f
                val cy = artArea.bottom - dp(34f)
                scratch.set(cx - dp(15f), cy - dp(8f), cx + dp(11f), cy + dp(8f))
                canvas.drawOval(scratch, fill(0xFFF6C445.toInt()))
                path.reset()
                path.moveTo(cx + dp(10f), cy)
                path.lineTo(cx + dp(19f), cy - dp(7f))
                path.lineTo(cx + dp(19f), cy + dp(7f))
                path.close()
                canvas.drawPath(path, fill(0xFFF6C445.toInt()))
                canvas.drawCircle(cx - dp(7f), cy - dp(1f), dp(1.8f), fill(0xFF0E1118.toInt()))
                canvas.drawCircle(cx - dp(1f), cy, dp(17f), stroke(0x99BFD9E6.toInt(), 1.4f))
                canvas.drawCircle(cx - dp(1f), cy, dp(17f), fill(0x1ABFD9E6))

                // A flag, planted with great ceremony.
                val poleX = artArea.right - dp(30f)
                canvas.drawLine(
                    poleX, artArea.bottom - dp(6f), poleX, artArea.bottom - dp(44f),
                    stroke(0xFFE8ECF4.toInt(), 1.6f)
                )
                path.reset()
                path.moveTo(poleX, artArea.bottom - dp(44f))
                path.lineTo(poleX + dp(22f), artArea.bottom - dp(38f))
                path.lineTo(poleX, artArea.bottom - dp(32f))
                path.close()
                canvas.drawPath(path, fill(0xFFF2796B.toInt()))
            }
        }
    }

    override fun describe(): String =
        "\"${style.title}\", a painting on a canvas — ${style.blurb}"

    companion object {
        private const val SHEEN_PERIOD_MS = 7_200L

        val WET_LINES = listOf(
            "give it a minute. still wet.",
            "still drying. patience.",
            "not yet. the paint's still deciding."
        )

        val TAP_LINES = listOf(
            "careful. one of one, literally.",
            "the dog in that one owes me twenty.",
            "no flash photography. it's shy.",
            "*holds it up* best work. also my only work.",
            "look all you like. I'm not explaining the middle."
        )
    }
}

/**
 * The whole bit: Pip finds a spot, sits down with a canvas, paints for a few seconds,
 * then gets up and wanders off again, leaving the painting behind.
 */
class PaintingScene(private val host: PropHost, private val style: Painting.Style) {

    private val ui = Handler(Looper.getMainLooper())

    fun begin() {
        val w = host.stageWidth().toFloat()
        val h = host.stageHeight().toFloat()
        val spotX = w * (0.28f + Random.nextFloat() * 0.34f)
        val spotY = h * (0.3f + Random.nextFloat() * 0.18f)

        host.petWalkTo(spotX, spotY) {
            host.petSit(true)
            host.say("*sets up the easel* give me a minute. inspiration is scheduled.")
            val art = Painting(host, style)
            val gap = host.px(6f)
            val halfPet = host.petWidth() / 2f
            val roomRight = w - (spotX + halfPet)
            val x = if (roomRight > art.windowW + gap) {
                spotX + halfPet + gap
            } else {
                spotX - halfPet - art.windowW - gap
            }
            val y = spotY + host.petHeight() / 2f - art.windowH * 0.82f

            if (!art.bring(x, y)) {
                host.petSit(false)
                return@petWalkTo
            }
            ui.postDelayed({ finish(art) }, 6_800L)
        }
    }

    private fun finish(art: Painting) {
        host.petSit(false)
        // Tidied away mid-painting: there is nothing to present, so he just gets up. The
        // window outlives the prop by the length of its fade, so leaving counts too.
        if (!art.isAttachedToWindow || art.leaving) return
        host.feel(PetView.Mood.HAPPY, 3200L)
        host.say(
            "done. it's \"${art.style.title}\". *holds it out* still wet, and I'm " +
                "quietly pleased."
        )
        val w = host.stageWidth().toFloat()
        val h = host.stageHeight().toFloat()
        host.petWalkTo(
            w * (0.2f + Random.nextFloat() * 0.6f),
            h * (0.4f + Random.nextFloat() * 0.3f),
            null
        )
    }
}
