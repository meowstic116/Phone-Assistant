package com.phoneassistant

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Darkens the screen on request.
 *
 * Two routes, and the app's Android baseline decides which one it can use:
 *
 * - **Android 9 and newer:** the accessibility service's own `GLOBAL_ACTION_LOCK_SCREEN`. This
 *   is the sanctioned way to do it, needs no device-admin grant, and leaves fingerprint and face
 *   unlock working — a device-admin lock resets biometric unlock, which would make "lock my
 *   screen" mean "and also type your PIN".
 * - **Android 8:** there is no global action, so the only dead-simple route is to open Quick
 *   Settings and tap a tile whose own label is a lock. Nothing is tapped unless such a label was
 *   actually read, so the failure mode is an honest "I can't from here" rather than a tap on
 *   something else entirely.
 *
 * What it never does: disable the keyguard, change a timeout, fake a dark window, or ask for
 * device-admin rights.
 */
object ScreenLocker {
    enum class Result {
        /** The lock was performed, and Android's own keyguard reports the screen is locked. */
        LOCKED,

        /** Android accepted the action; the keyguard had not caught up when it was checked. */
        ACCEPTED,

        /** Accessibility is on, but this Android version has no lock action (8 and below). */
        NEEDS_UNLOCK_TO_LOCK,

        /** No screen to lock: the phone is already locked or asleep. */
        ALREADY_LOCKED,

        /**
         * Android 8 and below: the screen is lit but no lock control could be read or it did
         * nothing. Distinct from [NEEDS_UNLOCK_TO_LOCK] so a failed tap is not reported as a lock.
         */
        TILE_NOT_FOUND,

        /** The accessibility switch is off, or Android never connected the service. */
        NO_ACCESS
    }

    /**
     * What a lock costs, in words the settings screen can show before anyone tries it. The
     * Android 8 sentence is left out on newer phones rather than describing something that
     * cannot happen to them.
     */
    fun explanation(): String {
        val base = "Locking uses the accessibility service Pip already needs to work other apps. "
        if (!directLockSupported()) {
            return base + "This version of Android has no direct lock, so the phone is locked " +
                "by opening Quick Settings and tapping the lock tile. That needs the screen " +
                "awake. Nothing is tapped unless a lock control is actually on the screen."
        }
        val direct = "Android locks through it directly, with no device-admin permission, and " +
            "fingerprint or face unlock still works."
        if (available()) return base + direct
        return base + direct + "\n\nThe accessibility switch above is off, so this won’t " +
            "work until it is on."
    }

    fun available(): Boolean = Screen.available()

    /** Whether this Android version can be locked without an unlock first. */
    fun directLockSupported(): Boolean = Build.VERSION.SDK_INT >= 28

    /**
     * Whether locking can succeed at all. On Android 9+ the system action always exists. Below
     * it nothing can be promised up front — a lock tile has to be found on the shade at the
     * moment of asking — so this reports what is known rather than pretending to know.
     */
    fun supported(): Boolean = directLockSupported()

    /**
     * Whether a lock would do anything right now, as a result rather than a boolean: "already
     * locked" and "can't lock on this phone" are different answers and must not be conflated.
     */
    fun readiness(ctx: Context): Result? = when {
        !available() -> Result.NO_ACCESS
        isLocked(ctx) -> Result.ALREADY_LOCKED
        else -> null
    }

    /**
     * Locks the screen. Must be called off the main thread: the fallback dispatches a gesture
     * and waits for it, and Android refuses gestures from the thread that owns the view tree.
     */
    fun lock(): Result {
        // Settings can offer the button before the overlay has ever run, which is the one case
        // where nothing has attached a context yet.
        val ctx = appContext ?: return fail(Result.NO_ACCESS, "no context: the overlay has not run")
        if (!available()) return fail(Result.NO_ACCESS, "accessibility service not connected")
        if (isLocked(ctx)) return report(Result.ALREADY_LOCKED, "screen was already locked")
        if (directLockSupported()) {
            if (!Screen.lockScreen()) return fail(Result.NO_ACCESS, "Android refused the lock action")
            return confirm(ctx, Result.LOCKED)
        }
        return when (quickSettingsLock()) {
            true -> confirm(ctx, Result.LOCKED)
            // A tap landed but nothing locked: the control was not a lock after all.
            false -> fail(Result.TILE_NOT_FOUND, "tapped a lock control that did nothing")
            null -> fail(Result.TILE_NOT_FOUND, "no lock control on the screen")
        }
    }

    /**
     * What to say about a result. Success is announced before the lock lands, by the caller,
     * because after it lands there is no screen left for a bubble.
     */
    fun message(result: Result): String = when (result) {
        Result.LOCKED -> "Locking up."
        Result.ACCEPTED -> "Locking up."
        Result.ALREADY_LOCKED -> "The screen's already asleep."
        Result.NEEDS_UNLOCK_TO_LOCK ->
            "I can lock it once the screen is awake — this phone locks through Quick Settings, and I can't pull it down from here."
        Result.NO_ACCESS ->
            "I need the accessibility switch on before I can lock your screen. It's in Pip settings."
        Result.TILE_NOT_FOUND ->
            "I couldn’t find a lock button on this phone’s Quick Settings, so I didn’t tap anything."
    }

    // ------------------------------------------------------------------ Android 8 fallback

    /**
     * Swipe the shade down, find a control whose label is a lock, tap it. Returns null when no
     * such control could be read, which is the common honest outcome on a themed ROM.
     */
    private fun quickSettingsLock(): Boolean? {
        if (!Screen.notifications()) return null
        // The shade needs a moment before it has a list to read. Four reads over about 400 ms is
        // enough for a cold shade and short enough that a failure is not a long silence.
        val item = (0 until 4).firstNotNullOfOrNull {
            if (it > 0) runCatching { Thread.sleep(120L) }
            Screen.findLockControl()
        }
        if (item == null) {
            Screen.back()
            return null
        }
        return Screen.tap(item)
    }

    // ------------------------------------------------------------------ shared

    /**
     * The application context, held so the keyguard can be read. Application context, never the
     * service: a static holding a Service outlives it and leaks everything the service owns.
     */
    @Volatile
    private var appContext: Context? = null

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    private fun isLocked(ctx: Context): Boolean = runCatching {
        val power = ctx.getSystemService(PowerManager::class.java)
        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }.getOrDefault(false)

    /**
     * Android applies the lock asynchronously, and a screen that is still lit a moment later has
     * not failed — it has not finished. One short re-check separates the two.
     */
    private fun confirm(ctx: Context, success: Result): Result {
        val settled = SystemClock.uptimeMillis() + 350L
        while (SystemClock.uptimeMillis() < settled) {
            if (isLocked(ctx)) return report(success, "keyguard confirmed")
            runCatching { Thread.sleep(60L) }
        }
        return report(Result.ACCEPTED, "action accepted, keyguard not yet confirmed")
    }

    private fun fail(result: Result, why: String): Result = report(result, why)

    private fun report(result: Result, why: String): Result {
        Diag.screenLock("${result.name.lowercase().replace('_', ' ')} · $why")
        return result
    }
}
