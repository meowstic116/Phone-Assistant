package com.phoneassistant

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification

/**
 * Maps has no public "stop" API, but it has two documented front doors: the ongoing
 * navigation notification carries its own Exit/Stop action, and the [geo.action] intent is the
 * one Gemini and Assistant use to end a route. Neither needs the screen unlocked, which is the
 * whole point — the old answer ("I can only do that when your phone is unlocked") came from
 * treating this as a phone task.
 *
 * The session is inferred from the notification only. Nothing here opens Maps or touches the
 * screen, so it works with the phone in a pocket.
 */
internal object CarNavigation {
    private const val MAPS = "com.google.android.apps.maps"
    private const val EXIT_ACTION = "exit_navigation"

    /** A route is only trusted from Maps' own ongoing navigation notification. */
    fun isNavigation(entry: StatusBarNotification): Boolean {
        if (entry.packageName != MAPS) return false
        return try {
            val notification = entry.notification
            val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.lowercase().orEmpty()
            // Ongoing, and either the navigation category or a title we recognize. An ordinary
            // "Maps" promo must not read as an active route.
            ongoing && (notification.category == Notification.CATEGORY_NAVIGATION ||
                listOf("navigation", "navigating", "directions", "route", "trip").any { it in title })
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Whether Pip itself may read location at all. Only used to diagnose Maps, never to track. */
    fun pipMayReadLocation(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun locationEnabled(ctx: Context): Boolean = runCatching {
        val manager = ctx.getSystemService(LocationManager::class.java) ?: return@runCatching true
        if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)

    /**
     * Android gives no app the right to flip location on. The closest it allows is the Settings
     * toggle itself, opened as a system screen, and only while the phone is in use — a locked
     * phone in a pocket must not flash a dialog nobody sees.
     */
    fun canPrompt(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(false)

    fun locationSettings(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appSettings(app: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", app, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun promptLocation(ctx: Context): Boolean = runCatching {
        canPrompt(ctx) && ctx.startActivity(locationSettings()).let { true }
    }.getOrDefault(false)

    fun promptAppSettings(ctx: Context): Boolean = runCatching {
        canPrompt(ctx) && ctx.startActivity(appSettings(ctx.packageName)).let { true }
    }.getOrDefault(false)

    /** The documented Assistant-style exit, used when the notification has no button to press. */
    fun exitIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo.action:?act=$EXIT_ACTION")).setPackage(MAPS)

    /** Notification reads and the send both happen off the main thread. */
    fun stop(ctx: Context, done: (NavVoice.Action) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val action = runCatching { stopNow(app) }.getOrDefault(NavVoice.Action.NO_HANDLES)
            Handler(Looper.getMainLooper()).post { done(action) }
        }.start()
    }

    fun status(ctx: Context, done: (Boolean?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val navigation = runCatching { navigating(app) }.getOrNull()
            Handler(Looper.getMainLooper()).post { done(navigation) }
        }.start()
    }

    private fun navigating(ctx: Context): Boolean? {
        val active = PipMediaAccess.snapshot() ?: return null
        return active.any { isNavigation(it) }
    }

    private fun stopNow(ctx: Context): NavVoice.Action {
        val active = PipMediaAccess.snapshot()
        val session = active?.firstOrNull { isNavigation(it) }
        // No Notification access at all is different from "access, and no route running".
        if (active != null && session == null) return NavVoice.Action.NOT_RUNNING
        if (session == null && ctx.packageManager.getLaunchIntentForPackage(MAPS) == null) {
            return NavVoice.Action.NO_MAPS
        }
        val button = session?.let { exitAction(it) }
        val sent = when {
            button != null -> tap(button)
            else -> runCatching {
                ctx.startActivity(exitIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        if (!sent) return NavVoice.Action.NO_HANDLES
        // Some Maps versions take a moment to retire the notification after ending the route.
        val deadline = SystemClock.elapsedRealtime() + 2_500L
        while (SystemClock.elapsedRealtime() < deadline) {
            val still = PipMediaAccess.snapshot()?.any { isNavigation(it) }
                ?: return NavVoice.Action.STOPPED_UNVERIFIED
            if (!still) return NavVoice.Action.STOPPED_AND_VERIFIED
            SystemClock.sleep(150L)
        }
        // A tap on the wrong control must not be reported as a stop.
        return NavVoice.Action.STOPPED_UNVERIFIED
    }

    private fun exitAction(entry: StatusBarNotification): Notification.Action? = try {
        entry.notification.actions.orEmpty().firstOrNull { action ->
            val title = action.title?.toString()?.lowercase().orEmpty()
            listOf("exit", "stop", "end", "cancel", "close").any { it in title }
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun tap(action: Notification.Action): Boolean = runCatching {
        action.actionIntent?.send()
        action.actionIntent != null
    }.getOrDefault(false)

}
