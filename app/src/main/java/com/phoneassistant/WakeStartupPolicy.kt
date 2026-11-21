package com.phoneassistant

/** Only an explicit foreground start can use the remembered microphone opt-in. */
internal object WakeStartupPolicy {
    fun shouldStart(fromOpenApp: Boolean, optedIn: Boolean, pipReady: Boolean): Boolean =
        fromOpenApp && optedIn && pipReady
}
