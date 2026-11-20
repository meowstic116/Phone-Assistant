package com.phoneassistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler

/** Sends PCM directly to Android, without asking a media decoder to open the IPC descriptor. */
internal class KokoroPlayback private constructor(
    private val pcm: KokoroPcm, private val main: Handler, private val track: AudioTrack,
    private val done: (Boolean) -> Unit
) {
    private var byteOffset = KokoroPcm.HEADER_BYTES
    // A stream shorter than the device's buffer may otherwise never reach its start threshold.
    private var paddingBytes = (track.bufferSizeInFrames - pcm.frames).coerceAtLeast(0) * 2
    private val silence = ByteArray(16_384)
    private var closed = false
    private val pump = object : Runnable {
        override fun run() {
            if (closed) return
            val finished = try {
                enqueue()
                byteOffset == pcm.wav.size && track.playbackHeadPosition >= pcm.frames
            } catch (_: RuntimeException) {
                done(false)
                return
            }
            if (finished) done(true) else main.postDelayed(this, 20L)
        }
    }

    private fun enqueue() {
        val remaining = pcm.wav.size - byteOffset
        if (remaining == 0 && paddingBytes == 0) return
        val count = if (remaining != 0) {
            track.write(pcm.wav, byteOffset, minOf(remaining, silence.size), AudioTrack.WRITE_NON_BLOCKING)
        } else {
            track.write(silence, 0, minOf(paddingBytes, silence.size), AudioTrack.WRITE_NON_BLOCKING)
        }
        check(count >= 0 && count % 2 == 0)
        if (remaining != 0) byteOffset += count else paddingBytes -= count
    }

    fun play() {
        enqueue() // Preload at least one frame before starting the output clock.
        check(byteOffset > KokoroPcm.HEADER_BYTES)
        track.play()
        main.post(pump)
    }
    fun progress() = track.playbackHeadPosition.coerceAtLeast(0) / pcm.frames.toFloat()

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(pump)
        runCatching { track.release() }
    }

    companion object {
        fun create(pcm: KokoroPcm, main: Handler, attributes: AudioAttributes, done: (Boolean) -> Unit): KokoroPlayback? = runCatching {
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(pcm.sampleRate).build()
            val min = AudioTrack.getMinBufferSize(pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(min, (pcm.sampleRate / 4) * 2)).build()
            if (track.state == AudioTrack.STATE_INITIALIZED) KokoroPlayback(pcm, main, track, done)
            else { track.release(); null }
        }.getOrNull()
    }
}
