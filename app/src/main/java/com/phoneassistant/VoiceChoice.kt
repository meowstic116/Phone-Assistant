package com.phoneassistant

internal enum class VoiceSource(val label: String) {
    KOKORO("Kokoro (offline English)"), WIRO("Hosted VoxCPM2 (Wiro)"), SERVER("Custom VoxCPM2 server"), ANDROID("Offline Android voice");

    companion object {
        fun restore(saved: String?, endpoint: String) = values().firstOrNull { it.name == saved }
            ?: if (endpoint.isBlank()) KOKORO else SERVER
    }
}

internal enum class KokoroPreset(val label: String, val speakerId: Int) {
    ADAM("Adam · male (default)", 5), MICHAEL("Michael · male", 6), BELLA("Bella · feminine", 1);

    companion object {
        fun restore(value: String?) = values().firstOrNull { it.name == value } ?: ADAM
    }
}

internal fun pcmWav(samples: FloatArray, rate: Int): ByteArray? {
    if (rate !in 8_000..48_000 || samples.isEmpty() || samples.size > (VoiceProtocol.MAX_WAV - 44) / 2) return null
    val pcm = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (sample in samples) pcm.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    return WiroSpeechProtocol.wav(pcm.array(), rate)
}

internal enum class PipVoiceStyle(val label: String, val description: String) {
    MALE("Synthetic male (default)", "An adult male text-to-speech voice, crisp and lightly robotic, with an even pace and clear diction"),
    WARM("Warm narrator", "A warm adult male narrator with a mellow tone, gentle humor, and relaxed articulation"),
    BRIGHT("Bright and playful", "A bright adult feminine voice with playful energy, a light tone and crisp articulation"),
    CUSTOM("Custom description", "");

    fun instruction(custom: String): String = if (this == CUSTOM) clean(custom).ifBlank { MALE.description } else description

    companion object {
        const val MAX_DESCRIPTION = 120
        fun clean(value: String): String = value.replace(Regex("[()\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(MAX_DESCRIPTION).trimEnd { it.isHighSurrogate() }
        fun restore(saved: String?) = values().firstOrNull { it.name == saved } ?: MALE
    }
}

internal object WiroSpeechProtocol {
    const val RUN = "https://api.wiro.ai/v1/Run/openbmb/voxcpm2"
    const val SOCKET = "wss://socket.wiro.ai/v1"
    const val MAX_PROMPT = 499
    const val PCM_RATE = 24_000

    fun prompt(style: String, text: String) = "($style)$text"

    fun pcmOffset(frame: ByteArray, token: ByteArray): Int? {
        if (frame.size <= token.size + 1 || frame[token.size] != '|'.code.toByte() ||
            (frame.size - token.size - 1) % 2 != 0) return null
        return (token.size + 1).takeIf { token.indices.all { i -> frame[i] == token[i] } }
    }

    fun wav(pcm: ByteArray, rate: Int = PCM_RATE): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size)
        return header.array() + pcm
    }
}
