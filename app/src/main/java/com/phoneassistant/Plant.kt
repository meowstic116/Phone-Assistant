package com.phoneassistant

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import com.phoneassistant.PlantGrowth.Companion.WATER_PER_STAGE
import kotlin.math.abs

/**
 * A pot plant. Tap to water it and it grows a stage at a time; tap it too fast and Pip
 * accuses you of drowning it.
 */
class Plant(host: PropHost) : Prop(host) {

    override val boxW = 119f
    override val boxH = 144f + PlantDiscovery.LABEL_SPACE_DP

    private val flower = PlantCollection.nextSpecies(context)
    private val growth = PlantGrowth()
    private var hurried = 0
    private var lastTap = -1_000L
    private var warnedFlood = false
    private var dropUntil = 0L
    private var growthAt = 0L
    private var lastAutoAt = 0L
    private var nextPourAt = bornAt + PlantGrowth.FIRST_POUR_DELAY_MS
    private var pour: Float? = null
    private var canOnLeft = true
    private var firstPour = true
    private var discovery = false

    internal override fun keepsToy(now: Long): Boolean = when {
        growth.blooming -> now - growthAt < 8_000L
        growth.waitingForUser -> now - maxOf(lastAutoAt, growthAt, bornAt) < 60_000L
        else -> now - maxOf(lastAutoAt, bornAt) < 30_000L
    }

    /** Degrees of lean toward Pip, eased rather than set, so a step is a sway and not a jump. */
    private var lean = 0f

    private val outline = stroke(0xFF352445.toInt(), 2.1f)
    private val pot = fill(0xFFFF8B99.toInt())
    private val potShade = fill(0xFFD45582.toInt())
    private val goldPot = fill(0xFFFFCA4F.toInt())
    private val goldShade = fill(0xFFB97A1C.toInt())
    private val goldRim = fill(0xFFFFEBA1.toInt())
    private val goldRing = stroke(0xFFFFEFA7.toInt(), 1.35f)
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
    private val namePill = fill(0xF0222035.toInt())
    private val label = text(0xFFF2EFFB.toInt(), 9.6f, true)
    private val celebrationInk = fill(flower.discoveryColor)
    private val sweepWidth = dp(26f)
    private val sweepMatrix = Matrix()
    private fun shimmer(base: Int) = LinearGradient(0f, 0f, sweepWidth, 0f,
        intArrayOf(base, 0xFFFFFFFF.toInt(), base), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
    private val normalShimmer = shimmer(label.color)
    private val discoveryShimmer = shimmer(flower.discoveryColor)

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
        if (growth.water(now, PlantGrowth.Waterer.USER)) {
            dropUntil = now + 460L
            sound(ToyAudio.Sound.POUR, 0.5f)
            invalidate()
        }
    }

