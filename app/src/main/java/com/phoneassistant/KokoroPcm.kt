package com.phoneassistant

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The only audio passed by the voice worker: a bounded, canonical mono PCM16 WAV. */
internal data class KokoroPcm(val wav: ByteArray, val sampleRate: Int) {
    val frames: Int get() = (wav.size - HEADER_BYTES) / 2

    companion object {
        const val HEADER_BYTES = 44
        const val MIN_WAV_BYTES = HEADER_BYTES + 2

        fun parse(wav: ByteArray): KokoroPcm? {
            if (wav.size !in MIN_WAV_BYTES..VoiceProtocol.MAX_WAV || wav.size % 2 != 0 || !VoiceProtocol.isWav(wav)) return null
            val format = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            val rate = format.getInt(24)
            val dataBytes = wav.size - HEADER_BYTES
            if (format.getInt(4) != wav.size - 8 || !wav.copyOfRange(12, 16).contentEquals("fmt ".toByteArray(Charsets.US_ASCII)) ||
                format.getInt(16) != 16 || format.getShort(20).toInt() != 1 || format.getShort(22).toInt() != 1 ||
                rate !in 8_000..48_000 || format.getInt(28) != rate * 2 || format.getShort(32).toInt() != 2 ||
                format.getShort(34).toInt() != 16 || !wav.copyOfRange(36, 40).contentEquals("data".toByteArray(Charsets.US_ASCII)) ||
                format.getInt(40) != dataBytes) return null
            return KokoroPcm(wav, rate)
        }
    }
}
