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
        private var instance: WakeListenService? = null
        var onChanged: (() -> Unit)? = null
        var feedback = WakeFeedback.OFF
            private set

        fun speakNow(): Boolean {
            val listener = instance ?: return false
            if (!listener.active || listener.commandPending || !feedback.canSpeak || PipVoice.microphoneBlocked()) return false
            listener.manualRequest += 1
            listener.publish(WakeFeedback.SPEAK_NOW)
            return true
        }
        var running = false
            private set
        fun start(ctx: Context) = runCatching {
            ctx.startForegroundService(Intent(ctx, WakeListenService::class.java)) != null
        }.getOrDefault(false)
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeListenService::class.java)) }
    }

    @Volatile private var active = false
    @Volatile private var commandPending = false
    @Volatile private var manualRequest = 0
    private var outcomeUntil = 0L
    private var keepFailure = false
    private val startupDeadline = Runnable { if (active) failed(
        if (feedback == WakeFeedback.OPENING_MIC) WakeFeedback.MIC_FAILED else WakeFeedback.ENGINE_FAILED) }
    private val resetOutcome = Runnable {
        if (active && feedback.isOutcome) publish(if (PipVoice.microphoneBlocked()) WakeFeedback.PLAYING else WakeFeedback.LISTENING)
    }
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        if (!OverlayService.running || !WakeModel.ready(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failed(WakeFeedback.START_FAILED)
            return START_NOT_STICKY
        }
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notification(WakeFeedback.STARTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTICE, notification(WakeFeedback.STARTING))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneAssistant:WakeListening").apply {
                setReferenceCounted(false)
                acquire() // Explicitly enabled session with a persistent Stop action; released on every exit.
            }
        }.isSuccess
        if (!accepted) { failed(WakeFeedback.START_FAILED); return START_NOT_STICKY }
        active = true
        running = true
        instance = this
        publish(WakeFeedback.STARTING)
        main.postDelayed(startupDeadline, 20_000L)
        Thread(::runListener, "PipVoiceInput").start()
        return START_NOT_STICKY
    }

    private fun notification(status: WakeFeedback): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pip microphone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, NOTICE, Intent(this, PipSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTICE, Intent(this, WakeListenService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pip)
            .setContentTitle("Talk to Pip").setContentText(status.message)
            .setStyle(Notification.BigTextStyle().bigText(status.message)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pip),
                "Stop listening", stop).build()).build()
    }

    private fun publish(status: WakeFeedback) {
        val now = SystemClock.elapsedRealtime()
        if (status in listOf(WakeFeedback.LISTENING, WakeFeedback.PLAYING) && now < outcomeUntil) return
        main.removeCallbacks(resetOutcome)
        if (status.isOutcome) {
            outcomeUntil = now + 7_000L
            main.postDelayed(resetOutcome, 7_100L)
        } else outcomeUntil = 0L
        if (feedback == status && status != WakeFeedback.SPEAK_NOW) return
        feedback = status
        onChanged?.invoke()
        if (active) runCatching { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(status)) }
    }

    private fun update(status: WakeFeedback) {
        main.post { if (active) publish(status) }
    }

    private fun failed(status: WakeFeedback) {
        keepFailure = true
        active = false
        publish(status)
        Toast.makeText(this, status.message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun dispatch(text: String) {
        commandPending = true
        main.post {
            if (!active) { commandPending = false; return@post }
            publish(WakeFeedback.SENDING)
            val done: (WakeFeedback) -> Unit = { outcome ->
                commandPending = false
                if (active) publish(outcome)
            }
            ChatRecovery.attempt(Unit, { done(WakeFeedback.CHAT_FAILED) }) {
                OverlayService.heardVoice(text, { active }, done)
            }
        }
    }

    @SuppressLint("MissingPermission") // Permission checked before starting this foreground session.
    private fun runListener() {
        var failure = WakeFeedback.ENGINE_FAILED
        try {
            val recognizer = WakeModel.recognizer(this)
            try {
                val stream = recognizer.createStream()
                try {
                    if (!active) return
                    failure = WakeFeedback.MIC_FAILED
                    update(WakeFeedback.OPENING_MIC)
                    val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(min > 0)
                    val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 32_000))
                    try {
                        check(mic.state == AudioRecord.STATE_INITIALIZED)
                        val samples = ShortArray(640)
                        val floats = FloatArray(samples.size)
                        val phrase = WakePhrase()
                        var recording = false
                        var filled = 0
                        var handledManual = 0
                        var lastSignal = 0L
                        var nextHypothesis = 0L
                        var showedName = false
                        var silent = false
                        while (active) {
                            val now = SystemClock.elapsedRealtime()
                            if (commandPending || PipVoice.microphoneBlocked()) {
                                if (recording) {
                                    mic.stop()
                                    recording = false
                                    filled = 0
                                    phrase.reset()
                                    showedName = false
                                    recognizer.reset(stream)
                                    if (!commandPending) update(WakeFeedback.PLAYING)
                                }
                                Thread.sleep(40L)
                                continue
                            }
                            val manual = manualRequest
                            if (handledManual != manual) {
                                handledManual = manual
                                if (recording) mic.stop() // Discard audio buffered before the explicit tap.
                                recording = false
                                filled = 0
                                recognizer.reset(stream)
                                phrase.arm(now)
                                showedName = true
                            }
                            if (!recording) {
                                mic.startRecording()
                                check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                                recording = true
                                silent = false
                                lastSignal = now
                                main.removeCallbacks(startupDeadline)
                                update(if (phrase.awaiting) WakeFeedback.SPEAK_NOW else WakeFeedback.LISTENING)
                            }
                            if (phrase.expire(now)) {
                                recognizer.reset(stream)
                                showedName = false
                                update(WakeFeedback.NO_MESSAGE)
                            }
                            val n = mic.read(samples, filled, samples.size - filled, AudioRecord.READ_NON_BLOCKING)
                            check(n >= 0)
                            if (n == 0) {
                                if (!silent && now - lastSignal >= 4_000L) {
                                    silent = true
                                    update(WakeFeedback.MIC_SILENT)
                                }
                                Thread.sleep(15L)
                                continue
                            }
                            filled += n
                            if (filled < samples.size) continue
                            filled = 0
                            var hasSignal = false
                            for (i in samples.indices) {
                                val sample = samples[i].toInt()
                                if (sample != 0) hasSignal = true
                                floats[i] = sample / 32768f
                            }
                            if (hasSignal) {
                                lastSignal = now
                                if (silent) {
                                    silent = false
                                    update(if (phrase.awaiting) WakeFeedback.SPEAK_NOW else WakeFeedback.LISTENING)
                                }
                            } else if (!silent && now - lastSignal >= 4_000L) {
                                silent = true
                                update(WakeFeedback.MIC_SILENT)
                            }
                            stream.acceptWaveform(floats, RATE)
                            while (active && recognizer.isReady(stream)) recognizer.decode(stream)
                            if (!active) break
                            if (recognizer.isEndpoint(stream)) {
                                val heard = recognizer.getResult(stream).text
                                recognizer.reset(stream)
                                val message = phrase.accept(heard, now)
                                showedName = phrase.awaiting
                                if (message != null) dispatch(message)
                                else if (!silent && phrase.awaiting && heard.isNotBlank()) update(WakeFeedback.NAME_HEARD)
                                else if (!silent && heard.isNotBlank()) update(WakeFeedback.NO_NAME)
                            } else if (!showedName && now >= nextHypothesis) {
                                nextHypothesis = now + 300L
                                if (phrase.hasAddress(recognizer.getResult(stream).text)) {
                                    showedName = true
                                    update(WakeFeedback.NAME_HEARD)
                                }
                            }
                        }
                    } finally { mic.release() }
                } finally { stream.release() }
            } finally { recognizer.release() }
        } catch (_: Exception) {
            main.post { if (active) failed(failure) }
        } catch (_: LinkageError) {
            main.post { if (active) failed(WakeFeedback.ENGINE_FAILED) }
        }
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacks(startupDeadline)
        main.removeCallbacks(resetOutcome)
        if (instance === this) instance = null
        running = false
        if (!keepFailure) feedback = WakeFeedback.OFF
        onChanged?.invoke()
        PipVoice.stopHandsFree()
        OverlayService.discardVoiceDraft()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
