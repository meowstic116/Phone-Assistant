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

    /**
     * The same, plus the read-only navigation question. It is always answered when asked by
     * name; this only decides whether it also works namelessly in the car, and a question that
     * reports the truth cannot change anything.
     */
    fun unaddressedOrQuestion(enabled: Boolean, questions: Boolean, connected: Boolean,
                             text: String): CarCommand? {
        // No link, no nameless anything: the car opt-ins are about the car, not the phone.
        if (!connected || text.length > 300) return null
        unaddressedCommand(enabled, connected, text)?.let { return it }
        if (!questions) return null
        return CarCommand.parse(text)?.takeIf { it == CarCommand.NavigationState }
    }
}
