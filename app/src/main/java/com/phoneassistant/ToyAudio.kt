package com.phoneassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Plays the toy sounds: short effects kept as toy_*.mp3 in res/raw.
 *
 * The files are the whole sound system. Every name below is a slot: put a real
 * recording at that file name and its toy starts using it — nothing else changes.
 * Until a slot holds real audio it is a silent placeholder, so its toy stays quiet.
 *
 *   toy_pop    a bubble popping         toy_clink  ice in a glass
 *   toy_squeak the lemonade handle      toy_drip   a water drop
 *   toy_pour   liquid into a glass      toy_swipe  a quick page swish
 *   toy_glug   drinking down a glass    toy_ding   the stand's service bell
 *   toy_grow   a plant's new leaf       toy_bounce a ball on the floor
 *   toy_chalk  pastel on canvas         toy_spray  the plant mister
 *   toy_splat  a splodge of paint       toy_whoosh something flying past
 *   toy_kick   boot on the soccer ball toy_boing  a spring
 *   toy_goal   the goal fanfare         toy_tap    a light wooden tap
 *   toy_whistle the referee             toy_sparkle a finished painting
 *
 * Three things matter here, and they are all about not being annoying:
 *
 * - **Short and quiet.** Effects are mixed well below speech, and [duck] can hush
 *   the whole set when Pip is talking.
 * - **Off is one switch.** [enabled] is read per play, so turning toy sounds off
 *   stops the next one. A silent ringer stays silent ([ringerMuted]); vibrate
 *   still plays, because the sounds ride the media stream.
 * - **Nothing blocks the caller.** Sounds are preloaded into one SoundPool;
 *   playing is a single call, and a phone with no free mixer channel loses a
 *   squeak, not a tap.
 */
internal object ToyAudio {
    private const val PREFS = "toy_sounds"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME = "volume"

    /** Mixing at about a quarter of full scale: these are effects, not audio content. */
    private const val BASE_GAIN = 0.30f

    /** Small per-play rate jitter, so the fortieth bubble pop is not machine-stamped. */
    private const val MIN_RATE = 0.94f
    private const val MAX_RATE = 1.06f

    /** The sounds and the files that hold them. The names are the upload contract. */
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
        WHOOSH(R.raw.toy_whoosh)
    }

    /** Read per play, so the switch takes effect immediately. */
    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

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

    private val main = Handler(Looper.getMainLooper())
    private val random = java.util.Random()

    private var pool: SoundPool? = null
    private val ids = mutableMapOf<Sound, Int>()
    private val loaded = mutableSetOf<Int>()

    /**
     * One pool for the process, preloaded on first use. A slot whose file does
     * not decode (or is still loading) simply does not play: a toy missing its
     * sound loses a squeak, never a frame.
     */
    @Synchronized
    private fun pool(ctx: Context): SoundPool =
        pool ?: SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // GAME, riding the media stream. The silent-ringer rule is
                    // enforced in play(), where it is visible and tested.
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { fresh ->
                pool = fresh
                fresh.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0) synchronized(this) { loaded.add(sampleId) }
                }
                for (sound in Sound.entries) {
                    ids[sound] = fresh.load(ctx.applicationContext, sound.raw, 1)
                }
            }

    /** Books a fresh variation and plays it; the file is already in memory. */
    fun play(ctx: Context, sound: Sound, gain: Float = 1f) {
        if (!enabled(ctx)) return
        if (ringerMuted(ctx)) return
        if (SystemClock.elapsedRealtime() < duckedUntil) return
        val level = (BASE_GAIN * gain * volume(ctx)).coerceIn(0f, 1f)
        if (level <= 0.001f) return
        val fresh = pool(ctx)
        val id = synchronized(this) { ids[sound] ?: return }
        if (synchronized(this) { id !in loaded }) return
        val rate = MIN_RATE + random.nextFloat() * (MAX_RATE - MIN_RATE)
        runCatching { fresh.play(id, level, level, 1, 0, rate) }
            .onFailure { Diag.caught("toy audio · play", it) }
    }

    /** Two sounds in order, for one event that deserves both. */
    fun playPair(ctx: Context, first: Sound, second: Sound, gain: Float = 1f) {
        play(ctx, first, gain)
        main.postDelayed({ play(ctx, second, gain * 0.8f) }, 90L)
    }

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
