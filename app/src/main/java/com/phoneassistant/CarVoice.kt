package com.phoneassistant

/** The part of car listening that does not touch Android, so it can be tested on a host. */
internal object CarVoice {
    /**
     * A car is a noisy, hands-busy place. When the user has opted into driving commands and a
     * hands-free link exists, an exact driving command is enough; ordinary speech still needs
     * his name. This never accepts chat, jobs, or purchases, only the bounded command set.
     */
    fun unaddressedCommand(enabled: Boolean, connected: Boolean, text: String): CarCommand? {
        if (!enabled || !connected || text.length > 300) return null
        return CarCommand.parse(text)
    }
}
