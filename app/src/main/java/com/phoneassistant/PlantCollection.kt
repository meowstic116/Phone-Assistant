package com.phoneassistant

import android.content.Context

internal object PlantCollection {
    fun preferences(context: Context) = context.getSharedPreferences("plant_collection", Context.MODE_PRIVATE)

    fun counts(context: Context): Map<PlantSpecies, Long> = preferences(context).let { prefs ->
        PlantSpecies.values().associateWith { prefs.getLong(it.id, 0L) }
    }

    fun nextSpecies(context: Context): PlantSpecies =
        PlantSelection.pick(counts(context), kotlin.random.Random.Default::nextInt)

    @Synchronized
    fun bloom(context: Context, species: PlantSpecies): PlantDiscovery.Bloom {
        val prefs = preferences(context)
        val bloom = PlantDiscovery.record(prefs.getLong(species.id, 0L))
        prefs.edit().putLong(species.id, bloom.count).apply()
        return bloom
    }
}
