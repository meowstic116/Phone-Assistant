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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only this private Android process loads the model and calls native speech code. */
class KokoroSpeechService : Service() {
    private val main = Handler(Looper.getMainLooper())
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
            reply?.let { sendResult(it, KokoroFailure.NO_AUDIO) }
            return@Handler true
        }
        val stop = AtomicBoolean()
        cancelled = stop
        worker.execute {
            val failure = try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    if (stop.get()) return@use KokoroFailure.NO_AUDIO
                    val tts = try { engine() } catch (_: LinkageError) {
                        return@use KokoroFailure.ENGINE
                    } catch (_: Exception) {
                        return@use KokoroFailure.MODEL
                    }
                    if (stop.get()) return@use KokoroFailure.NO_AUDIO
                    reply.send(Message.obtain(null, KokoroIpc.SYNTHESIZING))
                    if (preset.speakerId !in 0 until tts.numSpeakers()) return@use KokoroFailure.MODEL
                    val audio = tts.generate(text.replace('’', '\''), preset.speakerId, 1.03f)
                    val wav = pcmWav(audio.samples, audio.sampleRate) ?: return@use KokoroFailure.NO_AUDIO
                    if (stop.get()) return@use KokoroFailure.NO_AUDIO
                    stream.write(wav)
                    null
                }
            } catch (_: LinkageError) {
                KokoroFailure.ENGINE
            } catch (_: Exception) {
                KokoroFailure.NO_AUDIO
            } finally {
                unload?.cancel(false)
                unload = worker.schedule({ releaseEngine() }, 30L, TimeUnit.SECONDS)
            }
            main.post {
                if (cancelled === stop) cancelled = null
                if (!stop.get()) sendResult(reply, failure)
            }
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

    private fun engine(): OfflineTts {
        val dir = KokoroVoice.modelDir(this)
        check(KokoroFiles.ready(dir))
        val stamp = File(dir, KokoroFiles.MODEL).lastModified()
        val cached = cachedEngine
        if (cached != null && stamp == modelStamp) return cached
        releaseEngine()
        return OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(model = File(dir, KokoroFiles.MODEL).path,
                voices = File(dir, KokoroFiles.VOICES).path, tokens = File(dir, KokoroFiles.TOKENS).path,
                lexicon = File(dir, KokoroFiles.LEXICON).path), numThreads = 2))).also {
            cachedEngine = it
            modelStamp = stamp
        }
    }

    private fun sendResult(reply: Messenger, failure: KokoroFailure?) {
        runCatching {
            reply.send(Message.obtain(null, if (failure == null) KokoroIpc.READY else KokoroIpc.FAILED).apply {
                if (failure != null) data.putString(KokoroIpc.FAILURE, failure.name)
            })
        }
    }

    private companion object {
        // One native session on one thread, even if a cancelled request is still unwinding.
        val worker = Executors.newSingleThreadScheduledExecutor()
        var cachedEngine: OfflineTts? = null
        var modelStamp = 0L
        var unload: ScheduledFuture<*>? = null

        fun releaseEngine() {
            val previous = cachedEngine
            cachedEngine = null
            runCatching { previous?.free() }
        }
    }
}
