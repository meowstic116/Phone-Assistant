package com.phoneassistant

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

/** Final replies only. Toy banter and agent progress stay silent, and private phone results stay local. */
internal object PipVoice {
    private val main = Handler(Looper.getMainLooper())
    private val attrs by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
    private var sequence = 0L
    private var allowLockedPlayback = false
    @Volatile private var speaking = false
    @Volatile private var keepMicMutedUntil = 0L
    fun microphoneBlocked() = speaking || SystemClock.elapsedRealtime() < keepMicMutedUntil
    fun stopHandsFree() { if (allowLockedPlayback) stop() }
    fun stopUnlessHandsFree() { if (!allowLockedPlayback) stop() }
    private var kokoroRequest: KokoroIpc.Request? = null
    private var player: KokoroPlayback? = null
    private val clipReader = Executors.newSingleThreadExecutor()
    private var engine: TextToSpeech? = null
    private var engineStatus: Int? = null
    private var localPending: ((Int) -> Unit)? = null
    private var focus: Pair<AudioManager, AudioFocusRequest>? = null
    private var captionUpdate: ((Int?) -> Unit)? = null
    private var lastCaptionEnd = 0
    private var captionClock: Runnable? = null
    private var speechDeadline: Runnable? = null
    private var privatePlaybackCleanup: (() -> Unit)? = null

    private fun clearClocks() {
        captionClock?.let(main::removeCallbacks)
        speechDeadline?.let(main::removeCallbacks)
        captionClock = null
        speechDeadline = null
    }

    private fun reveal(id: Long, end: Int) {
        if (id != sequence || end <= lastCaptionEnd) return
        lastCaptionEnd = end
        captionUpdate?.invoke(end)
    }

    private fun finishCaptions() {
        clearClocks()
        privatePlaybackCleanup?.invoke()
        privatePlaybackCleanup = null
        if (speaking) {
            keepMicMutedUntil = SystemClock.elapsedRealtime() + 400L
            speaking = false
        }
        allowLockedPlayback = false
        val report = captionUpdate
        captionUpdate = null
        report?.invoke(null)
    }

    private fun timeLimit(id: Long, delay: Long, onTimeout: () -> Unit = {}) {
        speechDeadline?.let(main::removeCallbacks)
        speechDeadline = Runnable { if (id == sequence) { stop(); onTimeout() } }.also { main.postDelayed(it, delay) }
    }

    private fun estimate(id: Long, line: SpeechCaption, every: Long = 40L, progress: () -> Float?) {
        captionClock?.let(main::removeCallbacks)
        val ticker = object : Runnable {
            override fun run() {
                if (id != sequence || captionUpdate == null) return
                val fraction = progress() ?: return
                reveal(id, line.displayEndForProgress(fraction))
                main.postDelayed(this, every)
            }
        }
        captionClock = ticker
        main.post(ticker)
    }

    fun stop() {
        sequence++
        kokoroRequest?.cancel()
        kokoroRequest = null
        localPending = null
        runCatching { engine?.stop() }
        releaseAudio()
        finishCaptions()
    }

    private fun releaseAudio() {
        player?.close()
        player = null
        focus?.let { (manager, request) -> runCatching { manager.abandonAudioFocusRequest(request) } }
        focus = null
    }

    private fun mayPlay(ctx: Context, id: Long) = id == sequence &&
        ((allowLockedPlayback && WakeListenService.running) ||
            (ctx.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false))

