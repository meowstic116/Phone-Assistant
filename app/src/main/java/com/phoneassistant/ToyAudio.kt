package com.phoneassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Plays the toy sounds: [ToySounds] makes the waveforms, this puts them on the speaker.
 *
 * Three things matter here, and they are all about not being annoying:
 *
 * - **Short and quiet.** Every toy sound is under a second. They are mixed well below speech,
 *   and [duck] can hush the whole set when Pip is talking, because a squeak over his own
 *   sentence is worse than no squeak.
 * - **Off is one switch.** [enabled] is read per play, so turning toy sounds off stops the next
 *   one rather than after a restart. Silence is the default for someone who has not chosen.
 * - **Nothing is loaded from disk.** A clip is rendered once and kept — thirteen families with
 *   a hundred possible pitches each would be a lot of buffers, so the cache is bounded and
 *   evicts oldest-first. Rendering is ~3 ms; playback never blocks the caller.
 *
 * Playback uses one-shot AudioTracks built on a worker, not a streaming engine: a bubble pop is
 * a single buffer, and a track that finishes is a track to throw away. Failures are swallowed —
 * a phone with no free mixer channel loses a squeak, not a tap.
 */
internal object ToyAudio {
    private const val PREFS = "toy_sounds"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME = "volume"

    /** Mixing at about a quarter of full scale: these are effects, not audio content. */
    private const val BASE_GAIN = 0.30f

    /** Cache limit, in clips. Thirteen sounds times eight pitches is 104; this holds a dozen. */
    private const val CACHE_LIMIT = 48

    /**
     * Rendered takes, least recently used first. Touched only by the single renderer thread —
     * a LinkedHashMap in access order mutates on every read, so it is a data race the moment a
     * second thread looks at it. If a reader is ever added elsewhere, this needs a lock.
     */
    private val cache = object : LinkedHashMap<String, ToySounds.Clip>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ToySounds.Clip>?): Boolean =
            size > CACHE_LIMIT
    }

    @Volatile
    private var cachedEnabled: Boolean? = null

    /** Read per play, so the switch takes effect on the next squeak. Cached per process start. */
    fun enabled(ctx: Context): Boolean {
        cachedEnabled?.let { return it }
        val value = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        cachedEnabled = value
        return value
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        cachedEnabled = enabled
    }

    /** 0.2–1.0, so a toy can be made quieter without being turned off. */
    fun volume(ctx: Context): Float = ctx.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_VOLUME, 0.7f)

    fun setVolume(ctx: Context, value: Float) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_VOLUME, value.coerceIn(0.2f, 1f)).apply()
    }

    /** Hushing for Pip's own voice, and for the user dragging something across the screen. */
    @Volatile
    private var duckedUntil = 0L

    fun duck(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        if (until > duckedUntil) duckedUntil = until
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Two workers: one renders, one plays. A screenful of bubble wrap can ask for twenty pops in
     * a second, and rendering must never delay the sound that is already due.
     */
    private val renderer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PipToyRender").apply { isDaemon = true }
    }

    private val player = ThreadPoolExecutor(
        1, 1, 1, TimeUnit.SECONDS, ArrayBlockingQueue(12)
    ) { runnable -> Thread(runnable, "PipToyPlay").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private val random = java.util.Random()

    /** Books a fresh variation. Cheap: the waveform is rendered on the worker. */
    fun play(ctx: Context, sound: ToySounds.Sound, gain: Float = 1f) {
        if (!enabled(ctx)) return
        val level = (BASE_GAIN * gain * volume(ctx)).coerceIn(0f, 1f)
        if (level <= 0.001f) return
        val voice = ToySounds.voice(sound, random.nextFloat().let {
            ToySounds.MIN_PITCH + it * (ToySounds.MAX_PITCH - ToySounds.MIN_PITCH)
        })
        renderer.execute {
            val clip = cache[voice.key] ?: runCatching { ToySounds.render(voice) }.getOrNull()
                ?.also { cache[voice.key] = it } ?: return@execute
            if (SystemClock.elapsedRealtime() < duckedUntil) return@execute
            // A full queue drops this sound rather than queueing a pop for two seconds' time:
            // a bubble that is already gone must not be heard popping after the fact.
            runCatching { player.execute { emit(clip, level) } }
        }
    }

    /** Two sounds in order, for one event that deserves both. */
    fun playPair(ctx: Context, first: ToySounds.Sound, second: ToySounds.Sound, gain: Float = 1f) {
        play(ctx, first, gain)
        main.postDelayed({ play(ctx, second, gain * 0.8f) }, 90L)
    }

    /**
     * Plays one rendered clip to the end on its own track.
     *
     * The write loop is wrapped in a try because a track can be released from under it when
     * the phone's audio server restarts, which is a normal thing that happens on a phone.
     */
    private fun emit(clip: ToySounds.Clip, level: Float) {
        var track: AudioTrack? = null
        try {
            val bytes = clip.samples.size * 2
            val min = AudioTrack.getMinBufferSize(
                ToySounds.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) return
            val size = maxOf(min, bytes)
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(ToySounds.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(size)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) return
            // Volume is applied by the track, not by rebuilding the clip: volume and gain are
            // fixed points, and the multiplier is a per-sample loop over the whole buffer.
            track.setVolume(level)
            val pcm = clip.samples
            track.play()
            var written = 0
            var stalled = 0
            while (written < pcm.size) {
                val n = track.write(pcm, written, pcm.size - written, AudioTrack.WRITE_NON_BLOCKING)
                if (n <= 0) {
                    // WRITE_NON_BLOCKING returning zero means the buffer is full: wait for the
                    // mixer instead of spinning. Give up if it never drains.
                    if (++stalled > 60) return
                    SystemClock.sleep(4L)
                    continue
                }
                stalled = 0
                written += n
            }
            // Let the tail reach the speaker before the track is released, or the last few
            // milliseconds of a pop are cut off and it clicks. The track buffers `size` bytes,
            // so what is still in flight is the whole queued buffer, not just the buffer
            // minimum — a clip shorter than the queue would otherwise be cut off mid-pop.
            val queuedMs = (size / 2L) * 1_000L / ToySounds.SAMPLE_RATE
            SystemClock.sleep(queuedMs.coerceIn(20L, 400L))
        } catch (_: Throwable) {
            // A toy sound is never worth crashing a toy for.
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
        }
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        // SONIFICATION, not media: this is a sound effect, so it follows the notification
        // stream and is silenced by a silent ringer rather than talking over a muted phone.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** True when the ringer is silent, so a toy can play on vibrate but stay quiet on silent. */
    fun ringerMuted(ctx: Context): Boolean = runCatching {
        when (ctx.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> true
            else -> false
        }
    }.getOrDefault(false)

    /** Whether a sound should be heard right now: the switch, the ringer, and the duck. */
    fun audibleNow(ctx: Context): Boolean =
        enabled(ctx) && !ringerMuted(ctx) && SystemClock.elapsedRealtime() >= duckedUntil

    /** Diagnostics: what the switch is doing, without saying anything about the user. */
    fun summary(ctx: Context): String = when {
        !enabled(ctx) -> "off"
        ringerMuted(ctx) -> "on, but the phone is on silent"
        else -> "on · ${(volume(ctx) * 100).toInt()}%"
    }
}
