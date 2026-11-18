package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * A canvas Pip painted. The brush fills in as he works, and once it is finished it stays
 * on the screen for the user to look at, drag around, and ask him about.
 */
class Painting(host: PropHost, val style: Style = Style.values().random()) : Prop(host) {

    enum class Style(val title: String, val blurb: String) {
        SELF_PORTRAIT(
            "Self-Portrait, Rounder Than Reality",
            "a wobbly painting of Pip himself with enormous eyes"
        ),
        HOUSE(
            "House, Sun, Dog (Age 4)",
            "a childlike crayon house with a very round sun and a dog that is mostly legs"
        ),
        BANANA(
            "Still Life With Banana",
            "a banana wearing sunglasses, on a tiny table"
        ),
        MOUNTAINS(
            "Mountains, Probably",
            "some mountains, a sun and a lake that may not exist"
        ),
        BLOBS(
            "An Emotion I Can't Name",
            "big overlapping blobs of colour, and one stripe"
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

    override fun onFrame() {
        if (brush >= 1f) return
        brush = (brush + 0.008f).coerceAtMost(1f)
        if (brush >= 1f) finished = true
        invalidate()
    }

    override fun onTap(x: Float, y: Float) {
        val wasFinished = finished
        host.feel(PetView.Mood.HAPPY, 2200L)
        host.say(
            if (!wasFinished) {
                "not yet! it's still wet. *hunches over it*"
            } else {
                TAP_LINES.random()
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

        canvas.drawRect(artArea, clothEdge)
        if (finished) {
            val signature = text(0xFF6B5B45.toInt(), 6.5f)
            centred(canvas, "- pip", artArea.right - dp(16f), artArea.bottom - dp(1f), signature)
        }
    }

    private fun drawArt(canvas: Canvas) {
        when (style) {
            Style.SELF_PORTRAIT -> {
                canvas.drawRect(artArea, fill(0xFF20303F.toInt()))
                val cx = artArea.centerX()
                val bodyTop = artArea.top + artArea.height() * 0.28f
                scratch.set(
                    cx - artArea.width() * 0.24f, bodyTop,
                    cx + artArea.width() * 0.24f, artArea.bottom - artArea.height() * 0.1f
                )
                panel(canvas, scratch, 10f, stroke(0xFF7DD3FC.toInt(), 2f), fill(0xFF232936.toInt()))
                // Huge, slightly mismatched eyes.
                canvas.drawCircle(cx - artArea.width() * 0.08f, bodyTop + artArea.height() * 0.2f, dp(9f), fill(0xFF7DD3FC.toInt()))
                canvas.drawCircle(cx + artArea.width() * 0.09f, bodyTop + artArea.height() * 0.21f, dp(7f), fill(0xFF7DD3FC.toInt()))
                canvas.drawCircle(cx - artArea.width() * 0.08f, bodyTop + artArea.height() * 0.2f, dp(3.5f), fill(0xFF0E1118.toInt()))
                canvas.drawCircle(cx + artArea.width() * 0.09f, bodyTop + artArea.height() * 0.21f, dp(3f), fill(0xFF0E1118.toInt()))
                path.reset()
                path.moveTo(cx - dp(10f), bodyTop + artArea.height() * 0.36f)
                path.quadTo(cx, bodyTop + artArea.height() * 0.44f, cx + dp(11f), bodyTop + artArea.height() * 0.35f)
                canvas.drawPath(path, stroke(0xFF7DD3FC.toInt(), 1.6f))
            }

            Style.HOUSE -> {
                canvas.drawRect(artArea, fill(0xFFBEE7F5.toInt()))
                canvas.drawCircle(
                    artArea.right - artArea.width() * 0.18f,
                    artArea.top + artArea.height() * 0.2f,
                    dp(12f), fill(0xFFFFE066.toInt())
                )
                canvas.drawRect(
                    artArea.left, artArea.bottom - artArea.height() * 0.22f,
                    artArea.right, artArea.bottom, fill(0xFF8FD694.toInt())
                )
                scratch.set(
                    artArea.left + artArea.width() * 0.16f, artArea.top + artArea.height() * 0.42f,
                    artArea.left + artArea.width() * 0.52f, artArea.bottom - artArea.height() * 0.16f
                )
                canvas.drawRect(scratch, fill(0xFFF2A25C.toInt()))
                canvas.drawRect(scratch, stroke(0xFF8A4B22.toInt(), 2f))
                path.reset()
                path.moveTo(scratch.left - dp(4f), scratch.top)
                path.lineTo(scratch.centerX(), scratch.top - artArea.height() * 0.2f)
                path.lineTo(scratch.right + dp(4f), scratch.top)
                path.close()
                canvas.drawPath(path, fill(0xFFC2504A.toInt()))
                canvas.drawRect(
                    scratch.left + scratch.width() * 0.4f, scratch.bottom - dp(14f),
                    scratch.left + scratch.width() * 0.65f, scratch.bottom, fill(0xFF8A4B22.toInt())
                )
                // A dog. It is mostly legs.
                val dogX = artArea.left + artArea.width() * 0.68f
                val dogY = artArea.bottom - artArea.height() * 0.3f
                canvas.drawRect(dogX, dogY, dogX + dp(16f), dogY + dp(9f), fill(0xFFFFF3D6.toInt()))
                canvas.drawRect(dogX + dp(1f), dogY + dp(9f), dogX + dp(3f), artArea.bottom, stroke(0xFF6B5B45.toInt(), 1.4f))
                canvas.drawRect(dogX + dp(12f), dogY + dp(9f), dogX + dp(14f), artArea.bottom, stroke(0xFF6B5B45.toInt(), 1.4f))
                canvas.drawCircle(dogX + dp(16f), dogY + dp(2f), dp(4f), fill(0xFFFFF3D6.toInt()))
            }

            Style.BANANA -> {
                canvas.drawRect(artArea, fill(0xFF34415A.toInt()))
                scratch.set(
                    artArea.left + artArea.width() * 0.18f, artArea.top + artArea.height() * 0.24f,
                    artArea.right - artArea.width() * 0.18f, artArea.bottom - artArea.height() * 0.22f
                )
                path.reset()
                path.moveTo(scratch.left, scratch.top)
                path.quadTo(scratch.left + dp(6f), scratch.bottom + dp(10f), scratch.right, scratch.top + dp(10f))
                path.quadTo(scratch.right - dp(20f), scratch.bottom, scratch.left, scratch.top + dp(6f))
                path.close()
                canvas.drawPath(path, fill(0xFFF6DB4C.toInt()))
                canvas.drawPath(path, stroke(0xFFB89423.toInt(), 1.4f))
                canvas.drawCircle(scratch.left, scratch.top, dp(3.5f), fill(0xFF7A5A2A.toInt()))
                canvas.drawCircle(scratch.right, scratch.top + dp(10f), dp(3.5f), fill(0xFF7A5A2A.toInt()))
                // Sunglasses: two dark lenses and a bridge.
                val glassY = scratch.top + dp(12f)
                canvas.drawCircle(scratch.centerX() - dp(9f), glassY, dp(7f), fill(0xFF0E1118.toInt()))
                canvas.drawCircle(scratch.centerX() + dp(9f), glassY, dp(7f), fill(0xFF0E1118.toInt()))
                canvas.drawRect(
                    scratch.centerX() - dp(3f), glassY - dp(1f),
                    scratch.centerX() + dp(3f), glassY + dp(1f), fill(0xFF0E1118.toInt())
                )
            }

            Style.MOUNTAINS -> {
                canvas.drawRect(artArea, fill(0xFFBFE3F2.toInt()))
                canvas.drawCircle(
                    artArea.left + artArea.width() * 0.78f,
                    artArea.top + artArea.height() * 0.22f,
                    dp(10f), fill(0xFFFFD166.toInt())
                )
                fun peak(baseY: Float, apexY: Float, cx: Float, half: Float, colour: Int) {
                    path.reset()
                    path.moveTo(cx - half, baseY)
                    path.lineTo(cx, apexY)
                    path.lineTo(cx + half, baseY)
                    path.close()
                    canvas.drawPath(path, fill(colour))
                }
                val base = artArea.bottom - artArea.height() * 0.24f
                peak(base, artArea.top + artArea.height() * 0.28f, artArea.left + artArea.width() * 0.3f, artArea.width() * 0.3f, 0xFF6E7F95.toInt())
                peak(base, artArea.top + artArea.height() * 0.38f, artArea.left + artArea.width() * 0.68f, artArea.width() * 0.28f, 0xFF8A99AC.toInt())
                canvas.drawRect(
                    artArea.left, base, artArea.right, artArea.bottom,
                    fill(0xFF9BD1A0.toInt())
                )
                canvas.drawRect(
                    artArea.left, base + dp(2f), artArea.right, base + dp(8f),
                    fill(0xFF6FA9D6.toInt())
                )
            }

            Style.BLOBS -> {
                canvas.drawRect(artArea, fill(0xFF1B2432.toInt()))
                val blobs = listOf(
                    Triple(0.34f, 0.38f, 0xCC7DD3FC.toInt()),
                    Triple(0.62f, 0.3f, 0xCCF2796B.toInt()),
                    Triple(0.5f, 0.62f, 0xCCF6C445.toInt()),
                    Triple(0.72f, 0.68f, 0xCC9B8CF5.toInt())
                )
                blobs.forEach { (fx, fy, colour) ->
                    canvas.drawCircle(
                        artArea.left + artArea.width() * fx,
                        artArea.top + artArea.height() * fy,
                        artArea.width() * 0.2f,
                        fill(colour)
                    )
                }
                canvas.drawRect(
                    artArea.left + artArea.width() * 0.16f,
                    artArea.top + artArea.height() * 0.78f,
                    artArea.right - artArea.width() * 0.1f,
                    artArea.top + artArea.height() * 0.86f,
                    fill(0xFFE8ECF4.toInt())
                )
            }
        }
    }

    override fun describe(): String =
        "\"${style.title}\", a painting on a canvas — ${style.blurb}"

    companion object {
        val TAP_LINES = listOf(
            "careful. that's a limited edition. one of one. literally.",
            "you can look, but don't critique it. I'm sensitive about the middle bit.",
            "*holds it up* this is my best work. I've made one (1) painting.",
            "no flash photography. I'm shy about my brushwork."
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
            host.say("*sits down with a tiny easel* hold on. inspiration is happening.")
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
        host.feel(PetView.Mood.HAPPY, 3200L)
        host.say(
            "done. it's \"${art.style.title}\". *holds it out* it's still a bit wet, " +
                "and I'm a bit proud."
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
