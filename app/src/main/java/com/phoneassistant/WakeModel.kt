package com.phoneassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.apache.tools.bzip2.CBZip2InputStream
import org.apache.tools.tar.TarConstants
import org.apache.tools.tar.TarInputStream
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal object WakeModel {
    private const val PACK = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06"
    private const val BYTES = 57_267_600L
    private const val SHA = "c8676e5ff9ac2a85296e53ee0fd4d5fb1db6770e7a7647166eeafe349ade6834"
    private val files = setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var busy = false
        private set

    private fun directory(ctx: Context) = File(ctx.noBackupFilesDir, PACK)
    fun ready(ctx: Context) = files.all { File(directory(ctx), it).let { f -> f.isFile && f.length() > 0 } }

    fun install(ctx: Context, done: (Boolean) -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        if (ready(app)) { done(true); return }
        busy = true
        executor.execute {
            val target = directory(app)
            val stage = File(app.noBackupFilesDir, "$PACK.partial")
            val installed = runCatching {
                check(stage.deleteRecursively() && stage.mkdir() && stage.usableSpace > 150_000_000L)
                val archive = File(stage, "model.tar.bz2")
                val connection = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$PACK.tar.bz2")
                    .openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    check(connection.responseCode == 200)
                    connection.inputStream.use { KokoroFiles.copyVerified(it, archive, BYTES, SHA) {} }
                } finally { connection.disconnect() }
                archive.inputStream().buffered().use { input ->
                    check(input.read() == 'B'.code && input.read() == 'Z'.code)
                    TarInputStream(CBZip2InputStream(input)).use { tar ->
                        val found = mutableSetOf<String>()
                        var total = 0L
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            check(entry.size in 0..(135_000_000L - total))
                            total += entry.size
                            val name = entry.name.removePrefix("./").removePrefix("$PACK/")
                            if (name !in files) continue
                            check(entry.size > 0 && found.add(name) && (entry.linkFlag == TarConstants.LF_NORMAL ||
                                entry.linkFlag == TarConstants.LF_OLDNORM))
                            File(stage, name).outputStream().buffered().use { check(tar.copyTo(it) == entry.size) }
                        }
                        check(found == files)
                    }
                }
                check(archive.delete() && target.deleteRecursively() && stage.renameTo(target))
            }.isSuccess
            stage.deleteRecursively()
            main.post { busy = false; done(installed) }
        }
    }

    fun recognizer(ctx: Context): OnlineRecognizer {
        check(ready(ctx))
        val root = directory(ctx)
        fun path(name: String) = File(root, name).absolutePath
        return OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(path("encoder.onnx"), path("decoder.onnx"), path("joiner.onnx")),
            tokens = path("tokens.txt"), modelType = "zipformer2", numThreads = 1)))
    }
}
