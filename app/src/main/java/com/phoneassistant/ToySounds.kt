package com.phoneassistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every sound a toy makes, synthesised on the phone.
 *
 * There are no audio files in this project and there will not be: a wav per squeak per toy is
 * half a megabyte of binary in the repository, it has to be licensed, and it cannot vary itself.
 * A few hundred lines of arithmetic instead produce any sound at any pitch at 44.1 kHz, so each
 * one can come out slightly different every time it plays — which is the whole point, because a
 * pop that is bit-identical on the fortieth press stops being a toy and starts being a ringtone.
 *
 * The variation is deliberately *small*: [Voice] stores a pitch in the range 0.94–1.06, so a
 * family of sounds reads as one sound with life in it rather than as an unpredictable one.
 *
 * Everything here is pure: no Android types, no clocks, no I/O. [ToyAudio] turns these samples
 * into playback, and the host tests check the shapes without a device.
 */
object ToySounds {

    /** Float wrappers around [kotlin.math], so the synthesis below reads as arithmetic. */
    private fun sin(x: Float): Float = kotlin.math.sin(x)
    private fun exp(x: Float): Float = kotlin.math.exp(x)

    /** Mono, matching the rest of the app's audio. Toy sounds are short enough to keep it simple. */
    const val SAMPLE_RATE = 44_100

    /** One generated clip, before it becomes an AudioTrack. */
    class Clip(val samples: ShortArray, val seconds: Float)

    /** A sound family and a pitch for one playing of it. */
    class Voice(val sound: Sound, val pitch: Float) {
        /** A stable identity for caching the rendered buffer. */
        val key: String get() = sound.name + "@" + (pitch * 100f).toInt()
    }

    /**
     * The sounds. Grouped by what makes them, not by which toy uses them, because the lemonade
     * squeeze and the plant's watering can share one.
     */
    enum class Sound { POP, SQUEAK, POUR, GLUG, GROW, CHALK, SPLAT, KICK, GOAL, WHISTLE, BOING, TAP, SPARKLE }

    /**
     * How each sound is put together. Kept in one table so the character of the whole toy box
     * can be heard and changed in one place instead of scattered through the painters.
     */
    private class Recipe(
        val length: Float,
        /** Picks the shape below. A string rather than a lambda so the table stays readable. */
        val shape: String
    )

    private val recipes = mapOf(
        // A bubble going. Very short, a click and a falling body, no tail: 40 of these in a row
        // must not turn into a chord.
        Sound.POP to Recipe(0.075f, "pop"),
        // The lemonade stand's handle. A rising squeak, like rubber, with a little grit.
        Sound.SQUEAK to Recipe(0.16f, "squeak"),
        // Liquid into a glass: shaped noise, a slow envelope, rising pitch.
        Sound.POUR to Recipe(0.34f, "pour"),
        // The last of a glass going down a throat. Two low blorps.
        Sound.GLUG to Recipe(0.22f, "glug"),
        // A plant putting out a leaf. A soft rising chime, deliberately quiet.
        Sound.GROW to Recipe(0.42f, "grow"),
        // Pastel on canvas: filtered noise, dry and short.
        Sound.CHALK to Recipe(0.09f, "chalk"),
        // A flattened splodge of colour: a weter chalk.
        Sound.SPLAT to Recipe(0.14f, "splat"),
        // Boot on ball.
        Sound.KICK to Recipe(0.11f, "kick"),
        // A goal. Three rising notes, the smallest trumpet the phone can manage.
        Sound.GOAL to Recipe(0.52f, "goal"),
        // The referee.
        Sound.WHISTLE to Recipe(0.30f, "whistle"),
        // Rubber band, for pickup and put-down.
        Sound.BOING to Recipe(0.20f, "boing"),
        // The neutral click of a control.
        Sound.TAP to Recipe(0.045f, "tap"),
        // A finished painting, or a plant blooming.
        Sound.SPARKLE to Recipe(0.55f, "sparkle")
    )

    fun length(sound: Sound): Float = recipes.getValue(sound).length

    /** The pitch range every variation is drawn from. */
    const val MIN_PITCH = 0.94f
    const val MAX_PITCH = 1.06f

    /** A small deterministic generator, so a test can ask for the same take twice. */
    fun voice(sound: Sound, pitch: Float): Voice =
        Voice(sound, pitch.coerceIn(MIN_PITCH, MAX_PITCH))

    fun voice(sound: Sound, random: kotlin.random.Random): Voice =
        voice(sound, MIN_PITCH + random.nextFloat() * (MAX_PITCH - MIN_PITCH))

