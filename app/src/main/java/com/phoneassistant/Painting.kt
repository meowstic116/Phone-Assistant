package com.phoneassistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlin.random.Random

/**
 * A canvas Pip painted. The brush fills in as he works, and once it is finished it stays
 * on the screen for the user to look at, drag around, and ask him about.
 */
internal class Painting(host: PropHost, val picture: PaintingGallery.Picture, private val image: Bitmap) : Prop(host) {

    enum class Style(val title: String, val blurb: String, val drawable: Int) {
        SPACE_CAT("One Small Purr for Space", "a crayon astronaut kitten holding a star on the moon", R.drawable.crayon_cat),
        TEACUP_FROG("The Teacup Regatta", "a frog in a raincoat sailing a teacup", R.drawable.crayon_frog),
        FLOWER_GHOST("Boo, But Make It a Daisy", "a shy pink ghost holding a giant daisy", R.drawable.crayon_ghost),
        SLOW_MAIL("Snail Mail, Literally", "a snail delivering a letter to a mailbox under a red mushroom", R.drawable.crayon_slow_mail),
        MIDNIGHT_LIBRARY("The Midnight Library", "a raccoon librarian reading by a mushroom lamp at night", R.drawable.crayon_midnight_library),
        PENGUIN_BAKERY("A Bun of Unreasonable Size", "a penguin baker holding up a very large cinnamon bun", R.drawable.crayon_penguin_bakery),
        DRAGON_GARDEN("How to Water the Sun", "a lavender dragon watering an enormous sunflower", R.drawable.crayon_dragon_garden),
        OTTER_ORCHESTRA("Otterly Unplugged", "a floating otter playing ukulele among the fireflies", R.drawable.crayon_otter_orchestra)
    }

    override val boxW = 172f
    override val boxH = 136f

    var finished = false
        private set

    /** 0 to 1, how much of the painting has been painted. */
    private var brush = 0f

    /**
     * When the brush last made a sound. The stroke advances every frame at sixty frames a
     * second, and a chalk sound per frame is a buzz rather than a painting, so it is heard in
     * strokes: one every few hundred milliseconds, which is also how a hand actually moves.
     */
    private var lastStrokeSound = 0L
    private var paintFromLeft: Boolean? = null
    private var walkingToSeat = false
    private var finishedAt = 0L

    internal fun approachFrom(left: Boolean) {
        paintFromLeft = left
    }

    internal override fun keepsToy(now: Long): Boolean = !finished || now - finishedAt < 8_000L

    private fun trySit() {
        val left = paintFromLeft ?: return
        if (walkingToSeat || host.petIsSeatedAt(this) || movingByUser) return
        val x = ToyFooting.beside(posX, windowW, host.petWidth(), host.stageWidth(), dp(6f), preferLeft = left)
        val feet = (posY + windowH - dp(FRAME_MARGIN_DP)).coerceAtMost(host.toysFloor())
        val y = ToyFooting.centreForFeet(feet, host.petHeight(), dp(1f))
        walkingToSeat = host.petWalkTo(this, x, y) { arrived ->
            walkingToSeat = false
            if (arrived && !finished && !leaving) host.petSitAt(this)
        }
    }

    private val frame = fill(0xFFAA8EEF.toInt())
    private val frameEdge = stroke(0xFF3B2D64.toInt(), 2.2f)
    private val frameShade = fill(0x667339AF)
    private val cloth = fill(0xFFFFF3D8.toInt())
    private val clothEdge = stroke(0xFF674C89.toInt(), 1.2f)
    private val tape = fill(0xFFFFD77D.toInt())
    private val bobble = fill(0xFFFFF2D5.toInt())
    private val swatches = intArrayOf(0xFFFF9AAE.toInt(), 0xFFFFDF80.toInt(), 0xFF8FE8C5.toInt(), 0xFF83C8FF.toInt()).map(::fill)
    private val signature = text(0xFF2E2155.toInt(), 7f, true)
    private val paperLine = stroke(0x19886B69, 0.7f)
    private val artArea = RectF()
    private val imageRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The frame's outer box, reused: this canvas is redrawn while it is being painted. */
    private val frameRect = RectF()

    private val sheen = Sheen(dp(52f), 0x22FFFFFF.toInt(), SHEEN_PERIOD_MS)

    override fun onFrame() {
        if (brush < 1f) {
            trySit()
            val before = brush
            brush = (brush + 0.008f).coerceAtMost(1f)
            val now = SystemClock.uptimeMillis()
            // Only once he is actually painting, and only every so often.
            if (before > 0.02f && now - lastStrokeSound >= 340L) {
                lastStrokeSound = now
                sound(if (brush > 0.97f) ToySounds.Sound.SPLAT else ToySounds.Sound.CHALK, 0.55f)
            }
            if (brush >= 1f) finishPainting()
            invalidate()
            return
        }
        // Finished, so the sheen is the only thing still moving. Redraw while it is on
        // screen and leave the rest of its cycle as a still frame: a canvas this detailed
        // is the most expensive thing on the screen to repaint for no reason.
        if (sheen.visible(SystemClock.uptimeMillis(), bornAt)) invalidate()
    }

