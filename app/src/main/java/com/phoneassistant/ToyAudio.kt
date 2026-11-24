package com.phoneassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Plays the toy sounds. The clips in `res/raw/` are generated effects — no recordings and no
 * downloads, and no synthesis on the phone; the AudioLDM pipeline in the handoff that produced
 * this file could not run here because its checkpoint host was unreachable.
 *
 * Three things matter here, and they are all about not being annoying:
 *
 * - **Short and quiet.** Every clip is a fraction of a second, mixed well below speech.
 *   [duck] can hush the whole set while Pip talks, because a squeak over his own sentence is
 *   worse than no squeak.
 * - **Off is one switch.** [enabled] is read per play, so turning toy sounds off stops the next
 *   one rather than after a restart. The switch starts on — a toy box that is silent until
 *   someone finds the setting is a toy box that seems broken — and a silent ringer stays
 *   silent because [play] asks [ringerMuted] before touching the speaker.
 * - **One buffer per sound, never one per press.** [SoundPool] decodes each file once; the
 *   0.94–1.06 rate jitter applied at play time is what keeps the fortieth pop from sounding
 *   bit-identical to the first. Nothing is rendered or allocated while a toy is being tapped.
 *
 * Failures are swallowed after being reported to Diagnostics: a phone with no free mixer
 * channel loses a squeak, not a tap.
 */
internal object ToyAudio {
    private const val PREFS = "toy_sounds"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME = "volume"

    /** Mixing at about a quarter of full scale: these are effects, not audio content. */
    private const val BASE_GAIN = 0.30f

    /** The same small pitch band the synthesised takes used, now applied as a playback rate. */
    const val MIN_PITCH = 0.94f
    const val MAX_PITCH = 1.06f

    /** A sound family and its one bundled take. New families need a `toy_<name>.mp3` here. */
    enum class Sound(val raw: Int) {
        POP(R.raw.toy_pop),
        SQUEAK(R.raw.toy_squeak),
        POUR(R.raw.toy_pour),
        GLUG(R.raw.toy_glug),
        GROW(R.raw.toy_grow),
        CHALK(R.raw.toy_chalk),
        SPLAT(R.raw.toy_splat),
        KICK(R.raw.toy_kick),
        GOAL(R.raw.toy_goal),
        WHISTLE(R.raw.toy_whistle),
        BOING(R.raw.toy_boing),
        TAP(R.raw.toy_tap),
        SPARKLE(R.raw.toy_sparkle),
        CLINK(R.raw.toy_clink),
        DRIP(R.raw.toy_drip),
        SWIPE(R.raw.toy_swipe),
        DING(R.raw.toy_ding),
        BOUNCE(R.raw.toy_bounce),
        SPRAY(R.raw.toy_spray),
        WHOOSH(R.raw.toy_whoosh);

        /** The resource name, so a test can check that every enum has a matching file. */
        val resourceName: String get() = "toy_${name.lowercase()}"
    }

    private val main = Handler(Looper.getMainLooper())

    /** Pool and load bookkeeping are touched only on the main looper. */
    private var pool: SoundPool? = null
    private val sampleIds = mutableMapOf<Sound, Int>()
    private val ready = mutableSetOf<Sound>()
    /** The first tap can arrive before a decode finishes; hold at most a few takes per sound. */
    private val pending = mutableMapOf<Sound, MutableList<Pair<Float, Float>>>()

    private val random = java.util.Random()

    /** Read per play, so the switch takes effect immediately. */
    fun enabled(ctx: Context): Boolean = ctx.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
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

    /**
     * Decodes every clip once. Calling this at service start means the first tap already has
     * its sound; a call from another thread is posted to the main looper rather than racing
     * the pool's construction.
     */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) createPool(app)
        else main.post { createPool(app) }
    }

    private fun createPool(ctx: Context) {
        if (pool != null) return
        val p = try {
            SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(attributes())
                .build()
        } catch (t: Throwable) {
            Diag.caught("toy audio · pool", t)
            return
        }
        p.setOnLoadCompleteListener { playedPool, sampleId, status ->
            if (status == 0) {
                Sound.entries.firstOrNull { sampleIds[it] == sampleId }?.let { sound ->
                    ready.add(sound)
                    pending.remove(sound)?.forEach { (level, rate) ->
                        runCatching { playedPool.play(sampleId, level, level, 1, 0, rate) }
                            .onFailure { Diag.caught("toy audio · delayed play", it) }
                    }
                }
            }
        }
        for (sound in Sound.entries) sampleIds[sound] = p.load(ctx, sound.raw, 1)
        pool = p
    }

    /** Books a fresh variation. Cheap: playback rate is the only per-press work. */
    fun play(ctx: Context, sound: Sound, gain: Float = 1f) {
        if (!enabled(ctx)) return
        // The switch above is the user's choice; this one is the phone's. Vibrate still plays
        // (the sounds ride the media stream), silent stays silent.
        if (ringerMuted(ctx)) return
        if (SystemClock.elapsedRealtime() < duckedUntil) return
        val level = (BASE_GAIN * gain * volume(ctx)).coerceIn(0f, 1f)
        if (level <= 0.001f) return
        prepare(ctx)
        val p = pool ?: return
        val id = sampleIds[sound] ?: return
        val rate = MIN_PITCH + random.nextFloat() * (MAX_PITCH - MIN_PITCH)
        if (sound !in ready) {
            pending.getOrPut(sound) { mutableListOf() }.apply {
                if (size < 4) add(level to rate)
            }
            return
        }
        runCatching { p.play(id, level, level, 1, 0, rate) }
            .onFailure { Diag.caught("toy audio · play", it) }
    }

    /** Two sounds in order, for one event that deserves both. */
    fun playPair(ctx: Context, first: Sound, second: Sound, gain: Float = 1f) {
        play(ctx, first, gain)
        main.postDelayed({ play(ctx, second, gain * 0.8f) }, 90L)
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        // GAME, riding the media stream, because the ringer mutes the system and notification
        // streams in vibrate mode too — which is where phones spend most of their day. The
        // silent-ringer rule is enforced in play() instead, where it is visible and tested:
        // a silent phone is a choice and gets silence, vibrate is not and gets squeaks.
        .setUsage(AudioAttributes.USAGE_GAME)
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
