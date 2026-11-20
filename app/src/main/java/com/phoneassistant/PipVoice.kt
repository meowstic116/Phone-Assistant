package com.phoneassistant

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Final replies only. Toy banter and agent progress stay silent, and private phone results stay local. */
internal object PipVoice {
    data class Config(val enabled: Boolean, val endpoint: String, val token: String)
    private fun prefs(context: Context) = context.getSharedPreferences("pip_voice", Context.MODE_PRIVATE)
    fun config(context: Context) = prefs(context).let {
        Config(it.getBoolean("enabled", false), it.getString("endpoint", "").orEmpty(),
            it.getString("token", "").orEmpty())
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) stop()
    }

    fun setServer(context: Context, endpoint: String, token: String) {
        stop()
        prefs(context).edit().putString("endpoint", endpoint).putString("token", token).apply()
    }

    // Calls from the app's UI thread. The worker only fetches; audio resources belong to main.
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var sequence = 0L
    private var work: Future<*>? = null
    private var player: MediaPlayer? = null
    private var engine: TextToSpeech? = null
    private var engineStatus: Int? = null
    private var localPending: ((Int) -> Unit)? = null
    private var focus: Pair<AudioManager, AudioFocusRequest>? = null
    private var captionUpdate: ((Int?) -> Unit)? = null
    private var lastCaptionEnd = 0
    private var captionClock: Runnable? = null
    private var speechDeadline: Runnable? = null

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
        val report = captionUpdate
        captionUpdate = null
        report?.invoke(null)
    }

    private fun timeLimit(id: Long, delay: Long) {
        speechDeadline?.let(main::removeCallbacks)
        speechDeadline = Runnable { if (id == sequence) stop() }.also { main.postDelayed(it, delay) }
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
        work?.cancel(true)
        work = null
        localPending = null
        engine?.stop()
        releaseAudio()
        finishCaptions()
    }

    private fun releaseAudio() {
        player?.release()
        player = null
        focus?.let { (manager, request) -> manager.abandonAudioFocusRequest(request) }
        focus = null
    }

    private fun mayPlay(ctx: Context, id: Long) = id == sequence &&
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true &&
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun takeFocus(ctx: Context, id: Long): Boolean {
        val manager = ctx.getSystemService(AudioManager::class.java) ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change -> if (id == sequence && change < 0) stop() }, main)
            .build()
        // Recent Android versions may decline focus while an overlay's app is in the background.
        // In that case don't interrupt the app currently playing audio.
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = manager to request
        return true
    }

    fun reply(context: Context, text: String, allowRemote: Boolean, captions: ((Int?) -> Unit)? = null) {
        val config = config(context)
        if (config.enabled) speak(context.applicationContext, text, config, allowRemote, null, captions)
        else captions?.invoke(null)
    }

    fun preview(context: Context, report: (String) -> Unit) =
        speak(context.applicationContext, VoiceProtocol.PREVIEW, config(context), true, report, null)

    private fun speak(ctx: Context, value: String, config: Config, allowRemote: Boolean, report: ((String) -> Unit)?,
                      captions: ((Int?) -> Unit)?) {
        val line = SpeechCaption.from(value, VoiceProtocol.MAX_TEXT) ?: run { captions?.invoke(null); return }
        stop()
        val id = sequence
        captionUpdate = captions
        lastCaptionEnd = 0
        if (!mayPlay(ctx, id)) { finishCaptions(); return }
        timeLimit(id, 16_000L)
        if (!allowRemote || VoiceProtocol.endpoint(config.endpoint) == null) {
            local(ctx, id, line, report, "Playing Android voice.")
            return
        }
        work = worker.submit {
            val wav = runCatching { fetch(config, line.text) }.getOrNull()
            if (Thread.currentThread().isInterrupted) return@submit
            main.post {
                if (id != sequence) return@post
                if (!mayPlay(ctx, id)) { stop(); return@post }
                work = null
                fun fallback() = local(ctx, id, line, report, "VoxCPM unavailable; playing Android voice.")
                if (wav == null || !play(ctx, id, line, wav, report, ::fallback)) fallback()
            }
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
            val selected = offline.firstOrNull { it.locale == Locale.getDefault() }
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
                        timeLimit(id, 60_000L)
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
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Android fallback callback")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                private fun finish(utteranceId: String?) {
                    main.post {
                        if (utteranceId == id.toString() && id == sequence) {
                            releaseAudio()
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

    private fun play(ctx: Context, id: Long, line: SpeechCaption, wav: ByteArray, report: ((String) -> Unit)?, fallback: () -> Unit): Boolean {
        val media = MediaPlayer()
        player = media
        return runCatching {
            val clip = File.createTempFile("pip-voice-", ".wav", ctx.cacheDir)
            try {
                clip.writeBytes(wav)
                clip.inputStream().use { media.setDataSource(it.fd) }
            } finally {
                clip.delete()
            }
            media.setAudioAttributes(attrs)
            media.setOnPreparedListener {
                if (player === media && mayPlay(ctx, id) && takeFocus(ctx, id)) {
                    runCatching { media.start() }.onSuccess {
                        timeLimit(id, 60_000L)
                        estimate(id, line) {
                            if (player !== media) null else runCatching {
                                media.currentPosition / media.duration.coerceAtLeast(1).toFloat()
                            }.getOrNull()
                        }
                        report?.invoke("Playing VoxCPM voice.")
                    }.onFailure { releaseAudio(); fallback() }
                } else if (player === media) {
                    releaseAudio()
                    finishCaptions()
                    report?.invoke("Pip couldn’t get permission to play audio right now.")
                }
            }
            media.setOnCompletionListener {
                if (player === media) { releaseAudio(); finishCaptions() }
            }
            media.setOnErrorListener { _, _, _ ->
                if (player === media) {
                    releaseAudio()
                    // Avoid re-reading the same words if playback already got underway.
                    if (lastCaptionEnd > 0) finishCaptions() else fallback()
                }
                true
            }
            media.prepareAsync()
        }.onFailure { releaseAudio() }.isSuccess
    }

    private fun fetch(config: Config, text: String): ByteArray? {
        val endpoint = VoiceProtocol.endpoint(config.endpoint) ?: return null
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3_500
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/wav")
            if (config.token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            val json = JSONObject().put("model", VoiceProtocol.MODEL).put("input", text)
                .put("voice", "default").put("response_format", "wav").toString()
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200 || connection.contentLength > VoiceProtocol.MAX_WAV ||
                connection.contentType?.substringBefore(';')?.trim()?.lowercase() !in setOf("audio/wav", "audio/x-wav")) return null
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) return@use output.toByteArray().takeIf(VoiceProtocol::isWav)
                    if (output.size() + count > VoiceProtocol.MAX_WAV) return@use null
                    output.write(buffer, 0, count)
                }
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}
