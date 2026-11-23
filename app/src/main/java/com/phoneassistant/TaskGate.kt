package com.phoneassistant

/** When Pip may take the phone: never while the user is in the middle of something. */
internal object TaskGate {

    /**
     * A scheduled task was asked for a moment, so it may take over only when the phone is
     * open and the user is at the home screen doing nothing. Anything else waits.
     */
    fun mayRunScheduled(interactive: Boolean, unlocked: Boolean, atHome: Boolean, agentBusy: Boolean) =
        interactive && unlocked && atHome && !agentBusy

    /**
     * An automatic task belongs where its trigger lives, mid-app and all: the user asked
     * for exactly this interruption. The phone still has to be open and quiet.
     */
    fun mayRunAutomatic(interactive: Boolean, unlocked: Boolean, agentBusy: Boolean) =
        interactive && unlocked && !agentBusy
}
