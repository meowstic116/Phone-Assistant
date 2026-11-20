package com.phoneassistant

internal enum class KokoroFailure(val message: String, val pausesAutomatic: Boolean = false) {
    STOPPED("The Kokoro voice engine stopped. Pip can still reply in text. Try Preview to retry the voice.", true),
    TIMEOUT("Kokoro took too long. Pip can still reply in text. Try Preview to retry the voice.", true),
    BINDING("Pip couldn’t start the Kokoro voice engine. Try Preview again.", true),
    NO_AUDIO("Kokoro didn’t produce audio for this reply.")
}

/** A failed worker must not be relaunched on every chat reply. A successful preview resets it. */
internal class KokoroHealth {
    var lastProblem: KokoroFailure? = null
        private set
    var automaticAllowed = true
        private set

    fun completed(failure: KokoroFailure?) {
        if (failure == null) {
            lastProblem = null
            automaticAllowed = true
        } else if (automaticAllowed || failure.pausesAutomatic) {
            lastProblem = failure
            if (failure.pausesAutomatic) automaticAllowed = false
        }
    }
}
