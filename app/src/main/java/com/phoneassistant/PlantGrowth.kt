package com.phoneassistant

/** Shared growth for a user's tap and Pip's finished pour. */
internal class PlantGrowth {
    var stage = 0
        private set
    var water = 0
        private set
    private var growAt: Long? = null
    val blooming: Boolean get() = stage == MAX_STAGE
    val growing: Boolean get() = growAt != null
    val thirsty: Boolean get() = !blooming && !growing

    fun water(now: Long): Boolean {
        if (!thirsty) return false
        water++
        if (water == WATER_PER_STAGE) growAt = now + 620L
        return true
    }

    fun advance(now: Long): Boolean {
        if (growAt?.let { now >= it } != true) return false
        growAt = null
        water = 0
        stage++
        return true
    }

    companion object {
        const val MAX_STAGE = 4
        const val WATER_PER_STAGE = 3
    }
}
