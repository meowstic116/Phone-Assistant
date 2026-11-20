package com.phoneassistant

import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Wiro's hosted VoxCPM2: one authenticated run, then token-scoped 24 kHz PCM frames. */
internal object WiroSpeech {
    fun synthesize(key: String, prompt: String): ByteArray? {
        val token = submit(key, prompt) ?: return null
        if (Thread.currentThread().isInterrupted) return null
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var complete = false
        val socket = object : WebSocketClient(URI(WiroSpeechProtocol.SOCKET), Draft_6455(emptyList(), 256_000),
            emptyMap(), 5_000) {
            override fun onOpen(handshake: ServerHandshake) {
                send(JSONObject().put("type", "task_info").put("tasktoken", token).toString())
            }

            override fun onMessage(message: String) {
                val event = runCatching { JSONObject(message) }.getOrNull() ?: return
                when (event.optString("type")) {
                    "task_stream_end" -> complete = audio.size() > 0
                    "task_postprocess_end" -> {
                        if (event.has("success") && !event.optBoolean("success", false)) complete = false
                        done.countDown()
                    }
                    "task_cancel" -> { complete = false; done.countDown() }
                }
            }

            override fun onMessage(bytes: ByteBuffer) {
                if (done.count == 0L) return
                val frame = ByteArray(bytes.remaining()).also { bytes.get(it) }
                val start = WiroSpeechProtocol.pcmOffset(frame, tokenBytes) ?: return
                val length = frame.size - start
                if (audio.size() + length + 44 > VoiceProtocol.MAX_WAV) {
                    complete = false
                    done.countDown()
                } else audio.write(frame, start, length)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) { done.countDown() }
            override fun onError(ex: Exception) { done.countDown() }
        }
        // Keep the library’s default TLS socket wrapping, SNI, and HTTPS hostname checks.
        return try {
            socket.connect()
            if (done.await(19L, TimeUnit.SECONDS) && complete) WiroSpeechProtocol.wav(audio.toByteArray()) else null
        } finally {
            if (socket.isOpen) {
                if (done.count != 0L) runCatching {
                    socket.send(JSONObject().put("type", "task_session_end").put("tasktoken", token).toString())
                }
                socket.close()
            } else if (!socket.isClosed) {
                socket.closeConnection(1000, "Speech ended")
            }
        }
    }

    private fun submit(key: String, prompt: String): String? {
        val connection = URI(WiroSpeechProtocol.RUN).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 4_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-api-key", key)
            val body = JSONObject().put("prompt", prompt).toString().toByteArray(Charsets.UTF_8)
            // Explicit length prevents HttpURLConnection from redirecting/retrying a billed POST.
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) return null
            val data = connection.inputStream.reader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (output.length + count > 16_384) return null
                    output.append(buffer, 0, count)
                }
                JSONObject(output.toString())
            }
            return data.optString("socketaccesstoken").takeIf {
                data.optBoolean("result") && it.length in 1..256 && it.none { ch -> ch.isWhitespace() || ch == '|' }
            }
        } finally {
            connection.disconnect()
        }
    }
}
