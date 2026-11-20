package com.phoneassistant

internal enum class VoiceSource(val label: String) {
    KOKORO("Kokoro (offline English)"), ANDROID("Offline Android voice");

    companion object {
        fun restore(saved: String?) = values().firstOrNull { it.name == saved } ?: KOKORO
    }
}

internal enum class KokoroPreset(val label: String, val speakerId: Int) {
    ADAM("Adam · male (default)", 5), MICHAEL("Michael · male", 6), BELLA("Bella · feminine", 1);

    companion object {
        fun restore(value: String?) = values().firstOrNull { it.name == value } ?: ADAM
    }
}

internal fun pcmWav(samples: FloatArray, rate: Int): ByteArray? {
    if (rate !in 8_000..48_000 || samples.isEmpty() || samples.size > (VoiceProtocol.MAX_WAV - KokoroPcm.HEADER_BYTES) / 2) return null
    val bytes = samples.size * 2
    val wav = java.nio.ByteBuffer.allocate(KokoroPcm.HEADER_BYTES + bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
    wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
    wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
    for (sample in samples) wav.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
    return wav.array()
}
