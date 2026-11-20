package com.phoneassistant

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class PetalShape { ROUND, OVAL, POINT, HEART, FAN, DIAMOND }

/** Stable IDs keep collection progress independent of display names and catalog ordering. */
internal enum class PlantSpecies(val id: String, val display: String, val main: Int, val light: Int,
                                 val petals: Int, val discoveryColor: Int, val shape: PetalShape) {
    COSMOS("pocket_cosmos", "Pocket Cosmos", 0xFFFF74A9.toInt(), 0xFFFFB2CF.toInt(), 6, 0xFFFFADDD.toInt(), PetalShape.ROUND),
    STAR("lemon_star", "Lemon Star", 0xFFFFB14D.toInt(), 0xFFFFE18A.toInt(), 8, 0xFFFFDD81.toInt(), PetalShape.ROUND),
    BLUEBELL("blueberry_bell", "Blueberry Bell", 0xFF8D90FF.toInt(), 0xFFB5D5FF.toInt(), 5, 0xFFACDDFF.toInt(), PetalShape.ROUND),
    DAISY("cloud_daisy", "Cloud Daisy", 0xFFD5E1F2.toInt(), 0xFFFFFDFA.toInt(), 10, 0xFFF0F4FF.toInt(), PetalShape.OVAL),
    TULIP("peach_tulip", "Peach Tulip", 0xFFFF826D.toInt(), 0xFFFFB886.toInt(), 4, 0xFFFFB599.toInt(), PetalShape.FAN),
    ORCHID("mint_orchid", "Mint Orchid", 0xFF44C9A5.toInt(), 0xFFB0F8CD.toInt(), 5, 0xFFA3F8D7.toInt(), PetalShape.POINT),
    HIBISCUS("coral_hibiscus", "Coral Hibiscus", 0xFFF34E6F.toInt(), 0xFFFF8599.toInt(), 5, 0xFFFFA2AF.toInt(), PetalShape.HEART),
    ASTER("twilight_aster", "Twilight Aster", 0xFF805CD2.toInt(), 0xFFC59AFF.toInt(), 11, 0xFFDEC1FF.toInt(), PetalShape.POINT),
    LOTUS("frost_lotus", "Frost Lotus", 0xFF4ABCED.toInt(), 0xFFCAFBFF.toInt(), 7, 0xFFB8F5F9.toInt(), PetalShape.DIAMOND),
    MARIGOLD("ember_marigold", "Ember Marigold", 0xFFFF7839.toInt(), 0xFFFFBC62.toInt(), 9, 0xFFFFC483.toInt(), PetalShape.FAN),
    GOLDEN("golden_bloom", "Golden Bloom", 0xFFD99017.toInt(), 0xFFFFE994.toInt(), 9, 0xFFFFE75C.toInt(), PetalShape.DIAMOND);

    fun angle(index: Int) = 360f * index / petals - 90f
    val petalX = FloatArray(petals) { cos(angle(it) * PI / 180).toFloat() }
    val petalY = FloatArray(petals) { sin(angle(it) * PI / 180).toFloat() }
}

internal object PlantSelection {
    val regular = PlantSpecies.values().filterNot { it == PlantSpecies.GOLDEN }
    const val GOLDEN_ROLLS = 10

    fun goldenEligible(counts: Map<PlantSpecies, Long>) = regular.all { (counts[it] ?: 0L) >= 1L }

    fun collectionProgress(counts: Map<PlantSpecies, Long>): Pair<Int, Int> {
        val revealGolden = goldenEligible(counts)
        val found = regular.count { (counts[it] ?: 0L) > 0L }
        val goldFound = revealGolden && (counts[PlantSpecies.GOLDEN] ?: 0L) > 0L
        return (found + if (goldFound) 1 else 0) to (regular.size + if (revealGolden) 1 else 0)
    }

    fun pick(counts: Map<PlantSpecies, Long>, nextInt: (Int) -> Int): PlantSpecies {
        if (goldenEligible(counts) && nextInt(GOLDEN_ROLLS) == 0) return PlantSpecies.GOLDEN
        return regular[nextInt(regular.size)]
    }
}

internal object PlantDiscovery {
    const val CELEBRATION_MS = 1_550L
    const val LABEL_SPACE_DP = 21f

    data class Bloom(val count: Long, val first: Boolean)
    fun record(previous: Long): Bloom = Bloom(
        if (previous == Long.MAX_VALUE) previous else previous.coerceAtLeast(0L) + 1L,
        previous <= 0L
    )

    /** Sweep periodically, with a quiet interval between passes. */
    fun shimmer(elapsed: Long): Float? {
        val phase = elapsed.coerceAtLeast(0L) % 2_700L
        return if (phase < 1_250L) phase / 1_250f else null
    }
}
