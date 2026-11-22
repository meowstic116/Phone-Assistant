package com.phoneassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Phone-side handoff only; does not run on the car display or try to dismiss keyguard. */
internal object DrivingVoice {
    private const val AUTHORITY = "androidx.car.app.connection"
    private const val HOST = "com.google.android.projection.gearhead"
    private const val CONNECTION_COLUMN = "CarConnectionState"
    private const val TYPE_NOT_CONNECTED = 0
    private const val TYPE_NATIVE = 1
    private const val TYPE_PROJECTION = 2
    /**
     * [handedOff] means Android accepted the action. [verified] means the media session then
     * reported it happened — without that, "I sent it" is a guess, not a result.
     */
    data class Result(val message: String, val handedOff: Boolean = false, val verified: Boolean = false,
                      /** A screen Pip opened on purpose (Settings), so he should say what to do there. */
                      val prompted: Boolean = false)
    private val projectionUri = Uri.parse("content://$AUTHORITY")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var queryInProgress = false

    /** Query the AndroidX CarConnection provider contract, not Bluetooth or an untrusted broadcast. */
    fun projected(context: Context, done: (Boolean?) -> Unit) {
        if (queryInProgress) { done(null); return }
        val ctx = context.applicationContext
        val signal = CancellationSignal()
        queryInProgress = true
        var delivered = false
        val deadline = Runnable {
            delivered = true
            // The provider may be unresponsive. Cancel outside the UI thread.
            Thread { runCatching { signal.cancel() } }.start()
            done(null)
        }
        main.postDelayed(deadline, 2_500L)
        worker.execute {
            val result = runCatching {
                val provider = ctx.packageManager.resolveContentProvider(AUTHORITY, 0)
                if (provider?.packageName != HOST) null else ctx.contentResolver.query(projectionUri,
                    arrayOf(CONNECTION_COLUMN), null, null, null, signal)?.use { row ->
                    val index = row.getColumnIndex(CONNECTION_COLUMN)
                    if (index < 0 || !row.moveToFirst() || row.isNull(index)) null else when (row.getInt(index)) {
                        TYPE_PROJECTION -> true
                        TYPE_NOT_CONNECTED, TYPE_NATIVE -> false
                        else -> null
                    }
                }
            }.getOrNull()
            main.post {
                queryInProgress = false
                main.removeCallbacks(deadline)
                if (!delivered) done(result)
            }
        }
    }

    fun execute(ctx: Context, command: CarCommand, done: (Result) -> Unit) = when (command) {
        is CarCommand.Media -> PipMediaAccess.controlSpotify(ctx, command, done)
        is CarCommand.Navigate -> done(navigate(ctx, command))
        CarCommand.StopNavigation -> CarNavigation.stop(ctx) { action ->
            done(Result(NavVoice.message(action, null, canInspect = true),
                handedOff = true, verified = action == NavVoice.Action.STOPPED_AND_VERIFIED))
        }
        CarCommand.NavigationState -> CarNavigation.status { navigation ->
            val canInspect = PipMediaAccess.enabled(ctx)
            done(Result(NavVoice.message(NavVoice.Action.STATUS, navigation, canInspect)))
        }
    }

    private fun navigate(ctx: Context, command: CarCommand.Navigate): Result {
        val destination = if (command.destination.equals("home", ignoreCase = true)) {
            VoiceSettings.drivingHome(ctx)
        } else command.destination
        if (destination.isBlank()) return Result("Set a home destination in Pip settings when you're parked.")
        // Location first: Maps would otherwise open on a blank map and blame itself for a
        // setting Pip can name. Android lets no app flip the toggle directly, so a phone that
        // is in someone's hand gets the system screen; a locked one gets the plain truth.
        if (!CarNavigation.locationEnabled(ctx) && !CarNavigation.pipMayReadLocation(ctx)) {
            return if (CarNavigation.promptLocation(ctx)) {
                Result("Location is off on your phone. I opened Android’s Location setting — turn it on " +
                    "and ask me again to navigate.", handedOff = true, prompted = true)
            } else Result("Location is off on your phone, and I can’t turn it on myself while the phone is " +
                "locked. Turn on Location when you’re parked, then ask me again.")
        }
        // Mobile Maps intent; the OS and Maps decide whether to accept it and project the route.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(intent) }.isSuccess) {
            return Result("I sent the destination to Google Maps.", handedOff = true)
        }
        // "Couldn't hand off" is usually Android refusing the launch, not Maps refusing the route.
        return if (CarNavigation.promptAppSettings(ctx)) {
            Result("Android blocked Google Maps from opening. I opened Pip’s app settings — allow " +
                "“Display over other apps”, then ask me again.", handedOff = true, prompted = true)
        } else Result("Android blocked Google Maps from opening. Allow Pip to display over other apps " +
            "when you’re parked, then try again.")
    }
}
