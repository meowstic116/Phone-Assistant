package com.phoneassistant

import kotlin.random.Random

/** If no different toy is eligible, leave the screen empty rather than repeat. */
internal fun <T : Any> chooseSpontaneousToy(eligible: List<T>, last: T?, random: Random = Random.Default): T? =
    eligible.filterNot { it == last }.randomOrNull(random)
