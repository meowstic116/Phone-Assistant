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
    private fun tanh(x: Float): Float = kotlin.math.tanh(x)

    /** One full cycle, so the recipes read as frequencies rather than as arithmetic. */
    private val TAU = (2.0 * PI).toFloat()

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
        // A bubble going. A click, a body that drops in pitch, and a breath of spray: 40 of
        // these in a row must not turn into a chord.
        Sound.POP to Recipe(0.075f, "pop"),
        // The lemonade stand's handle. Wet rubber rising against the glass, with grit.
        Sound.SQUEAK to Recipe(0.16f, "squeak"),
        // Liquid into a glass: splash bands climbing as it fills, bubbles underneath.
        Sound.POUR to Recipe(0.34f, "pour"),
        // The last of a glass going down a throat. Two low blorps.
        Sound.GLUG to Recipe(0.22f, "glug"),
        // A plant putting out a leaf. A soft three-note chime, deliberately quiet.
        Sound.GROW to Recipe(0.42f, "grow"),
        // Pastel on canvas: dry banded grit, almost no tail.
        Sound.CHALK to Recipe(0.09f, "chalk"),
        // A flattened splodge of colour: wetter and lower than the stroke.
        Sound.SPLAT to Recipe(0.14f, "splat"),
        // Boot on ball: a click, a thump that drops, and a little leather.
        Sound.KICK to Recipe(0.11f, "kick"),
        // A goal. Three brass notes climbing, the smallest trumpet the phone can manage.
        Sound.GOAL to Recipe(0.52f, "goal"),
        // The referee: two close high tones that beat against each other.
        Sound.WHISTLE to Recipe(0.30f, "whistle"),
        // Rubber band, for pickup and put-down. A twang that unwinds as it falls.
        Sound.BOING to Recipe(0.20f, "boing"),
        // The neutral click of a control: a tick and two quick wood modes.
        Sound.TAP to Recipe(0.045f, "tap"),
        // A finished painting, or a plant blooming. Four tiny bells, at a hush.
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
        var phase = 0f
        var lowpass = 0f
        // Two ringing bands available to any recipe, for splashes, thuds and breath. State
        // lives here rather than in the loop, so rendering stays allocation-free per sample.
        val bandA = Bandpass()
        val bandB = Bandpass()
        for (i in 0 until samples) {
            val t = i.toFloat() / SAMPLE_RATE
            val progress = i / samples.toFloat()
            val value: Float = when (recipe.shape) {
                "pop" -> {
                    // The skin breaking, a body that drops in pitch as it dies, and a breath
                    // of spray. Layered rather than pure: a single tone reads as a beep.
                    val body = sin(TAU * (1_150f * (1f - progress * 0.72f)) * t) * exp(-t * 52f)
                    val click = if (t < 0.0035f) noise.next() * (1f - t / 0.0035f) else 0f
                    val spray = bandA.step(noise.next(), 2_700f, 1.1f) * exp(-t * 95f)
                    body * 0.78f + click * 0.85f + spray * 0.9f
                }
                "squeak" -> {
                    // Wet rubber: a rising tone with a wobble, odd harmonics for the rubber,
                    // and a band of grit for the glass it is turning against.
                    val freq = 560f + progress * 620f + sin(TAU * 31f * t) * 34f
                    phase += freq / SAMPLE_RATE
                    val tone = sin(TAU * phase) + 0.22f * sin(TAU * 3f * phase) + 0.08f * sin(TAU * 5f * phase)
                    val rub = bandA.step(noise.next(), 1_350f, 0.9f) * 0.35f
                    val env = sin(PI.toFloat() * progress).pow(0.65f)
                    (tone * 0.42f + rub) * env
                }
                "pour" -> {
                    // Liquid is noise with structure: two splash bands climbing as the glass
                    // fills, thin air above them, and the body of the stream below.
                    val cutoff = 0.05f + progress * 0.2f
                    lowpass += (noise.next() - lowpass) * cutoff
                    val splash = bandA.step(noise.next(), 850f + progress * 2_250f, 1.6f)
                    val air = bandB.step(noise.next(), 3_400f + progress * 1_200f, 0.8f) * 0.4f
                    val env = sin(PI.toFloat() * progress).pow(0.9f)
                    tanh((lowpass * 1.6f + splash * 1.2f + air * 0.6f) * env * 1.15f) * 0.9f
                }
                "glug" -> {
                    // Two blorps with a breath between them, each a pitch drop with a wet edge.
                    val second = progress >= 0.52f
                    val seg = if (second) (progress - 0.52f) / 0.48f else progress / 0.48f
                    val inGap = !second && progress >= 0.48f
                    val base = if (second) 158f else 205f
                    val env = if (inGap) 0f else exp(-seg * 6.2f)
                    val f = base + seg * 55f
                    val tone = sin(TAU * f * t) + 0.45f * sin(TAU * 2f * f * t)
                    val wet = bandA.step(noise.next(), 420f, 1.4f) * exp(-seg * 9f) * 0.6f
                    (tone * 0.5f + wet) * env
                }
                "grow" -> {
                    // A quiet three-note chime. Bell partials that die faster than their
                    // fundamental, so each note has a shimmer on top of its own tone.
                    val note = (progress * 3f).toInt().coerceAtMost(2)
                    val f = floatArrayOf(523.25f, 659.25f, 783.99f)[note]
                    val local = progress * 3f - note
                    val bell = sin(TAU * f * t) +
                        0.55f * exp(-local * 8f) * sin(TAU * 2.02f * f * t) +
                        0.28f * exp(-local * 12f) * sin(TAU * 3.01f * f * t)
                    val env = (local / 0.06f).coerceAtMost(1f) * exp(-local * 5.5f)
                    bell * env * 0.34f
                }
                "chalk" -> {
                    // Dry: banded grit with a fast fall and two resonances, so it reads as
                    // powder dragged across a toothy surface rather than as white noise.
                    val grain = bandA.step(noise.next(), 2_350f, 1.3f)
                    val dust = bandB.step(noise.next(), 4_200f, 0.9f) * 0.5f
                    val env = exp(-t * 64f) * (t / 0.0022f).coerceAtMost(1f)
                    (grain + dust) * env * 1.15f
                }
                "splat" -> {
                    // The same idea, wetter and lower: soft-clipped noise over a dropping body,
                    // so a splodge of paint lands with weight.
                    val wet = tanh(bandA.step(noise.next(), 780f, 0.8f) * 2.2f)
                    val body = sin(TAU * (165f - progress * 55f) * t) * 0.55f
                    val env = exp(-t * 30f) * (t / 0.003f).coerceAtMost(1f)
                    (wet * 0.8f + body) * env
                }
                "kick" -> {
                    // Boot on ball: a click, a thump that drops, and just enough leather to
                    // not be a drum machine. Soft-clipped so it punches through the mix.
                    val thump = sin(TAU * (145f * (1f - progress * 0.62f)) * t) * exp(-t * 32f)
                    val click = if (t < 0.0022f) noise.next() * (1f - t / 0.0022f) else 0f
                    val grit = bandA.step(noise.next(), 1_600f, 0.8f) * exp(-t * 210f) * 0.5f
                    tanh((thump + click * 0.7f + grit) * 1.35f) * 0.85f
                }
                "goal" -> {
                    // Three notes climbing on the smallest brass section the phone can
                    // manage: a harmonic stack per note, a breath of vibrato, no gaps.
                    val note = (progress * 3f).toInt().coerceAtMost(2)
                    val freq = floatArrayOf(392f, 523.25f, 659.25f)[note]
                    val local = progress * 3f - note
                    phase += freq * (1f + 0.004f * sin(TAU * 5.2f * t)) / SAMPLE_RATE
                    val brass = sin(TAU * phase) + 0.52f * sin(TAU * 2f * phase) +
                        0.33f * sin(TAU * 3f * phase) + 0.18f * sin(TAU * 4f * phase) +
                        0.09f * sin(TAU * 5f * phase)
                    val env = (local / 0.045f).coerceAtMost(1f) * (1f - local * 0.55f)
                    brass * env * (if (note == 2) 0.185f else 0.16f)
                }
                "whistle" -> {
                    // A pea whistle: two close high tones that beat against each other, a
                    // flutter of air, and pitch that rises into the note and falls out.
                    val rise = (progress / 0.12f).coerceAtMost(1f)
                    val fall = if (progress > 0.86f) (1f - progress) / 0.14f else 1f
                    val flutter = 0.82f + 0.18f * sin(TAU * 8.2f * t)
                    val tone = sin(TAU * (2_035f * rise.coerceAtLeast(0.15f)) * t) + 0.85f * sin(TAU * 2_068f * t)
                    val breath = bandA.step(noise.next(), 3_100f, 0.8f) * 0.22f
                    (tone * 0.34f + breath) * flutter * rise * fall *
                        sin(PI.toFloat() * progress).pow(0.25f)
                }
                "boing" -> {
                    // The spring: pitch falls while a vibrato unwinds, with harmonics for
                    // twang. The wobble dies with the note, so it never turns into a warble.
                    val wobble = 1f + 0.20f * exp(-t * 8f) * sin(TAU * 15.5f * t)
                    phase += (860f * (1f - progress * 0.52f)) * wobble / SAMPLE_RATE
                    val tone = sin(TAU * phase) + 0.36f * sin(TAU * 2f * phase) + 0.14f * sin(TAU * 3f * phase)
                    tone * 0.42f * exp(-t * 11f)
                }
                "tap" -> {
                    // Feet on the sheet, a button pressed: a tick and two quick wood modes
                    // that die before they can ring.
                    val tick = if (t < 0.0016f) noise.next() * (1f - t / 0.0016f) else 0f
                    val mode1 = sin(TAU * 1_180f * t) * exp(-t * 190f)
                    val mode2 = sin(TAU * 2_540f * t) * exp(-t * 330f) * 0.5f
                    tick * 0.55f + mode1 * 0.62f + mode2
                }
                "sparkle" -> {
                    // Four tiny bells in a row at a hush, each with inharmonic partials that
                    // die faster than the note, plus a whisper of glitter under them.
                    val slot = (progress * 4f).toInt().coerceAtMost(3)
                    val local = progress * 4f - slot
                    val f = floatArrayOf(1_046.5f, 1_318.5f, 1_568f, 2_093f)[slot]
                    val bell = sin(TAU * f * t) +
                        0.5f * exp(-local * 6.5f) * sin(TAU * 2.76f * f * t) +
                        0.22f * exp(-local * 9f) * sin(TAU * 5.4f * f * t)
                    val shimmer = bandA.step(noise.next(), 5_200f, 0.7f) * exp(-local * 9f) * 0.18f
                    (bell * exp(-local * 4.4f) + shimmer) * 0.2f
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
     * A Chamberlin state-variable band-pass, used to give noise a center and a Q: splashes,
     * thuds, breath and grit are all just noise with a resonant shape over it.
     */
    private class Bandpass {
        private var low = 0f
        private var band = 0f
        fun step(x: Float, freq: Float, q: Float): Float {
            val f = 2f * sin((PI * freq / SAMPLE_RATE).toFloat())
            val high = x - low - band / q
            band += f * high
            low += f * band
            return band
        }
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

    /** The RMS of a window, used by tests to check an envelope actually decays. */
    fun rms(clip: Clip, from: Float, to: Float): Double {
        val start = max(0, (clip.samples.size * from).toInt())
        val end = min(clip.samples.size, (clip.samples.size * to).toInt())
        if (end <= start) return 0.0
        var sum = 0.0
        for (i in start until end) sum += clip.samples[i].toDouble() * clip.samples[i]
        return sqrt(sum / (end - start))
    }
}