    private fun takeFocus(ctx: Context, id: Long): Boolean {
        val manager = ctx.getSystemService(AudioManager::class.java) ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change -> if (id == sequence && change < 0) stop() }, main)
            .build()
        // Recent Android versions may decline focus while an overlay's app is in the background.
        // In that case don't interrupt the app currently playing audio.
        if (runCatching { manager.requestAudioFocus(request) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = manager to request
        return true
    }

    fun reply(context: Context, text: String, captions: ((Int?) -> Unit)? = null, allowLocked: Boolean = false) {
        val config = VoiceSettings.config(context)
        if (config.enabled) speak(context.applicationContext, text, config, null, captions, allowLocked)
        else captions?.invoke(null)
    }

    /** Explicit read-aloud uses only Android voices marked offline, never model/TTS API paths. */
    fun readPrivate(context: Context, text: String, unavailable: (String) -> Unit, finished: () -> Unit = {}) {
        val line = SpeechCaption.literal(text, TextToSpeech.getMaxSpeechInputLength())
        if (line == null) {
            unavailable("This text is empty or too long for Android’s offline voice. Try sharing a shorter selection.")
            finished()
            return
        }
        stop()
        val id = sequence
        val ctx = context.applicationContext
        if (!mayPlay(ctx, id)) return
        // The overlay isn't always running (e.g. when invoked from Android's Share menu).
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && id == sequence) stop()
            }
        }
        val registered = runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
        }.isSuccess
        if (!registered) {
            unavailable("Android couldn’t start private playback.")
            finished()
            return
        }
        privatePlaybackCleanup = { runCatching { ctx.unregisterReceiver(receiver) } }
        speaking = true
        captionUpdate = { end -> if (end == null && id == sequence) finished() }
        timeLimit(id, 8_000L) {
            unavailable("Android’s offline voice didn’t start.")
            finished()
        }
        local(ctx, id, line, { if (it.isNotEmpty()) unavailable(it) }, "")
    }

    fun preview(context: Context, report: (String) -> Unit) =
        speak(context.applicationContext, VoiceProtocol.PREVIEW, VoiceSettings.config(context), report, null)

    private fun speak(ctx: Context, value: String, config: VoiceSettings.Config, report: ((String) -> Unit)?,
                      captions: ((Int?) -> Unit)?, allowLocked: Boolean = false) {
        val line = SpeechCaption.from(value, VoiceProtocol.MAX_TEXT) ?: run { captions?.invoke(null); return }
        stop()
        val id = sequence
        allowLockedPlayback = allowLocked
        speaking = true
        captionUpdate = captions
        lastCaptionEnd = 0
        if (!mayPlay(ctx, id)) { finishCaptions(); return }
        if (config.source == VoiceSource.ANDROID) {
            timeLimit(id, 16_000L)
            local(ctx, id, line, report, "Playing Android voice.")
            return
        }
        if (config.source == VoiceSource.KOKORO) {
            if (!KokoroVoice.supported() || !KokoroVoice.ready(ctx)) {
                finishCaptions()
                report?.invoke(KokoroVoice.status(ctx))
                return
            }
            if (report == null && !KokoroIpc.automaticAllowed) {
                finishCaptions()
                return
            }
            val request = KokoroIpc.Request(ctx, line.text, config.kokoro) complete@{ clip, problem ->
                if (id != sequence) return@complete
                kokoroRequest = null
                if (!mayPlay(ctx, id)) { stop(); return@complete }
                fun unavailable(message: String) {
                    finishCaptions()
                    report?.invoke(message)
                }
                if (clip == null) {
                    unavailable(problem?.message ?: "Kokoro couldn’t play this preview on your phone.")
                    return@complete
                }
                playKokoro(ctx, id, line, clip, report)
            }
            kokoroRequest = request
            request.begin()
            return
        }
    }

    private fun local(ctx: Context, id: Long, line: SpeechCaption, report: ((String) -> Unit)?, success: String) {
        if (id != sequence) return
        if (!mayPlay(ctx, id)) { stop(); return }
        val ready: (Int) -> Unit = ready@{ status ->
            if (id != sequence) return@ready
            if (!mayPlay(ctx, id)) { stop(); return@ready }
            val tts = engine
            val language = Locale.getDefault().language
            val voices = if (status == TextToSpeech.SUCCESS) tts?.voices.orEmpty() else emptySet()
            val offline = voices.filter { !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            val selected = tts?.defaultVoice?.takeIf { default -> offline.any { it.name == default.name } }
                ?: offline.firstOrNull { it.locale == Locale.getDefault() }
                ?: offline.firstOrNull { it.locale.language == language }
                ?: offline.firstOrNull { it.locale.language == "en" }
            if (tts == null || selected == null || tts.setVoice(selected) != TextToSpeech.SUCCESS) {
                finishCaptions()
                report?.invoke("No offline Android voice is installed. Download one in Android’s text-to-speech settings.")
                return@ready
            }
            if (!takeFocus(ctx, id)) {
                finishCaptions()
                report?.invoke("Pip couldn’t get permission to play audio right now.")
                return@ready
            }
            tts.setAudioAttributes(attrs)
            tts.setPitch(1.12f)
            tts.setSpeechRate(1.05f)
            var timedByEngine = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    main.post {
                        if (utteranceId != id.toString() || id != sequence) return@post
                        val start = SystemClock.uptimeMillis()
                        timeLimit(id, if (success.isEmpty()) 360_000L else 60_000L)
                        // Engines that don't implement word ranges still get a timed reveal.
                        if (!timedByEngine) estimate(id, line) {
                            if (timedByEngine) null else (SystemClock.uptimeMillis() - start - 260L) / line.estimatedDurationMs.toFloat()
                        }
                    }
                }
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    main.post {
                        if (utteranceId != id.toString() || id != sequence) return@post
                        timedByEngine = true
                        reveal(id, line.displayEndForRange(start, end))
                    }
                }
                override fun onDone(utteranceId: String?) = finish(utteranceId, false)
                @Deprecated("Android fallback callback")
                override fun onError(utteranceId: String?) = finish(utteranceId, true)
                private fun finish(utteranceId: String?, error: Boolean) {
                    main.post {
                        if (utteranceId == id.toString() && id == sequence) {
                            releaseAudio()
                            if (error) report?.invoke("Android couldn’t play the voice.")
                            finishCaptions()
                        }
                    }
                }
            })
            if (tts.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) == TextToSpeech.SUCCESS) {
                report?.invoke(success)
            } else {
                releaseAudio()
                finishCaptions()
                report?.invoke("Android couldn’t play the voice.")
            }
        }
        val status = engineStatus
        if (status != null) ready(status) else {
            localPending = ready
            if (engine == null) engine = TextToSpeech(ctx) { result ->
                main.post {
                    val pending = localPending
                    localPending = null
                    if (result == TextToSpeech.SUCCESS) engineStatus = result else {
                        engine?.shutdown()
                        engine = null
                    }
                    pending?.invoke(result)
                }
            }
        }
    }

    private fun playKokoro(ctx: Context, id: Long, line: SpeechCaption, clip: ParcelFileDescriptor,
                           report: ((String) -> Unit)?) {
        fun failed(problem: KokoroFailure) {
            KokoroIpc.playbackResult(problem)
            releaseAudio()
            finishCaptions()
            report?.invoke(problem.message)
        }
        val reader = runCatching { ParcelFileDescriptor.dup(clip.fileDescriptor) }.getOrNull()
        if (reader == null) { failed(KokoroFailure.CLIP); return }
        timeLimit(id, 10_000L) { failed(KokoroFailure.CLIP) }
        clipReader.execute {
            val pcm = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(reader).use { input ->
                    // The writer and duplicated reader share an open-file position. Read from zero.
                    input.channel.position(0L)
                    val size = reader.statSize
                    if (size !in KokoroPcm.MIN_WAV_BYTES.toLong()..VoiceProtocol.MAX_WAV.toLong()) return@use null
                    val bytes = ByteArray(size.toInt())
                    java.io.DataInputStream(input).readFully(bytes)
                    KokoroPcm.parse(bytes)
                }
            }.getOrNull()
            main.post {
                if (id != sequence) return@post
                if (!mayPlay(ctx, id)) { stop(); return@post }
                if (pcm == null) { failed(KokoroFailure.CLIP); return@post }
                if (!takeFocus(ctx, id)) { failed(KokoroFailure.FOCUS); return@post }
                val playback = KokoroPlayback.create(pcm, main, attrs) { ok ->
                    if (id != sequence) return@create
                    if (ok) {
                        KokoroIpc.playbackResult(null)
                        releaseAudio()
                        finishCaptions()
                        report?.invoke("Kokoro preview played offline.")
                    } else failed(KokoroFailure.PLAYBACK)
                }
                if (playback == null) { failed(KokoroFailure.PLAYBACK); return@post }
                player = playback
                if (runCatching { playback.play() }.isFailure) { failed(KokoroFailure.PLAYBACK); return@post }
                timeLimit(id, pcm.frames * 1000L / pcm.sampleRate + 8_000L) { failed(KokoroFailure.PLAYBACK) }
                estimate(id, line) { runCatching { playback.progress() }.getOrDefault(0f) }
            }
        }
    }
}
