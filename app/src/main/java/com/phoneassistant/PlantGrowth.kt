package com.phoneassistant

/** Shared growth for a user's tap and Pip's finished pour. */
internal class PlantGrowth {
    enum class Waterer { USER, PIP }

    var wateredByUser = false
        private set
    var stage = 0
        private set
    var water = 0
        private set
    private var growAt: Long? = null
    val blooming: Boolean get() = stage == MAX_STAGE
    val growing: Boolean get() = growAt != null
    val thirsty: Boolean get() = !blooming && !growing

    val waitingForUser: Boolean get() = !wateredByUser && stage == MAX_STAGE - 1 && water == WATER_PER_STAGE - 1
    val pipMayWater: Boolean get() = thirsty && !waitingForUser

    fun water(now: Long, by: Waterer): Boolean {
        if (!thirsty || by == Waterer.PIP && !pipMayWater) return false
        if (by == Waterer.USER) wateredByUser = true
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
        const val FIRST_POUR_DELAY_MS = 2_500L
        const val PIP_POUR_PAUSE_MS = 3_200L
        const val PIP_POUR_DURATION_MS = 900L
    }
}
