package com.phoneassistant

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal object KokoroVoice {
    private const val MODEL_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val SPACE_NEEDED = 270_000_000L
    private val main = Handler(Looper.getMainLooper())
    private val installer = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>() // Main thread only.
    private var stage: String? = null
    private var error: String? = null

    fun supported() = Build.VERSION.SDK_INT >= 27 && Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    fun modelDir(ctx: Context) = File(ctx.noBackupFilesDir, KokoroFiles.PACK)
    private fun partialDir(ctx: Context) = File(ctx.noBackupFilesDir, "${KokoroFiles.PACK}.partial")
    fun hasDownload(ctx: Context) = modelDir(ctx).exists() || partialDir(ctx).exists()
    fun ready(ctx: Context) = KokoroFiles.ready(modelDir(ctx))
    fun busy() = stage != null
    fun status(ctx: Context): String = when {
        !supported() -> "Kokoro needs Android 8.1 or later and a supported processor."
        stage != null -> stage.orEmpty()
        error != null -> error.orEmpty()
        ready(ctx) -> KokoroIpc.lastProblem?.message ?: "Kokoro is installed. Tap Preview to test audio."
        hasDownload(ctx) -> "Kokoro’s download is incomplete. Remove it, then download again."
        else -> "Kokoro needs a one-time 110 MB download."
    }

    fun observe(listener: () -> Unit) { listeners.add(listener); listener() }
    fun forget(listener: () -> Unit) { listeners.remove(listener) }
    private fun update(value: String?, failure: String? = null) {
        stage = value
        error = failure
        listeners.toList().forEach { it() }
    }

    fun install(context: Context) {
        val ctx = context.applicationContext
        if (!supported() || busy() || ready(ctx)) return
        update("Starting Kokoro download…")
        installer.execute {
            val target = modelDir(ctx)
            val staging = partialDir(ctx)
            val failure = runCatching {
                check(staging.deleteRecursively())
                if (ctx.noBackupFilesDir.usableSpace < SPACE_NEEDED) throw NotEnoughSpace()
                check(staging.mkdir())
                val archive = File(staging, "pack.tar.bz2")
                var last = -1
                download("${KokoroFiles.PACK}.tar.bz2", archive, KokoroFiles.ARCHIVE_BYTES,
                    KokoroFiles.ARCHIVE_SHA) { bytes ->
                    val percent = (90L * bytes / KokoroFiles.ARCHIVE_BYTES).toInt()
                    if (percent != last) {
                        last = percent
                        main.post { update("Downloading Kokoro… $percent%") }
                    }
                }
                download(KokoroFiles.LEXICON, File(staging, KokoroFiles.LEXICON), KokoroFiles.LEXICON_BYTES,
                    KokoroFiles.LEXICON_SHA) { bytes ->
                    val percent = (90 + 10L * bytes / KokoroFiles.LEXICON_BYTES).toInt()
                    if (percent != last) {
                        last = percent
                        main.post { update("Downloading Kokoro… $percent%") }
                    }
                }
                main.post { update("Installing Kokoro…") }
                archive.inputStream().buffered().use { KokoroFiles.extract(it, staging) }
                check(archive.delete() && KokoroFiles.ready(staging))
                check(target.deleteRecursively() && staging.renameTo(target))
            }.exceptionOrNull()
            staging.deleteRecursively()
            main.post {
                update(null, when (failure) {
                    null -> null
                    is NotEnoughSpace -> "Free at least 270 MB and try again."
                    else -> "Couldn’t install Kokoro. Check your connection and free space, then retry."
                })
            }
        }
    }

    private class NotEnoughSpace : Exception()

    private fun download(name: String, file: File, size: Long, sha: String, progress: (Long) -> Unit) {
        val connection = URL(MODEL_BASE + name).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            check(connection.responseCode == 200)
            connection.inputStream.use { KokoroFiles.copyVerified(it, file, size, sha, progress = progress) }
        } finally { connection.disconnect() }
    }

    fun remove(context: Context) {
        val ctx = context.applicationContext
        if (busy() || !hasDownload(ctx)) return
        PipVoice.stop()
        update("Removing Kokoro…")
        installer.execute {
            val modelRemoved = modelDir(ctx).deleteRecursively()
            val partialRemoved = partialDir(ctx).deleteRecursively()
            val removed = modelRemoved && partialRemoved
            main.post { update(null, if (removed) null else "Couldn’t remove all of Kokoro’s files.") }
        }
    }
}
