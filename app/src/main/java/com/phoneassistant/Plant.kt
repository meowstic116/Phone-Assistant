package com.phoneassistant

import android.graphics.Canvas
import android.graphics.Path
import android.os.SystemClock
import com.phoneassistant.PlantGrowth.Companion.WATER_PER_STAGE
import kotlin.math.abs

/**
 * A pot plant. Tap to water it and it grows a stage at a time; tap it too fast and Pip
 * accuses you of drowning it.
 */
class Plant(host: PropHost) : Prop(host) {

    override val boxW = 119f
    override val boxH = 144f

    private enum class Flower(val display: String, val main: Int, val light: Int, val petals: Int) {
        COSMOS("Pocket Cosmos", 0xFFFF74A9.toInt(), 0xFFFFB2CF.toInt(), 6),
        STAR("Lemon Star", 0xFFFFB14D.toInt(), 0xFFFFE18A.toInt(), 8),
        BLUEBELL("Blueberry Bell", 0xFF8D90FF.toInt(), 0xFFB5D5FF.toInt(), 5)
    }
    private val flower = Flower.values().random()
    private val growth = PlantGrowth()
    private var hurried = 0
    private var lastTap = -1_000L
    private var warnedFlood = false
    private var dropUntil = 0L
    private var growthAt = 0L
    private var lastAutoAt = 0L
    private var nextPourAt = 0L
    private var pour: Float? = null
    private var canOnLeft = true
    private var firstPour = true

    internal fun tendingThrough(now: Long): Boolean =
        if (growth.blooming) now - growthAt < 3_400L else lastAutoAt != 0L && now - lastAutoAt < 1_250L

    /** Degrees of lean toward Pip, eased rather than set, so a step is a sway and not a jump. */
    private var lean = 0f

    private val outline = stroke(0xFF352445.toInt(), 2.1f)
    private val pot = fill(0xFFFF8B99.toInt())
    private val potShade = fill(0xFFD45582.toInt())
    private val rim = fill(0xFFFFC69D.toInt())
    private val soil = fill(0xFF59405C.toInt())
    private val stem = stroke(0xFF21534D.toInt(), 4.5f)
    private val stemShine = stroke(0xFF89E18A.toInt(), 2.0f)
    private val leaf = fill(0xFF74DA99.toInt())
    private val leafLight = fill(0xFFB8F68C.toInt())
    private val petal = fill(flower.main)
    private val petalLight = fill(flower.light)
    private val centre = fill(0xFFFFD66B.toInt())
    private val droplet = fill(0xFF74D9FF.toInt())
    private val shine = fill(0xEFFFF6DF.toInt())
    private val cheek = fill(0xFFF06E8F.toInt())
    private val eyes = fill(0xFF352445.toInt())
    private val shadow = fill(0x66352654)
    private val meterEmpty = fill(0xFF9B426D.toInt())
    private val path = Path()
    private val potPath = Path()
    private val canPaint = fill(0xFF62DFD0.toInt())
    private val bug = fill(0xFFFF6C78.toInt())
    private val label = text(0xFF352445.toInt(), 7.3f, true)
    private val petalX = FloatArray(flower.petals) { kotlin.math.cos(it * 2 * Math.PI / flower.petals).toFloat() }
    private val petalY = FloatArray(flower.petals) { kotlin.math.sin(it * 2 * Math.PI / flower.petals).toFloat() }

