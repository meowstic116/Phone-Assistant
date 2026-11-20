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
    data class Config(val enabled: Boolean, val source: VoiceSource, val endpoint: String, val token: String,
                      val wiroKey: String, val style: PipVoiceStyle, val custom: String, val kokoro: KokoroPreset) {
        val description: String get() = style.instruction(custom)
    }
    private fun prefs(context: Context) = context.getSharedPreferences("pip_voice", Context.MODE_PRIVATE)
    fun config(context: Context) = prefs(context).let {
        val endpoint = it.getString("endpoint", "").orEmpty()
        Config(it.getBoolean("enabled", false), VoiceSource.restore(it.getString("source", null), endpoint), endpoint,
            it.getString("token", "").orEmpty(), it.getString("wiro_key", "").orEmpty(),
            PipVoiceStyle.restore(it.getString("style", null)), it.getString("custom", "").orEmpty(),
            KokoroPreset.restore(it.getString("kokoro_preset", null)))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) stop()
    }

    fun save(context: Context, config: Config) {
        stop()
        prefs(context).edit().putString("source", config.source.name).putString("endpoint", config.endpoint)
            .putString("token", config.token).putString("wiro_key", config.wiroKey)
            .putString("style", config.style.name).putString("custom", config.custom)
            .putString("kokoro_preset", config.kokoro.name).apply()
    }

    // Calls from the app's UI thread. The worker only fetches; audio resources belong to main.
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var sequence = 0L
    private var work: Future<*>? = null
    private var kokoroRequest: KokoroIpc.Request? = null
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
        kokoroRequest?.cancel()
        kokoroRequest = null
        localPending = null
        runCatching { engine?.stop() }
        releaseAudio()
        finishCaptions()
    }

    private fun releaseAudio() {
        runCatching { player?.release() }
        player = null
        focus?.let { (manager, request) -> runCatching { manager.abandonAudioFocusRequest(request) } }
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
        if (runCatching { manager.requestAudioFocus(request) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
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
        val hosted = config.source == VoiceSource.WIRO
        val limit = if (hosted) minOf(VoiceProtocol.MAX_TEXT, WiroSpeechProtocol.MAX_PROMPT - config.description.length - 2)
            else VoiceProtocol.MAX_TEXT
        val line = SpeechCaption.from(value, limit) ?: run { captions?.invoke(null); return }
        stop()
        val id = sequence
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
                timeLimit(id, 8_000L)
                val failed = { unavailable("Kokoro generated audio, but Android couldn’t play it.") }
                if (!play(ctx, id, line, report, "Playing Kokoro offline.", failed) { media ->
                    media.setDataSource(clip.fileDescriptor, 0L, clip.statSize)
                }) failed()
            }
            kokoroRequest = request
            request.begin()
            return
        }
        // In remote modes, local phone results stay text-only and never reach a speech provider.
        if (!allowRemote) { finishCaptions(); return }
        val configured = if (hosted) config.wiroKey.isNotBlank() else VoiceProtocol.endpoint(config.endpoint) != null
        if (!configured) {
            finishCaptions()
            report?.invoke(if (hosted) "Add a Wiro API key in Voice settings first." else "Add a custom VoxCPM2 server URL first.")
            return
        }
        timeLimit(id, if (hosted) 32_000L else 16_000L)
        work = worker.submit {
            val wav = try {
                if (hosted) WiroSpeech.synthesize(config.wiroKey, WiroSpeechProtocol.prompt(config.description, line.text))
                else fetch(config, line.text)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@submit
            } catch (_: Exception) { null }
            if (Thread.currentThread().isInterrupted) return@submit
            main.post {
                if (id != sequence) return@post
                if (!mayPlay(ctx, id)) { stop(); return@post }
                work = null
                fun unavailable() {
                    finishCaptions()
                    report?.invoke(if (hosted) "Wiro couldn’t play this preview. Check your key, balance, and connection."
                        else "Couldn’t play the preview from your VoxCPM2 server.")
                }
                if (wav == null || !play(ctx, id, line, report, "Playing VoxCPM voice.", ::unavailable) { media ->
                    val clip = File.createTempFile("pip-voice-", ".wav", ctx.cacheDir)
                    try {
                        clip.writeBytes(wav)
                        clip.inputStream().use { media.setDataSource(it.fd) }
                    } finally {
                        clip.delete()
                    }
                }) unavailable()
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

    private fun play(ctx: Context, id: Long, line: SpeechCaption, report: ((String) -> Unit)?, success: String,
                     failure: () -> Unit, dataSource: (MediaPlayer) -> Unit): Boolean {
        return runCatching {
            val media = MediaPlayer()
            player = media
            dataSource(media)
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
                        report?.invoke(success)
                    }.onFailure { releaseAudio(); failure() }
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
                    failure()
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
                .put("voice", "default").put("response_format", "wav").put("instructions", config.description).toString()
            val body = json.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
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