    /**
     * Renders [voice] to 16-bit mono samples. The same voice always renders the same bytes,
     * which is what makes the cache worth having.
     */
    fun render(voice: Voice): Clip {
        val recipe = recipes.getValue(voice.sound)
        val samples = (recipe.length * SAMPLE_RATE * voice.pitch).toInt().coerceIn(16, SAMPLE_RATE * 2)
        val out = ShortArray(samples)
        // Seeded from the voice so a cached render is reproducible, and so two different
        // variations do not share their noise.
        val noise = Noise(voice.sound.ordinal * 7919 + (voice.pitch * 1000f).toInt())
        val duration = samples.toFloat() / SAMPLE_RATE
        var phase = 0f
        var lowpass = 0f
        for (i in 0 until samples) {
            val t = i.toFloat() / SAMPLE_RATE
            val progress = i / samples.toFloat()
            val value: Float = when (recipe.shape) {
                "pop" -> {
                    // A click, then a body that drops in pitch as it dies: the classic bubble.
                    val body = sin((2.0 * PI).toFloat() * (900f * (1f - progress * 0.65f)) * t)
                    val click = if (t < 0.004f) noise.next() * (1f - t / 0.004f) else 0f
                    val env = exp(-t * 46f)
                    (body * 0.75f * env + click * 0.5f)
                }
                "squeak" -> {
                    // Rising, with a wobble, and a little noise for the rubber.
                    val freq = 620f + progress * 520f + sin((2.0 * PI * 34.0).toFloat() * t) * 28f
                    phase += freq / SAMPLE_RATE
                    val env = sin(PI * progress).toFloat().pow(0.7f)
                    (sin((2.0 * PI).toFloat() * phase) * 0.55f + noise.next() * 0.06f) * env
                }
                "pour" -> {
                    // Liquid is noise whose brightness rises as the glass fills.
                    val cutoff = 0.05f + progress * 0.22f
                    lowpass += (noise.next() - lowpass) * cutoff
                    val env = sin(PI.toFloat() * progress).pow(1.4f)
                    lowpass * 3.2f * env
                }
                "glug" -> {
                    // Two bubbles, each with its own little rise: glug-glug.
                    val step = if (progress < 0.5f) progress * 2f else (progress - 0.5f) * 2f
                    val base = if (progress < 0.5f) 210f else 165f
                    val env = exp(-step * 7f)
                    (sin((2.0 * PI).toFloat() * (base + step * 90f) * t) * 0.7f * env)
                }
                "grow" -> {
                    // A quiet fifth, arriving late and leaving early.
                    val env = sin(PI.toFloat() * progress).pow(2.2f)
                    val a = sin((2.0 * PI * 523.25).toFloat() * t)
                    val b = sin((2.0 * PI * 783.99).toFloat() * t) * 0.5f
                    ((a + b) * 0.33f * env)
                }
                "chalk" -> {
                    // Dry: noise with a very fast decay, band-passed by a short average.
                    lowpass += (noise.next() - lowpass) * 0.35f
                    val env = exp(-t * 58f)
                    (lowpass * 2.1f * env).toFloat()
                }
                "splat" -> {
                    // The same idea, wetter and lower: a splodge, not a stroke.
                    lowpass += (noise.next() - lowpass) * 0.12f
                    val env = exp(-t * 26f)
                    (lowpass * 2.6f * env + sin((2.0 * PI * 140.0).toFloat() * t) * 0.25f * env)
                }
                "kick" -> {
                    // A thump and a tick: heel on ball.
                    val body = sin((2.0 * PI).toFloat() * (150f * (1f - progress * 0.5f)) * t) * exp(-t * 26f)
                    val tick = noise.next() * exp(-t * 190f) * 0.8f
                    (body + tick).toFloat()
                }
                "goal" -> {
                    // Three notes climbing, joined by a short glide.
                    val note = (progress * 3f).toInt().coerceAtMost(2)
                    val freq = floatArrayOf(392f, 523.25f, 659.25f)[note]
                    val local = (progress * 3f) - note
                    val env = sin(PI.toFloat() * local).pow(0.55f)
                    ((sin((2.0 * PI).toFloat() * freq * t) * 0.6f + sin((2.0 * PI).toFloat() * freq * 2.0f * t) * 0.2f) * env)
                }
                "whistle" -> {
                    // A pea whistle: one high, almost pure tone with a breath of noise.
                    val env = sin(PI.toFloat() * progress).pow(0.4f)
                    (sin((2.0 * PI).toFloat() * (2_050f + progress * 90f) * t) * 0.5f * env +
                        noise.next() * 0.04f * env).toFloat()
                }
                "boing" -> {
                    // Pitch drops, amplitude wobbles: the spring.
                    val freq = 780f * (1f - progress * 0.45f)
                    val wobble = 1f + 0.18f * sin((2.0 * PI * 19.0).toFloat() * t)
                    val env = exp(-t * 13f)
                    (sin((2.0 * PI).toFloat() * freq * wobble * t) * 0.62f * env)
                }
                "tap" -> {
                    val env = exp(-t * 170f)
                    (noise.next() * 0.5f * env + sin((2.0 * PI * 1_100.0).toFloat() * t) * 0.3f * env)
                }
                "sparkle" -> {
                    // Four tiny bells, spaced out, at a hush.
                    val slot = (progress * 4f).toInt().coerceAtMost(3)
                    val start = slot * 0.25f
                    if (progress * 4f - slot > 0.45f) 0f
                    else {
                        val freq = floatArrayOf(1_046.5f, 1_318.5f, 1_568f, 2_093f)[slot]
                        val env = exp(-(t - start) * 16f)
                        (sin((2.0 * PI).toFloat() * freq * t) * 0.22f * env)
                    }
                }
                else -> 0f
            }
            out[i] = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        // A 4 ms fade at each end. A pop that starts mid-board clicks on the speaker, and a
        // click is a different sound from the one that was wanted.
        fade(out)
        return Clip(out, samples.toFloat() / SAMPLE_RATE)
    }

    /**
     * Applies the ending fade, so a waveform that stops at full amplitude does not click.
     * In place: these arrays are the audio buffer, and copying every one is waste.
     */
    private fun fade(samples: ShortArray) {
        val edge = min(SAMPLE_RATE / 250, samples.size / 2)
        if (edge <= 0) return
        for (i in 0 until edge) {
            val gain = i.toFloat() / edge
            samples[i] = (samples[i] * gain).toInt().toShort()
            val j = samples.size - 1 - i
            samples[j] = (samples[j] * gain).toInt().toShort()
        }
    }

    /** Peak amplitude, so a test can tell a sound from a silence without a speaker. */
    fun peak(clip: Clip): Int = clip.samples.maxOfOrNull { abs(it.toInt()) } ?: 0

    /** Whether the clip actually makes noise rather than being a zeroed array. */
    fun audible(clip: Clip): Boolean = peak(clip) > 300

    /**
     * A tiny xorshift generator. `java.util.Random` would do, but this is one line, has no
     * synchronisation, and gives the same sequence for the same seed on every JVM.
     */
    private class Noise(seed: Int) {
        private var state = if (seed == 0) 0x9E3779B9.toInt() else seed
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state / Int.MAX_VALUE.toFloat()
        }
    }

