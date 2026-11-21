package com.phoneassistant

/** Boot-only requests belong to the overlay instance, never to a restarted service. */
internal class WakeStartupPolicy {
    enum class Source { APP, BOOT, RECOVERY }

    private var waitingForApp = false
    var hasPendingAppLaunch = false
        private set

    fun start(source: Source, optedIn: Boolean, pipReady: Boolean, bootEnabled: Boolean,
              bootMicAllowed: Boolean, openAppAtBoot: Boolean = false): Boolean {
        if (source == Source.RECOVERY) return false
        hasPendingAppLaunch = source == Source.BOOT && pipReady && bootEnabled && openAppAtBoot
        val allowed = optedIn && pipReady && (source != Source.BOOT || bootEnabled)
        waitingForApp = allowed && source == Source.BOOT && !bootMicAllowed
        return allowed && !waitingForApp
    }

    fun appVisible(optedIn: Boolean, pipReady: Boolean, bootEnabled: Boolean): Boolean {
        hasPendingAppLaunch = false
        val start = waitingForApp && optedIn && pipReady && bootEnabled
        waitingForApp = false
        return start
    }

    /** Never consume a launch while the phone is still locked. Try once after unlock. */
    fun takeAppLaunch(unlocked: Boolean, enabled: Boolean): Boolean {
        if (!unlocked) return false
        val launch = hasPendingAppLaunch && enabled
        hasPendingAppLaunch = false
        return launch
    }
}
