package com.phoneassistant

/**
 * The wording of every navigation answer, kept free of Android types so the host tests can
 * check that nothing claims a stop that was not seen.
 */
internal object NavVoice {
    enum class Action {
        STATUS,
        STOPPED_AND_VERIFIED,
        STOPPED_UNVERIFIED,
        NOT_RUNNING,
        NO_MAPS,
        NO_HANDLES
    }

    fun message(action: Action, navigation: Boolean?, canInspect: Boolean): String = when (action) {
        Action.STATUS -> when {
            navigation == true -> "Yes, navigation is still running."
            navigation == false -> "You’re not navigating anywhere right now."
            canInspect -> "I checked Maps and didn’t see an active navigation notification."
            else -> "I can’t see Google Maps’ notification from here, so I can’t tell. I can still stop navigation if you ask."
        }
        Action.STOPPED_AND_VERIFIED -> "Stopped navigation."
        Action.STOPPED_UNVERIFIED -> "I sent a stop to Google Maps, but its navigation notification is still there. " +
            "Maps may be waiting for a tap on the phone."
        Action.NOT_RUNNING -> "You’re not navigating anywhere, so there’s nothing to stop."
        Action.NO_MAPS -> "Google Maps isn’t installed, so there’s no navigation to stop."
        Action.NO_HANDLES -> "I couldn’t find a way to stop Google Maps’ navigation from here. " +
            "Turn on Pip’s Notification access, or end the route on the phone."
    }
}
