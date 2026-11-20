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

    /** Start at the bottom and zigzag back up, skipping bubbles already tapped. */
    fun nextBubble(): Int? {
        for (row in rows - 1 downTo 0) {
            for (col in 0 until columns) {
                val x = if ((rows - 1 - row) % 2 == 0) col else columns - 1 - col
                val index = row * columns + x
                if (!popped[index]) return index
            }
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