    /** A short hush, used when a toy needs a gap between two sounds. */
    fun silence(ms: Int): Clip =
        Clip(ShortArray(SAMPLE_RATE * ms / 1000), ms / 1000f)

    /**
     * Concatenates clips, so one toy event can be a squeak then a pour without two players.
     * The fade at each join keeps the seam from clicking.
     */
    fun join(vararg clips: Clip): Clip {
        val total = clips.sumOf { it.samples.size }
        val out = ShortArray(total)
        var at = 0
        for (clip in clips) {
            clip.samples.copyInto(out, at)
            at += clip.samples.size
        }
        fade(out)
        return Clip(out, total.toFloat() / SAMPLE_RATE)
    }

    /** How loud, given a toy's own sense of itself, clamped to something a phone can play. */
    fun gain(base: Float, jitter: Float): Float = (base * (1f - jitter) + base * jitter * 1.0f).let { it }
        .coerceIn(0.05f, 1f)

    /** The RMS of a window, used by tests to check an envelope actually decays. */
    fun rms(clip: Clip, from: Float, to: Float): Double {
        val start = max(0, (clip.samples.size * from).toInt())
        val end = min(clip.samples.size, (clip.samples.size * to).toInt())
        if (end <= start) return 0.0
        var sum = 0.0
        for (i in start until end) sum += clip.samples[i].toDouble() * clip.samples[i]
        return sqrt(sum / (end - start))
    }

    /** The first index above [threshold], or -1: lets a test prove a clip is not silent late. */
    fun firstAbove(clip: Clip, threshold: Int): Int = clip.samples.indexOfFirst { abs(it.toInt()) > threshold }
}
