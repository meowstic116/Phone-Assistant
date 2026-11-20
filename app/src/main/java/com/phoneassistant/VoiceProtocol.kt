package com.phoneassistant

import java.net.URI

internal object VoiceProtocol {
    const val MODEL = "openbmb/VoxCPM2"
    const val MAX_TEXT = 420
    const val MAX_WAV = 2_000_000
    const val PREVIEW = "Hello, I’m Pip. A tiny phone with an unusually large vocabulary."

    fun endpoint(value: String): URI? = runCatching {
        URI(value.trim()).takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() &&
                it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null &&
                it.path.endsWith("/v1/audio/speech")
        }
    }.getOrNull()

    fun spokenText(value: String): String? = SpeechCaption.from(value, MAX_TEXT)?.text

    fun isWav(prefix: ByteArray): Boolean = prefix.size >= 12 &&
        String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" && String(prefix, 8, 4, Charsets.US_ASCII) == "WAVE"
}
