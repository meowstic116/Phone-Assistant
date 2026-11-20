package com.phoneassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast

/** User-started microphone session. Never started by boot, a restart, or a chat reply. */
internal class WakeListenService : Service() {
    companion object {
        private const val STOP = "com.phoneassistant.STOP_WAKE_LISTENING"
        private const val CHANNEL = "pip_wake_listening"
        private const val NOTICE = 408
        private const val RATE = 16_000
        var onChanged: (() -> Unit)? = null
        var running = false
            private set
        fun start(ctx: Context) = runCatching {
            ctx.startForegroundService(Intent(ctx, WakeListenService::class.java)) != null
        }.getOrDefault(false)
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeListenService::class.java)) }
    }

    @Volatile private var active = false
    @Volatile private var commandPending = false
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !OverlayService.running || !WakeModel.ready(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notification("Starting offline listening…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTICE, notification("Starting offline listening…"))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneAssistant:WakeListening").apply {
                setReferenceCounted(false)
                acquire() // Explicitly enabled session with a persistent Stop action; released on every exit.
            }
        }.isSuccess
        if (!accepted) { failed("Android couldn’t start listening. Try again from Pip settings."); return START_NOT_STICKY }
        active = true
        running = true
        onChanged?.invoke()
        Thread(::runListener, "PipVoiceInput").start()
        return START_NOT_STICKY
    }

    private fun notification(status: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pip microphone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, NOTICE, Intent(this, PipSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTICE, Intent(this, WakeListenService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pip)
            .setContentTitle("Pip is listening").setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pip),
                "Stop listening", stop).build()).build()
    }

    private fun update(message: String) {
        main.post { if (active) getSystemService(NotificationManager::class.java).notify(NOTICE, notification(message)) }
    }

    private fun failed(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    @SuppressLint("MissingPermission") // Permission checked before starting this foreground session.
    private fun runListener() {
        try {
            val recognizer = WakeModel.recognizer(this)
            try {
                val stream = recognizer.createStream()
                try {
                    if (!active) return
                    val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(min > 0)
                    val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
                    try {
                        check(mic.state == AudioRecord.STATE_INITIALIZED)
                        val samples = ShortArray(640)
                        val floats = FloatArray(samples.size)
                        val phrase = WakePhrase()
                        var recording = false
                        var filled = 0
                        val idle = "Say ‘Pip’, then ask a question."
                        while (active) {
                            if (commandPending || PipVoice.microphoneBlocked()) {
                                if (recording) {
                                    mic.stop()
                                    recording = false
                                    filled = 0
                                    phrase.reset()
                                    recognizer.reset(stream)
                                    update("Paused while Pip replies.")
                                }
                                Thread.sleep(40L)
                                continue
                            }
                            if (!recording) {
                                mic.startRecording()
                                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                                recording = true
                                update(idle)
                            }
                            val n = mic.read(samples, filled, samples.size - filled, AudioRecord.READ_NON_BLOCKING)
                            check(n >= 0)
                            if (n == 0) { Thread.sleep(15L); continue }
                            filled += n
                            if (filled < samples.size) continue
                            filled = 0
                            for (i in samples.indices) floats[i] = samples[i] / 32768f
                            stream.acceptWaveform(floats, RATE)
                            while (active && recognizer.isReady(stream)) recognizer.decode(stream)
                            val now = SystemClock.elapsedRealtime()
                            if (phrase.expire(now)) {
                                recognizer.reset(stream)
                                update(idle)
                            }
                            if (!recognizer.isEndpoint(stream)) continue
                            val heard = recognizer.getResult(stream).text
                            recognizer.reset(stream)
                            val text = phrase.accept(heard, now)
                            if (text != null) {
                                commandPending = true
                                main.post {
                                    if (!active) commandPending = false
                                    else OverlayService.heardVoice(text, { active }) { commandPending = false }
                                }
                            } else if (phrase.awaiting) update("Name heard. Speak now.")
                        }
                    } finally { mic.release() }
                } finally { stream.release() }
            } finally { recognizer.release() }
        } catch (_: Exception) {
            main.post { if (active) failed("Pip couldn’t use the microphone or offline model. Try again in Pip settings.") }
        } catch (_: LinkageError) {
            main.post { if (active) failed("The offline speech engine couldn’t start on this device.") }
        }
    }

    override fun onDestroy() {
        active = false
        running = false
        onChanged?.invoke()
        PipVoice.stopHandsFree()
        OverlayService.discardVoiceDraft()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
