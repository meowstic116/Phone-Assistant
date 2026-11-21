package com.phoneassistant

/** Shared progress for taps and Pip's landings. A bubble can only pop once. */
internal class PopSheet(val columns: Int = 4, val rows: Int = 5) {
    val total = columns * rows
    private val popped = BooleanArray(total)
    var count = 0
        private set
    var pipCount = 0
        private set

    fun popped(index: Int): Boolean = popped[index]

    /** Every remaining bubble has an equal chance, including ones beside Pip. */
    fun nextBubble(nextInt: (Int) -> Int = kotlin.random.Random.Default::nextInt): Int? {
        val remaining = total - count
        if (remaining == 0) return null
        var target = nextInt(remaining)
        for (index in popped.indices) {
            if (!popped[index] && target-- == 0) return index
        }
        return null
    }

    enum class Milestone { NONE, FIRST, FIVE, TEN, HALFWAY, FIFTEEN, COMPLETE }

    fun pop(index: Int, byPip: Boolean): Milestone? {
        if (index !in popped.indices || popped[index]) return null
        popped[index] = true
        count++
        if (byPip) pipCount++
        return when {
            count == total -> Milestone.COMPLETE
            byPip -> when (pipCount) {
                1 -> Milestone.FIRST
                5 -> Milestone.FIVE
                10 -> Milestone.TEN
                15 -> Milestone.FIFTEEN
                else -> Milestone.NONE
            }
            count == 1 -> Milestone.FIRST
            count == total / 2 -> Milestone.HALFWAY
            else -> Milestone.NONE
        }
    }
}

/** Aim one shoe at the dome's near/lower half, not both shoes at its side edges. */
internal fun bubbleLanding(
    domeX: Float, domeY: Float, radius: Float, petX: Float, petWidth: Int, stageWidth: Int, density: Float
): DragMotion.Point {
    val left = petWidth / 2f
    val right = (stageWidth - left).coerceAtLeast(left)
    val offset = ToyFooting.SHOE_CENTRE_DP * density
    val options = listOf(domeX - offset, domeX + offset)
    val centre = options.filter { it in left..right }.minByOrNull { kotlin.math.abs(it - petX) }
        ?: domeX.coerceIn(left, right)
    return DragMotion.Point(centre, domeY + radius * 0.65f)
}

/** Unit-height parabolic leap: at ground at launch and landing, at full lift halfway. */
internal fun hopLift(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return 4f * t * (1f - t)
}

/** Timings for one sheet. A blocked launch is not a hop, and an interrupted hop is not a pop. */
internal class BubbleHopSchedule(bornAt: Long) {
    private var nextAt = bornAt + 750L
    private var keepUntil = bornAt + 45_000L
    private var betweenHops = false
    private var inAir = false

    fun due(now: Long): Boolean = !inAir && now >= nextAt

    fun attempted(now: Long, started: Boolean) {
        inAir = started
        nextAt = now + 100L
        if (!started) betweenHops = false
    }

    fun finished(now: Long, landed: Boolean) {
        inAir = false
        betweenHops = landed
        nextAt = now + if (landed) 380L else 200L
        // Give an interrupted or nearly finished sheet time to resume instead of retiring it
        // against the original spawn time as soon as Pip's attention is needed elsewhere.
        keepUntil = maxOf(keepUntil, now + 30_000L)
    }

    fun reservesInput(now: Long): Boolean = inAir || (betweenHops && now < nextAt + 100L)

    fun keepsSheet(now: Long): Boolean = inAir || now < keepUntil
}