    private fun tryPour(now: Long) {
        if (movingByUser || !growth.pipMayWater || now < nextPourAt || now - lastTap < 450L || width <= 0) return
        nextPourAt = now + 160L
        val cx = posX + width / 2f
        val potTop = posY + potTop()
        val scale = dp(1f)
        val leftGrip = WateringPose.grip(cx, potTop, true, scale)
        val rightGrip = WateringPose.grip(cx, potTop, false, scale)
        val left = WateringPose.stance(leftGrip, true, scale).x
        val right = WateringPose.stance(rightGrip, false, scale).x
        val halfPet = host.petWidth() / 2f
        val useLeft = left >= halfPet && (right > host.stageWidth() - halfPet ||
            host.petCentreX() <= cx)
        val grip = if (useLeft) leftGrip else rightGrip
        if (host.petWaterPlant(this, grip.x, grip.y, facesRight = useLeft,
                progress = { frame ->
                    canOnLeft = useLeft
                    pour = frame
                    invalidate()
                }, watered = {
                    val landed = SystemClock.uptimeMillis()
                    lastAutoAt = landed
                    nextPourAt = landed + PlantGrowth.PIP_POUR_PAUSE_MS
                    if (!leaving && growth.water(landed, PlantGrowth.Waterer.PIP)) {
                        sound(ToyAudio.Sound.POUR, 0.42f)
                        if (firstPour) {
                            firstPour = false
                            host.say("a little water from me. give the pot a tap to add yours.")
                        } else if (growth.waitingForUser) {
                            host.say("your turn. one more drop and it’ll bloom.")
                        }
                        invalidate()
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
                discovery = PlantCollection.bloom(context, flower).first
                host.feel(PetView.Mood.HAPPY, 3_000L)
                host.sound(ToyAudio.Sound.SPARKLE, 0.9f)
                host.say(if (discovery) "${flower.display}! a new bloom for your collection."
                    else "*sets down the can* a ${flower.display}. look, the ladybug approves.")
            } else {
                host.feel(PetView.Mood.HAPPY, 900L)
                host.sound(ToyAudio.Sound.GROW, 0.6f)
                host.say(GROW_LINES[growth.stage - 1])
            }
        }
        if (growthAt > 0L && (now - growthAt <= (if (growth.blooming) 3_250L else 460L) ||
                growth.blooming && ValueAnimator.areAnimatorsEnabled())) invalidate()
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

    private fun potBase() = height - dp(6f + PlantDiscovery.LABEL_SPACE_DP)
    private fun potTop() = potBase() - dp(WateringPose.POT_DEPTH_DP)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val potTop = potTop()
        val foot = potBase()
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
            for (i in 0 until flower.petals) {
                val x = cx + dp(12f) * flower.petalX[i]
                val y = stemTop + dp(12f) * flower.petalY[i]
                BloomArt.petal(canvas, flower.shape, x, y, dp(8.3f), flower.angle(i),
                    if (i % 2 == 0) petal else petalLight, path)
                canvas.drawCircle(x - dp(2.4f), y - dp(2.7f), dp(1.5f), shine)
            }
            if (flower == PlantSpecies.GOLDEN) {
                canvas.drawCircle(cx, stemTop, dp(27f), goldRing)
                sparkle(canvas, cx, stemTop - dp(27f), 3.7f)
            }
            canvas.drawCircle(cx, stemTop, dp(8f), if (flower == PlantSpecies.GOLDEN) goldRim else centre)
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
        val golden = growth.blooming && flower == PlantSpecies.GOLDEN
        canvas.drawPath(potPath, if (golden) goldPot else pot)
        canvas.save()
        canvas.clipPath(potPath)
        canvas.drawOval(cx - dp(27f), foot - dp(9f), cx + dp(34f), foot + dp(13f), if (golden) goldShade else potShade)
        canvas.restore()
        canvas.drawPath(potPath, outline)
        canvas.drawRoundRect(cx - dp(29f), potTop - dp(1f), cx + dp(29f), potTop + dp(8f), dp(4f), dp(4f), if (golden) goldRim else rim)
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
        if (growth.blooming) {
            val elapsed = now - growthAt
            val animated = ValueAnimator.areAnimatorsEnabled()
            if (discovery && animated && elapsed < PlantDiscovery.CELEBRATION_MS) {
                celebrate(canvas, cx, stemTop, elapsed / PlantDiscovery.CELEBRATION_MS.toFloat())
            }
            drawLabel(canvas, flower.display, cx, foot, elapsed, animated)
        } else if (growth.waitingForUser) {
            drawLabel(canvas, "Tap for the last drop", cx, foot, 0L, animated = false)
        }
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

    /** Fixed to the pot, not the leaning stem or the moving ladybug. */
    private fun drawLabel(canvas: Canvas, title: String, cx: Float, foot: Float, elapsed: Long, animated: Boolean) {
        val half = label.measureText(title) / 2f
        val midY = foot + dp(16f)
        canvas.drawRoundRect(cx - half - dp(7f), midY - dp(7f), cx + half + dp(7f), midY + dp(7f),
            dp(7f), dp(7f), namePill)
        label.color = if (discovery) flower.discoveryColor else 0xFFF2EFFB.toInt()
        val sweep = if (animated) PlantDiscovery.shimmer(elapsed) else null
        if (sweep != null) {
            val shader = if (discovery) discoveryShimmer else normalShimmer
            sweepMatrix.setTranslate(cx - half - sweepWidth + sweep * (2f * half + sweepWidth), 0f)
            shader.setLocalMatrix(sweepMatrix)
            label.shader = shader
        }
        centred(canvas, title, cx, midY, label)
        label.shader = null
    }

    /** First blooms only: pink petals orbit, golden rays burst, blue bells send out dew. */
    private fun celebrate(canvas: Canvas, cx: Float, y: Float, progress: Float) {
        val t = progress.coerceIn(0f, 1f)
        celebrationInk.alpha = (245f * (1f - t) * minOf(1f, t * 8f + 0.35f)).toInt()
        when (flower) {
            PlantSpecies.COSMOS -> for (i in 0..6) {
                val angle = i * 2 * Math.PI / 7 + t * Math.PI * 1.3
                val x = cx + dp(24f + 23f * t) * kotlin.math.cos(angle).toFloat()
                val petalY = y + dp(16f + 12f * t) * kotlin.math.sin(angle).toFloat()
                canvas.save()
                canvas.rotate((angle * 180 / Math.PI).toFloat(), x, petalY)
                canvas.drawOval(x - dp(3.7f), petalY - dp(1.9f), x + dp(3.7f), petalY + dp(1.9f), celebrationInk)
                canvas.restore()
            }
            PlantSpecies.STAR -> for (i in 0..7) {
                val angle = i * Math.PI / 4
                val x = cx + dp(23f + 25f * t) * kotlin.math.cos(angle).toFloat()
                val pointY = y + dp(23f + 11f * t) * kotlin.math.sin(angle).toFloat()
                val ray = dp(3.9f - 1.8f * t)
                canvas.save()
                canvas.rotate(45f + 80f * t, x, pointY)
                canvas.drawRect(x - ray, pointY - dp(0.95f), x + ray, pointY + dp(0.95f), celebrationInk)
                canvas.drawRect(x - dp(0.95f), pointY - ray, x + dp(0.95f), pointY + ray, celebrationInk)
                canvas.restore()
            }
            PlantSpecies.BLUEBELL -> for (i in 0..7) {
                val x = cx + dp((i - 3.5f) * (5.5f + 7f * t))
                val dropY = y + dp(3f - 24f * kotlin.math.sin(t * Math.PI).toFloat() + 32f * t * t + (i % 3) * 5f)
                canvas.drawOval(x - dp(1.8f), dropY - dp(3f), x + dp(1.8f), dropY + dp(3f), celebrationInk)
            }
            PlantSpecies.GOLDEN -> {
                val radius = dp(25f + 27f * t)
                goldRing.alpha = celebrationInk.alpha
                val ringY = y + dp(11f)
                val ry = radius * 0.67f
                canvas.drawOval(cx - radius, ringY - ry, cx + radius, ringY + ry, goldRing)
                canvas.drawOval(cx - radius * 0.79f, ringY - ry * 0.79f,
                    cx + radius * 0.79f, ringY + ry * 0.79f, goldRing)
                goldRing.alpha = 255
                for (i in 0..7) {
                    val angle = i * Math.PI / 4 - 1.2 * t
                    val x = cx + radius * kotlin.math.cos(angle).toFloat()
                    val yy = y + dp(11f) + radius * 0.66f * kotlin.math.sin(angle).toFloat()
                    BloomArt.petal(canvas, PetalShape.DIAMOND, x, yy, dp(4.4f - t * 2f), 90f, celebrationInk, path)
                }
            }
            else -> {
                // Each silhouette and petal count carries through to its discovery particles.
                val turn = (if (flower.petals % 2 == 0) 1 else -1) * t * 1.6f
                for (i in 0 until flower.petals) {
                    val angle = i * 2 * Math.PI / flower.petals + turn
                    val spread = 24f + 22f * t * (if (i % 2 == 0) 1f else 0.65f)
                    val x = cx + dp(spread) * kotlin.math.cos(angle).toFloat()
                    val yy = y + dp(8f) + dp(spread * 0.64f) * kotlin.math.sin(angle).toFloat()
                    BloomArt.petal(canvas, flower.shape, x, yy, dp(3.8f - 1.3f * t),
                        (angle * 180 / Math.PI).toFloat(), celebrationInk, path)
                }
            }
        }
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
        val scale = dp(1f)
        val grip = WateringPose.grip(cx, potTop, canOnLeft, scale)
        val angle = WateringPose.tilt(fraction)
        canvas.save()
        // Rotate the can around the back of its handle, where Pip is holding it.
        canvas.translate(grip.x, grip.y)
        canvas.scale(WateringPose.direction(canOnLeft), 1f)
        canvas.rotate(angle)
        canvas.translate(dp(WateringPose.HANDLE_FROM_CAN), 0f)
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
            val nozzle = WateringPose.spout(grip, canOnLeft, scale, angle)
            for (i in 0..5) {
                val t = (fraction * 5f + i * 0.17f) % 1f
                val dx = nozzle.x + (cx - nozzle.x) * t
                val dy = nozzle.y + (potTop - nozzle.y) * t * t
                canvas.drawOval(dx - dp(1.5f), dy - dp(2.4f), dx + dp(1.5f), dy + dp(2.4f), droplet)
            }
        }
    }

    override fun describe(): String =
        if (growth.blooming) "a blooming ${flower.display} with a tiny ladybug"
        else "a ${flower.display} at growth stage ${growth.stage} of ${PlantGrowth.MAX_STAGE}. " +
            when {
                growth.waitingForUser -> "The user needs to tap the pot for the last drop."
                growth.wateredByUser -> "The user already watered it; Pip can help with the rest."
                else -> "Pip helps water it, but the user must water it at least once for it to bloom."
            }

    companion object {
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
