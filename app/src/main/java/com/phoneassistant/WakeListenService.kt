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

/** Explicit microphone session, either tapped directly or opted in when starting Pip from the app. */
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
            listener.publish(WakeFeedback.OPENING_MIC)
            return true
        }
        var running = false
            private set
        fun hasMicPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun startProblem(ctx: Context): String? = when {
            !KokoroVoice.supported() -> "Offline listening is unavailable on this device."
            !WakeModel.ready(ctx) -> "Download offline recognition in Pip settings → Talk to Pip."
            !hasMicPermission(ctx) -> "Grant microphone permission in Pip settings → Talk to Pip."
            else -> null
        }

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
        if (!OverlayService.running || OverlayService.stopping || startProblem(this) != null) {
            failed(WakeFeedback.START_FAILED)
            return START_NOT_STICKY
        }
        val accepted = runCatching {
            // A foreground service must supply a notification even when Android hides it.
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notification(WakeFeedback.STARTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTICE, notification(WakeFeedback.STARTING))
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneAssistant:WakeListening").apply {
                setReferenceCounted(false)
                acquire() // Explicit session; stoppable from settings and released on every exit.
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
        if (status.isMicEvent) Diag.wakeInput(status)
        if (status.isOutcome) Diag.wakeOutcome(status)
        val now = SystemClock.elapsedRealtime()
        if ((status == WakeFeedback.LISTENING || status == WakeFeedback.PLAYING) && now < outcomeUntil) return
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
                    fun openRecorder() = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 32_000)).also { recorder ->
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            recorder.release()
                            error("Microphone did not initialize")
                        }
                    }
                    var mic = openRecorder()
                    // Held for this capture session only; released in the finally below.
                    var sco: CarAudio.Sco? = null
                    try {
                        val samples = ShortArray(640)
                        val floats = FloatArray(samples.size)
                        val phrase = WakePhrase()
                        var recording = false
                        var filled = 0
                        var handledManual = 0
                        var lastSignal = 0L
                        var lastPcmAt = 0L
                        val recovery = WakeCaptureRecovery()
                        var nextHypothesis = 0L
                        var nextInputCheck = 0L
                        var showedName = false
                        var inputProblem: WakeFeedback? = null
                        // Checked once per segment: the car link changes slowly, and this must
                        // never run a Bluetooth binder call on the audio path.
                        var carCommandReady = false
                        // Car microphone: requested once, off the audio thread, and abandoned
                        // for the phone mic if the car route never supplies audio.
                        var triedCarMic = false
                        var carMicPending = false
                        // True once capture has been restarted onto the car route, and true
                        // again only when real audio has arrived from it.
                        var carMicLive = false
                        var carMicHeard = false
                        fun locked() = runCatching {
                            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
                        }.getOrDefault(false)
                        fun androidMuted() = Build.VERSION.SDK_INT >= 29 &&
                            runCatching { mic.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
                        fun restartCapture() {
                            update(WakeFeedback.MIC_RESTARTING)
                            if (!active) return
                            mic.release()
                            mic = openRecorder()
                            recording = false
                            filled = 0
                            phrase.reset()
                            showedName = false
                            recognizer.reset(stream)
                        }
                        fun markNoSignal(muted: Boolean) {
                            val problem = if (muted) WakeFeedback.MIC_MUTED else WakeFeedback.MIC_SILENT
                            if (inputProblem == problem) return
                            // Never combine speech buffered before input loss with a new utterance.
                            if (inputProblem == null) {
                                filled = 0
                                phrase.reset()
                                showedName = false
                                recognizer.reset(stream)
                            }
                            inputProblem = problem
                            update(problem)
                        }
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
                            // The car route just came up: restart capture onto it, discarding
                            // whatever the phone microphone had buffered before the switch.
                            if (sco != null && !carMicLive) {
                                carMicLive = true
                                carMicHeard = false
                                if (recording) mic.stop()
                                recording = false
                                filled = 0
                                phrase.reset()
                                showedName = false
                                recognizer.reset(stream)
                                Diag.wakeInput(WakeFeedback.CAR_MIC)
                                update(WakeFeedback.CAR_MIC)
                                continue
                            }
                            if (!recording) {
                                // A route change can take a moment to settle; retry instead of
                                // failing the whole session on the first attempt.
                                if (!runCatching {
                                        mic.startRecording()
                                        mic.recordingState == AudioRecord.RECORDSTATE_RECORDING
                                    }.getOrDefault(false)) {
                                    Thread.sleep(40L)
                                    continue
                                }
                                recording = true
                                inputProblem = null
                                lastSignal = now
                                lastPcmAt = now
                                nextHypothesis = now
                                nextInputCheck = now
                                main.removeCallbacks(startupDeadline)
                                update(if (phrase.awaiting) WakeFeedback.SPEAK_NOW else WakeFeedback.LISTENING)
                                // Only when the user opted into driving commands, and only
                                // when a hands-free device is actually present.
                                // Only on a locked phone: an unlocked listening session is the
                                // user at the phone, where taking over call audio would not help.
                                if (!triedCarMic && !carMicPending && VoiceSettings.drivingEnabled(this) &&
                                    CarAudio.connected(this) && CarAudio.canUseHeadset(this) && locked()) {
                                    triedCarMic = true
                                    carMicPending = true
                                    val session = CarAudio.Sco(this)
                                    Thread({
                                        val connected = session.start()
                                        main.post {
                                            carMicPending = false
                                            if (connected && active) sco = session else session.stop()
                                        }
                                    }, "PipCarMic").start()
                                }
                            }
                            if (phrase.expire(now)) {
                                recognizer.reset(stream)
                                showedName = false
                                update(WakeFeedback.NO_MESSAGE)
                            }
                            val n = mic.read(samples, filled, samples.size - filled, AudioRecord.READ_NON_BLOCKING)
                            if (n == AudioRecord.ERROR_DEAD_OBJECT && recovery.tryRestart(now)) {
                                restartCapture()
                                carCommandReady = false
                                continue
                            }
                            check(n >= 0)
                            if (n == 0) {
                                if (now >= nextInputCheck && recovery.stalled(now, lastPcmAt)) {
                                    nextInputCheck = now + 1_000L
                                    val muted = androidMuted()
                                    if (!muted && recovery.tryRestart(now)) {
                                        restartCapture()
                                        continue
                                    }
                                    markNoSignal(muted)
                                }
                                Thread.sleep(15L)
                                continue
                            }
                            lastPcmAt = now
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
                                if (carMicLive) carMicHeard = true
                                if (inputProblem != null) {
                                    inputProblem = null
                                    update(if (phrase.awaiting) WakeFeedback.SPEAK_NOW else WakeFeedback.LISTENING)
                                }
                            } else if (recovery.stalled(now, lastSignal)) {
                                if (now >= nextInputCheck) {
                                    nextInputCheck = now + 1_000L
                                    // A car route that reports connected but carries silence is
                                    // worse than the phone mic, so leave it and keep listening.
                                    if (sco != null && carMicLive && !carMicHeard) {
                                        sco?.stop()
                                        sco = null
                                        carMicLive = false
                                        restartCapture()
                                        continue
                                    }
                                    markNoSignal(androidMuted())
                                }
                                continue
                            }
                            stream.acceptWaveform(floats, RATE)
                            var decoded = false
                            while (active && recognizer.isReady(stream)) {
                                recognizer.decode(stream)
                                decoded = true
                            }
                            if (!active) break
                            if (recognizer.isEndpoint(stream)) {
                                val heard = recognizer.getResult(stream).text
                                recognizer.reset(stream)
                                val message = phrase.accept(heard, now)
                                showedName = phrase.awaiting
                                if (message != null) {
                                    dispatch(message)
                                } else {
                                    // In a car, his name is not required for an exact driving
                                    // command when the user opted in and a hands-free link exists.
                                    if (!carCommandReady) carCommandReady = CarAudio.connected(this)
                                    val unaddressed = CarVoice.unaddressedOrQuestion(
                                        VoiceSettings.drivingEnabled(this) && VoiceSettings.carNoName(this),
                                        VoiceSettings.carQuestions(this),
                                        carCommandReady, heard)
                                    // Clear any half-heard name so it cannot leak into a later
                                    // utterance, then run only the bounded command set.
                                    if (unaddressed != null) phrase.reset()
                                    val carCommand = if (unaddressed != null) phrase.acceptUnaddressed(heard) else null
                                    if (carCommand != null) dispatch(carCommand)
                                    else if (phrase.awaiting && heard.isNotBlank()) update(WakeFeedback.NAME_HEARD)
                                    else if (heard.isNotBlank()) update(WakeFeedback.NO_NAME)
                                }
                            } else if (decoded && now >= nextHypothesis) {
                                nextHypothesis = now + 180L
                                val addressed = phrase.observePartial(recognizer.getResult(stream).text)
                                if (addressed && !showedName) {
                                    showedName = true
                                    update(WakeFeedback.NAME_HEARD)
                                }
                            }
                        }
                    } finally {
                        sco?.stop()
                        mic.release()
                    }
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