    private fun finishPainting() {
        finished = true
        finishedAt = SystemClock.uptimeMillis()
        host.cancelPetWalk(this)
        val canWalkAway = host.petLeaveSeat(this)
        host.feel(PetView.Mood.HAPPY, 3200L)
        host.sound(ToySounds.Sound.SPARKLE, 0.8f)
        host.say("done. it's \"${picture.title}\". *holds it out* still wet, and I'm quietly pleased.")
        if (canWalkAway) host.petWalkTo(this,
            host.stageWidth() * (0.2f + Random.nextFloat() * 0.6f),
            host.stageHeight() * (0.4f + Random.nextFloat() * 0.3f)
        )
    }

    override fun onDragStarted() {
        host.cancelPetWalk(this)
        host.petLeaveSeat(this)
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

        frameRect.set(dp(FRAME_MARGIN_DP), dp(FRAME_MARGIN_DP), w - dp(FRAME_MARGIN_DP), h - dp(FRAME_MARGIN_DP))
        panel(canvas, frameRect, 13f, frameEdge, frame)
        canvas.drawRoundRect(dp(10f), h - dp(22f), w - dp(10f), h - dp(6f), dp(7f), dp(7f), frameShade)
        artArea.set(dp(10f), dp(10f), w - dp(10f), h - dp(23f))
        canvas.drawRect(artArea, cloth)
        for (i in 1..7) {
            val y = artArea.top + artArea.height() * i / 8f
            canvas.drawLine(artArea.left, y, artArea.right, y, paperLine)
        }
        val paintedRight = artArea.left + artArea.width() * brush
        canvas.save()
        canvas.clipRect(artArea.left, artArea.top, paintedRight, artArea.bottom)
        drawArt(canvas)
        canvas.restore()
        drawSheen(canvas)
        canvas.drawRect(artArea, clothEdge)
        if (!finished) {
            val tipY = artArea.top + artArea.height() * (0.5f + kotlin.math.sin(brush * 23f) * 0.30f)
            canvas.drawCircle(paintedRight, tipY, dp(3.1f), tape)
        }
        for (i in swatches.indices) {
            canvas.drawCircle(dp(19f + 10f * i), h - dp(13f), dp(3.8f), swatches[i])
        }
        centred(canvas, if (finished) "SIGNED: PIP" else "PAINTING…", w - dp(36f), h - dp(13f), signature)
        canvas.save()
        canvas.rotate(-18f, dp(15f), dp(11f))
        canvas.drawRoundRect(dp(6f), dp(7f), dp(24f), dp(14f), dp(1.5f), dp(1.5f), tape)
        canvas.restore()
        canvas.drawCircle(w - dp(10f), dp(10f), dp(3f), bobble)
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
        val scale = minOf(artArea.width() / image.width, artArea.height() / image.height)
        val halfW = image.width * scale / 2f
        val halfH = image.height * scale / 2f
        val x = artArea.centerX()
        val y = artArea.centerY()
        imageRect.set(x - halfW, y - halfH, x + halfW, y + halfH)
        canvas.drawBitmap(image, null, imageRect, bitmapPaint)
    }

    override fun describe(): String =
        "\"${picture.title}\", a painting on a canvas — ${picture.blurb}"

    companion object {
        private const val FRAME_MARGIN_DP = 3f
        private const val SHEEN_PERIOD_MS = 7_200L

        val WET_LINES = listOf(
            "give it a minute. still wet.",
            "still drying. patience is part of the medium.",
            "not yet — the paint's still deciding.",
            "touch it now and it becomes modern art. differently."
        )

        val TAP_LINES = listOf(
            "careful. one of one, literally.",
            "the brush did all the difficult bits.",
            "no flash photography. it's shy.",
            "*holds it up* best work. also my only work.",
            "look all you like. I'm not explaining the middle.",
            "that's not a smudge. that's a statement."
        )
    }
}

/** Place the canvas before starting the optional walk. A tap, keyboard move or chat can
 * interrupt the walk without leaving the user with an empty screen. */
internal class PaintingScene(private val host: PropHost, private val picture: PaintingGallery.Picture) {
    fun begin(): PaintingStart.Result {
        val ctx = host.stageContext()
        val bitmap = PaintingGallery.bitmap(ctx, picture) ?: return PaintingStart.Result.UNREADABLE
        val art = Painting(host, picture, bitmap)
        val w = host.stageWidth().toFloat()
        val h = host.stageHeight().toFloat()
        val floor = host.toysFloor()
        val gap = host.px(6f)
        val spotX = w * (0.28f + Random.nextFloat() * 0.34f)
        val spotY = h * (0.3f + Random.nextFloat() * 0.18f)
        val halfPet = host.petWidth() / 2f
        val x = if (w - spotX - halfPet > art.windowW + gap) spotX + halfPet + gap
            else spotX - halfPet - art.windowW - gap
        val y = (spotY + host.petHeight() / 2f - art.windowH * 0.82f).coerceAtMost(floor - art.windowH)
        if (art.windowW > w || art.windowH > floor - host.stageTop() || !art.bring(x, y)) {
            bitmap.recycle() // No attached view owns it.
            return PaintingStart.Result.NO_ROOM
        }

        art.approachFrom(left = x > spotX)
        host.say("*sets up the easel* give me a minute. inspiration is scheduled.")
        return PaintingStart.Result.SHOWN
    }
}
