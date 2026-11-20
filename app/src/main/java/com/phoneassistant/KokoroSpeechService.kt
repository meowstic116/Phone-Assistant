package com.phoneassistant

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only this private Android process loads the model and calls native speech code. */
class KokoroSpeechService : Service() {
    private var cancelled: AtomicBoolean? = null
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what != KokoroIpc.SYNTHESIZE) return@Handler false
        @Suppress("DEPRECATION") // API 26 also supports this ParcelFileDescriptor overload.
        val output = request.data.getParcelable<ParcelFileDescriptor>(KokoroIpc.OUTPUT)
            ?: return@Handler true
        val reply = request.replyTo
        val text = request.data.getString(KokoroIpc.TEXT).orEmpty()
        val preset = KokoroPreset.restore(request.data.getString(KokoroIpc.PRESET))
        if (reply == null || cancelled != null || text.isBlank() || text.length > VoiceProtocol.MAX_TEXT) {
            output.closeQuietly()
            reply?.let { sendResult(it, false) }
            return@Handler true
        }
        val stop = AtomicBoolean()
        cancelled = stop
        worker.execute {
            val ok = runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    if (stop.get()) return@use false
                    val wav = synthesize(text, preset, stop) ?: return@use false
                    if (stop.get()) return@use false
                    stream.write(wav)
                    true
                }
            }.getOrDefault(false)
            if (!stop.get()) sendResult(reply, ok)
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        cancelled?.set(true)
        cancelled = null
        return false
    }

    override fun onDestroy() {
        cancelled?.set(true)
        super.onDestroy()
    }

    private fun synthesize(text: String, preset: KokoroPreset, stop: AtomicBoolean): ByteArray? {
        val dir = KokoroVoice.modelDir(this)
        if (!KokoroFiles.ready(dir)) return null
        // Reduce peak memory on devices where Android may have killed the previous in-process engine.
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = File(dir, KokoroFiles.MODEL).path,
                voices = File(dir, KokoroFiles.VOICES).path, tokens = File(dir, KokoroFiles.TOKENS).path,
                lexicon = File(dir, KokoroFiles.LEXICON).path, lang = "en-us"), numThreads = 1)))
        try {
            if (stop.get() || preset.speakerId !in 0 until tts.numSpeakers()) return null
            // Avoid calling into Kotlin from a native synthesis callback.
            val audio = tts.generate(text.replace('’', '\''), preset.speakerId, 1.03f)
            return if (stop.get()) null else pcmWav(audio.samples, audio.sampleRate)
        } finally {
            tts.free()
        }
    }

    private fun sendResult(reply: Messenger, ok: Boolean) {
        runCatching { reply.send(Message.obtain(null, if (ok) KokoroIpc.READY else KokoroIpc.FAILED)) }
    }

    private companion object {
        // A cancelled native call may still be unwinding when Android rebinds. Never load two models.
        val worker = Executors.newSingleThreadExecutor()
    }
}
