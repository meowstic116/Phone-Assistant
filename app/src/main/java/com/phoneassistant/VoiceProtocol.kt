package com.phoneassistant

internal object VoiceProtocol {
    const val MAX_TEXT = 420
    const val MAX_WAV = 2_000_000
    const val PREVIEW = "Hello, I’m Pip. A tiny phone with an unusually large vocabulary."

    fun isWav(prefix: ByteArray): Boolean = prefix.size >= 12 &&
        String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" && String(prefix, 8, 4, Charsets.US_ASCII) == "WAVE"
}
