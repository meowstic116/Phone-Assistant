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

/** Unit-height parabolic leap: at ground at launch and landing, at full lift halfway. */
internal fun hopLift(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return 4f * t * (1f - t)
}