    override fun onTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (growth.blooming) {
            if (now - lastTap >= 1_000L) host.say(Lines.pick(FULL_LINES))
            lastTap = now
            return
        }
        val tooQuick = now - lastTap < 320L
        lastTap = now
        if (tooQuick) {
            if (++hurried >= 5 && !warnedFlood) {
                warnedFlood = true
                host.feel(PetView.Mood.ANNOYED, 2400L)
                host.say(Lines.pick(OVERWATER_LINES))
            }
            return
        }
        hurried = 0
        if (growth.water(now)) {
            dropUntil = now + 460L
            invalidate()
        }
    }

    private fun tryPour(now: Long) {
        if (held || !growth.thirsty || now < nextPourAt || now - lastTap < 450L ||
            width <= 0 || now - bornAt < 500L) return
        nextPourAt = now + 160L
        val cx = centreX
        val left = cx - dp(57f)
        val right = cx + dp(57f)
        val halfPet = host.petWidth() / 2f
        val useLeft = left >= halfPet && (right > host.stageWidth() - halfPet ||
            host.petCentreX() <= cx)
        val feetY = posY + height - dp(6f)
        if (host.petWaterPlant(this, if (useLeft) left else right, feetY, facesRight = useLeft,
                progress = { frame ->
                    canOnLeft = useLeft
                    pour = frame
                    invalidate()
                }, watered = {
                    val landed = SystemClock.uptimeMillis()
                    lastAutoAt = landed
                    nextPourAt = landed + 170L
                    if (!leaving && growth.water(landed) && firstPour) {
                        firstPour = false
                        host.say(Lines.pick(PIP_WATER_LINES))
                    }
                })) lastAutoAt = now
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
        if (warnedFlood && now - lastTap > 4_000L) {
            warnedFlood = false
            hurried = 0
        }
        if (growth.advance(now)) {
            growthAt = now
            if (growth.blooming) {
                host.feel(PetView.Mood.HAPPY, 3_000L)
                host.say("*sets down the can* a ${flower.display}. look, the ladybug approves.")
            } else {
                host.feel(PetView.Mood.HAPPY, 900L)
                host.say(GROW_LINES[growth.stage - 1])
            }
        }
        if (growthAt > 0L && now - growthAt <= (if (growth.blooming) 3_250L else 460L)) invalidate()
        tryPour(now)
    }

    private fun sparkle(canvas: Canvas, x: Float, y: Float, size: Float) {
        val r = dp(size)
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x + r * 0.2f, y - r * 0.2f, x + r, y)
        path.quadTo(x + r * 0.2f, y + r * 0.2f, x, y + r)
        path.quadTo(x - r * 0.2f, y + r * 0.2f, x - r, y)
        path.quadTo(x - r * 0.2f, y - r * 0.2f, x, y - r)
        path.close()
        canvas.drawPath(path, centre)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val cx = width / 2f
        val potTop = h - dp(43f)
        val foot = h - dp(6f)
        canvas.drawOval(cx - dp(35f), foot - dp(4f), cx + dp(35f), foot + dp(5f), shadow)

        // Lavender soil beneath a bouncy silhouette that grows from sprout to bloom.
        canvas.drawOval(cx - dp(28f), potTop - dp(7f), cx + dp(28f), potTop + dp(7f), rim)
        canvas.drawOval(cx - dp(24f), potTop - dp(4f), cx + dp(24f), potTop + dp(4f), soil)
        val now = SystemClock.uptimeMillis()
        val stage = growth.stage
        val stemTop = potTop - dp(12f + stage * 12f)
        val bounce = 1f + if (growthAt > 0L && now - growthAt in 0L..440L) {
            val t = (now - growthAt) / 440f
            kotlin.math.sin(t * Math.PI * 2).toFloat() * (1f - t) * 0.12f
        } else 0f
        canvas.save()
        canvas.rotate(lean, cx, potTop)
        canvas.scale(1f, bounce, cx, potTop)
        path.reset()
        path.moveTo(cx, potTop)
        path.cubicTo(cx + dp(11f), potTop - dp(12f), cx - dp(8f), stemTop + dp(12f), cx, stemTop)
        canvas.drawPath(path, stem)
        canvas.drawPath(path, stemShine)
        for (i in 0..stage) {
            val y = potTop - dp(6f + i * 10f)
            val side = if (i % 2 == 0) -1f else 1f
            val spread = dp(15f + i * 2f) * side
            path.reset()
            path.moveTo(cx, y)
            path.quadTo(cx + spread * 0.45f, y - dp(16f), cx + spread, y - dp(12f))
            path.quadTo(cx + spread * 0.9f, y + dp(3f), cx, y)
            path.close()
            canvas.drawPath(path, if (i % 2 == 0) leaf else leafLight)
            canvas.drawPath(path, outline)
        }
        if (stage == 3) {
            canvas.drawOval(cx - dp(5f), stemTop - dp(10f), cx + dp(5f), stemTop + dp(5f), petalLight)
            canvas.drawOval(cx - dp(5f), stemTop - dp(10f), cx + dp(5f), stemTop + dp(5f), outline)
        }
        if (growth.blooming) {
            for (i in petalX.indices) {
                val x = cx + dp(12f) * petalX[i]
                val y = stemTop + dp(12f) * petalY[i]
                canvas.drawCircle(x, y, dp(8.3f), if (i % 2 == 0) petal else petalLight)
                canvas.drawCircle(x - dp(2.4f), y - dp(2.7f), dp(1.5f), shine)
            }
            canvas.drawCircle(cx, stemTop, dp(8f), centre)
            canvas.drawCircle(cx, stemTop, dp(8f), outline)
            canvas.drawCircle(cx - dp(2.7f), stemTop, dp(1.15f), eyes)
            canvas.drawCircle(cx + dp(2.7f), stemTop, dp(1.15f), eyes)
            sparkle(canvas, cx - dp(26f), stemTop - dp(4f), 4f)
            sparkle(canvas, cx + dp(27f), stemTop + dp(5f), 5.5f)
            drawLadybug(canvas, cx, stemTop, now)
        }
        canvas.restore()

        potPath.reset()
        potPath.moveTo(cx - dp(27f), potTop)
        potPath.lineTo(cx + dp(27f), potTop)
        potPath.quadTo(cx + dp(24f), foot, cx + dp(15f), foot)
        potPath.lineTo(cx - dp(15f), foot)
        potPath.quadTo(cx - dp(24f), foot, cx - dp(27f), potTop)
        potPath.close()
        canvas.drawPath(potPath, pot)
        canvas.save()
        canvas.clipPath(potPath)
        canvas.drawOval(cx - dp(27f), foot - dp(9f), cx + dp(34f), foot + dp(13f), potShade)
        canvas.restore()
        canvas.drawPath(potPath, outline)
        canvas.drawRoundRect(cx - dp(29f), potTop - dp(1f), cx + dp(29f), potTop + dp(8f), dp(4f), dp(4f), rim)
        canvas.drawLine(cx - dp(22f), potTop + dp(2f), cx - dp(9f), potTop + dp(2f), stemShine)
        val faceY = potTop + dp(17f)
        canvas.drawCircle(cx - dp(7f), faceY, dp(2.2f), eyes)
        canvas.drawCircle(cx + dp(7f), faceY, dp(2.2f), eyes)
        canvas.drawCircle(cx - dp(12f), faceY + dp(2f), dp(2.5f), cheek)
        canvas.drawCircle(cx + dp(12f), faceY + dp(2f), dp(2.5f), cheek)
        canvas.drawArc(cx - dp(4f), faceY - dp(1f), cx + dp(4f), faceY + dp(6f), 20f, 140f, false, outline)
        for (i in 0 until WATER_PER_STAGE) {
            val x = cx + dp((i - 1) * 8f)
            canvas.drawCircle(x, foot - dp(5f), dp(2.4f),
                if (i < growth.water || growth.blooming) droplet else meterEmpty)
        }
        if (growth.blooming) centred(canvas, flower.display, cx, potTop + dp(3.4f), label)
        if (dropUntil > 0L) {
            for (i in 0..2) {
                val x = cx + dp(9f + i * 8f)
                val y = potTop - dp(12f + i * 6f)
                path.reset()
                path.moveTo(x, y - dp(6f))
                path.cubicTo(x - dp(10f), y + dp(5f), x + dp(10f), y + dp(6f), x, y - dp(6f))
                canvas.drawPath(path, droplet)
                canvas.drawPath(path, outline)
            }
        }
        pour?.let { drawPour(canvas, cx, potTop, it) }
    }

    private fun drawLadybug(canvas: Canvas, cx: Float, stemTop: Float, now: Long) {
        val t = ((now - growthAt) / 2_200f).coerceIn(0f, 1f)
        val x = cx + dp(45f - t * 25f)
        val y = stemTop + dp(21f * t - 7f + kotlin.math.sin(t * Math.PI * 3).toFloat() * 7f * (1f - t))
        canvas.drawOval(x - dp(4.5f), y - dp(3.4f), x + dp(4.5f), y + dp(3.4f), bug)
        canvas.drawCircle(x - dp(3.8f), y, dp(2f), eyes)
        canvas.drawCircle(x + dp(1.1f), y - dp(1.55f), dp(0.85f), eyes)
        canvas.drawCircle(x + dp(2.2f), y + dp(1.2f), dp(0.85f), eyes)
    }

    private fun drawPour(canvas: Canvas, cx: Float, potTop: Float, fraction: Float) {
        val toward = if (canOnLeft) 1f else -1f
        val x = cx - toward * dp(35f)
        val y = potTop - dp(29f)
        val tip = kotlin.math.sin(fraction * Math.PI).toFloat()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(toward, 1f)
        canvas.rotate(tip * 33f)
        canvas.drawOval(-dp(18f), -dp(9f), dp(2f), dp(7f), outline)
        path.reset()
        path.moveTo(dp(4f), 0f)
        path.lineTo(dp(20f), -dp(8f))
        path.lineTo(dp(22f), -dp(3f))
        path.lineTo(dp(5f), dp(6f))
        path.close()
        canvas.drawPath(path, canPaint)
        canvas.drawPath(path, outline)
        canvas.drawRoundRect(-dp(10f), -dp(9f), dp(10f), dp(9f), dp(4f), dp(4f), canPaint)
        canvas.drawRoundRect(-dp(10f), -dp(9f), dp(10f), dp(9f), dp(4f), dp(4f), outline)
        canvas.drawCircle(0f, 0f, dp(3.4f), shine)
        canvas.restore()
        if (fraction in 0.19f..0.94f) {
            for (i in 0..5) {
                val t = (fraction * 5f + i * 0.17f) % 1f
                val dx = x + toward * dp(21f + t * 12f)
                val dy = y + dp(4f + t * t * 24f)
                canvas.drawOval(dx - dp(1.5f), dy - dp(2.4f), dx + dp(1.5f), dy + dp(2.4f), droplet)
            }
        }
    }

    override fun describe(): String =
        if (growth.blooming) "a blooming ${flower.display} with a tiny ladybug"
        else "a ${flower.display} at growth stage ${growth.stage} of ${PlantGrowth.MAX_STAGE}, watered by Pip or by tapping"

    companion object {
        val PIP_WATER_LINES = listOf(
            "*tips the can* they put me in charge of one leaf. big day.",
            "a little water. I'm being responsible, briefly.",
            "watering shift. very small department."
        )
        val OVERWATER_LINES = listOf(
            "ease off. it's a plant, not a swimming pool.",
            "that's plenty. it's not a fish."
        )
        val FULL_LINES = listOf(
            "that's full size. even the ladybug noticed.",
            "no more water, please. she's expecting company."
        )
        val GROW_LINES = listOf(
            "*peers at the pot* a new leaf. promotion approved.",
            "we're nearly a shrub. don't tell the windowsill.",
            "a bud! everyone act natural."
        )
    }
}
