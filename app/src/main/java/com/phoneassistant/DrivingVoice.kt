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
    private const val TYPE_PROJECTION = 2
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
                if (provider?.packageName != HOST) false else ctx.contentResolver.query(projectionUri,
                    arrayOf(CONNECTION_COLUMN), null, null, null, signal)?.use { row ->
                    val index = row.getColumnIndex(CONNECTION_COLUMN)
                    index >= 0 && row.moveToFirst() && row.getInt(index) == TYPE_PROJECTION
                }
            }.getOrNull()
            main.post {
                queryInProgress = false
                main.removeCallbacks(deadline)
                if (!delivered) done(result)
            }
        }
    }

    fun execute(ctx: Context, command: CarCommand): String = when (command) {
        is CarCommand.Spotify -> PipMediaAccess.controlSpotify(ctx, command)
        is CarCommand.Navigate -> {
            val destination = if (command.destination.equals("home", ignoreCase = true)) {
                VoiceSettings.drivingHome(ctx)
            } else command.destination
            if (destination.isBlank()) "Set a home destination in Pip settings when you're parked."
            else {
                // Mobile Maps intent; the OS and Maps decide whether to accept it and project the route.
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=d"))
                    .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { ctx.startActivity(intent) }.isSuccess) {
                    "I sent the destination to Google Maps."
                } else "I couldn’t hand off those directions. Try again when parked."
            }
        }
    }
}
