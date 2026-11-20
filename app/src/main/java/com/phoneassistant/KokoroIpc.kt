package com.phoneassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import java.io.File

internal fun ParcelFileDescriptor.closeQuietly() { runCatching { close() } }

/** Main-thread client. Only status and an anonymous file descriptor travel back, not WAV over Binder. */
internal object KokoroIpc {
    const val SYNTHESIZE = 1
    const val READY = 2
    const val FAILED = 3
    const val TEXT = "text"
    const val PRESET = "preset"
    const val OUTPUT = "output"

    private val main = Handler(Looper.getMainLooper())
    private val health = KokoroHealth()
    val lastProblem get() = health.lastProblem
    val automaticAllowed get() = health.automaticAllowed

    class Request(private val ctx: Context, private val text: String, private val preset: KokoroPreset,
                  private val done: (ParcelFileDescriptor?, KokoroFailure?) -> Unit) : ServiceConnection {
        private var bound = false
        private var finished = false
        private var clip: ParcelFileDescriptor? = null
        private val deadline = Runnable { finish(KokoroFailure.TIMEOUT) }
        private val incoming = Messenger(Handler(Looper.getMainLooper()) { result ->
            if (finished) return@Handler true
            val valid = result.what == READY &&
                (clip?.statSize ?: 0L) in 44L..VoiceProtocol.MAX_WAV.toLong()
            finish(if (valid) null else KokoroFailure.NO_AUDIO)
            true
        })

        fun begin() {
            // Unlink immediately: a stopped or interrupted reply leaves no named audio file behind.
            clip = runCatching {
                val file = File.createTempFile("pip-kokoro-", ".wav", ctx.cacheDir)
                try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE) }
                finally { file.delete() }
            }.getOrNull()
            if (clip == null) { finish(KokoroFailure.BINDING); return }
            val connected = runCatching {
                val result = ctx.bindService(Intent(ctx, KokoroSpeechService::class.java), this,
                    Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
                bound = true // Android requires an unbind even when the call returns false.
                result
            }.getOrDefault(false)
            if (connected) main.postDelayed(deadline, 20_000L) else finish(KokoroFailure.BINDING)
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (finished) return
            if (service == null) { finish(KokoroFailure.BINDING); return }
            runCatching {
                Messenger(service).send(Message.obtain(null, SYNTHESIZE).apply {
                    replyTo = incoming
                    data = Bundle().apply {
                        putString(TEXT, text)
                        putString(PRESET, preset.name)
                        putParcelable(OUTPUT, clip)
                    }
                })
            }.onFailure { finish(KokoroFailure.STOPPED) }
        }

        override fun onServiceDisconnected(name: ComponentName?) = finish(KokoroFailure.STOPPED)
        override fun onBindingDied(name: ComponentName?) = finish(KokoroFailure.STOPPED)
        override fun onNullBinding(name: ComponentName?) = finish(KokoroFailure.BINDING)

        fun cancel() = close()

        private fun close() {
            if (finished) return
            finished = true
            main.removeCallbacks(deadline)
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false
            clip?.closeQuietly()
            clip = null
        }

        private fun finish(failure: KokoroFailure?) {
            if (finished) return
            health.completed(failure)
            try { done(clip.takeIf { failure == null }, failure) }
            finally { close() }
        }
    }
}
