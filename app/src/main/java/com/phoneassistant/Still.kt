package com.phoneassistant

import android.content.Context

/**
 * Standing still: the user has told Pip to stay put, either for a length of time or until they
 * say otherwise.
 *
 * Stored as a wall-clock deadline, the same way a toy break is, so the clock is the only truth:
 * a service restart, a reboot, or a phone that slept through the end of a hold are all just
 * another reading of the same number. A hold with no length is stored as [FOREVER], because
 * "stand still" without a time means exactly that — the end of it is the user saying he can go.
 *
 * The hold is enforced by the overlay, not by the model: the message that asks for it also goes
 * to the brain for a line, but a brain that is unreachable must not leave him walking about
 * when he was told to stop.
 */
object Still {

    /** A hold with no end until the user gives one. */
    const val FOREVER = Long.MAX_VALUE

    private const val PREFS = "still"
    private const val KEY = "until"

    /** Said when a hold he was given a time for runs out on its own. */
    val DONE_LINES = listOf(
        "*shifts* right. moving again.",
        "back to my rounds. *stretches a leg*",
        "moving again. I was getting very good at standing.",
        "hold's over. it suited me, actually."
    )

    /** Called after a hold starts or is cleared, so the overlay re-reads it. */
    var onChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When he may move again: 0 when he is free, [FOREVER] when he has not been told. */
    fun until(ctx: Context): Long = prefs(ctx).getLong(KEY, 0L)

    fun active(ctx: Context): Boolean {
        val deadline = until(ctx)
        return deadline == FOREVER || deadline > System.currentTimeMillis()
    }

    /**
     * How long is left of the hold, in words, or null when it has no end: there is no number to
     * give then, and "until you say" is the honest answer.
     */
    fun describe(ctx: Context): String? {
        val deadline = until(ctx)
        if (deadline == FOREVER) return null
        val left = deadline - System.currentTimeMillis()
        return if (left > 0L) Toys.describeDuration(left) else null
    }

    /** Holds him still, for [length] or with null for as long as the user wants. */
    fun hold(ctx: Context, length: Long?) {
        val deadline = if (length == null) FOREVER else System.currentTimeMillis() + length
        prefs(ctx).edit().putLong(KEY, deadline).apply()
        onChanged?.invoke()
    }

    /**
     * Lets him go. The deadline is the only state there is, so removing it is the whole of it —
     * nothing else has to remember that a hold ever happened.
     */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
        onChanged?.invoke()
    }

    // ------------------------------------------------------------------ matching

    /** Ways of telling him to stay put. Only ever read as a command aimed at him. */
    private val standPhrases = listOf(
        "stand still", "stay still", "hold still", "keep still", "sit still",
        "stand right there", "stay right there", "stay there", "stay put",
        "stop moving", "stop wandering", "stop walking", "stop strolling",
        "don't move", "dont move", "do not move", "don't wander", "dont wander",
        "don't budge", "dont budge", "freeze", "hold your position", "hold position"
    )

    /** Ways of letting him go again. */
    private val movePhrases = listOf(
        "you can move", "you may move", "you can walk", "you can wander",
        "can move again", "move again", "you're free", "youre free", "you are free",
        "off you go", "wander again", "start moving", "unfreeze", "release you",
        "hold's over", "holds over", "you can go", "back to it", "carry on"
    )

    /** Ways a sentence starts when the person is talking about themselves, not at Pip. */
    private val selfWords = setOf(
        "i", "i'm", "im", "i've", "ive", "i'd", "id", "my", "me", "we", "we're", "our"
    )

    fun asksToStand(raw: String): Boolean {
        val text = raw.lowercase().trim()
        if (text.length > 160) return false
        if (standPhrases.none { text.contains(it) }) return false
        // "their screen doesn't stay still" is about them, not an instruction.
        if (text.contains("you") || text.contains("pip") || text.contains("please")) return true
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val first = words.firstOrNull()?.trim(',', '.', '!', '?')
        return first !in selfWords && words.size <= 10
    }

    fun asksToMove(raw: String): Boolean {
        val text = raw.lowercase().trim()
        if (text.length > 160) return false
        return movePhrases.any { text.contains(it) }
    }

    /**
     * Reads a message as a hold starting or ending, and returns the note for Pip's prompt so his
     * line matches what his body is already doing — or null when it says neither.
     */
    fun reactTo(host: OverlayService, raw: String): String? {
        val ctx = host.stageContext()
        if (asksToMove(raw) && active(ctx)) {
            clear(ctx)
            host.feel(PetView.Mood.HAPPY, 1800L)
            return "they said you can move again, so you are free to wander about the screen. " +
                "say one short, dry line about it."
        }
        if (!asksToStand(raw)) return null
        val length = Toys.durationIn(raw)
        hold(ctx, length)
        // Stopped here, before the brain has said anything: the holding still is the part that
        // matters, and it must not depend on a reply arriving.
        host.standStillNow()
        host.feel(PetView.Mood.NEUTRAL, 1500L)
        val forHowLong = if (length == null) {
            "until they tell you otherwise"
        } else {
            "for ${Toys.describeDuration(length)}"
        }
        return "they asked you to stand still $forHowLong, so you have stopped where you were " +
            "and are not wandering about the screen. you can still talk and still do things " +
            "they ask for. say one short, dry line about it."
    }
}
