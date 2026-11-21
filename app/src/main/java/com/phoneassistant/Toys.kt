package com.phoneassistant

/** Pip’s props and opt-in toy activities. */
enum class Toy(val label: String, private val words: List<String>, val spontaneousAnywhere: Boolean = true) {
    LEMONADE(
        "Lemonade stand",
        // Names of the thing itself. "a glass", "the stand" and "thirsty" were too loose:
        // they turned up in sentences that had nothing to do with him.
        listOf("lemonade", "lemonade stand", "lemon drink", "lemon", "fifty cents")
    ),
    PAINTING(
        "Canvas",
        listOf("paint", "canvas", "draw")
    ),
    SOCCER(
        "Soccer",
        listOf("soccer", "football", "kickabout", "kick a ball", "ball", "goal", "penalty")
    ),
    BUBBLES(
        "Bubble wrap",
        listOf("bubble", "bubble wrap", "bubbles")
    ),
    PLANT(
        "Plant",
        listOf("plant", "flower", "garden", "water it")
    ),
    DOOMSCROLL("Doomscroll", listOf("doomscroll", "doom scroll"), spontaneousAnywhere = false);

    fun mentionedIn(text: String): Boolean = words.any { text.contains(it) }

    /** Whether this prop belongs to this toy. */
    fun owns(prop: Prop): Boolean = when (this) {
        LEMONADE -> prop is LemonadeStand || prop is LemonadeGlass
        PAINTING -> prop is Painting
        SOCCER -> prop is Goal || prop is Ball || prop is ScoreBoard
        BUBBLES -> prop is BubbleWrap
        PLANT -> prop is Plant
        DOOMSCROLL -> false
    }
}

/**
 * The toy box: the single place that decides how a toy arrives, what Pip says about it,
 * how long he is on a break for, and how he takes criticism.
 */
object Toys {

    /** What came of asking for a toy: whether it happened, and how Pip should describe it. */
    data class Outcome(val ok: Boolean, val note: String)

    /** Said out loud, with no API call, the moment a toy break runs out. */
    val BREAK_OVER_LINES = listOf(
        "*stretches* break's over. it was fine.",
        "time's up. toys allowed again. I waited quietly, like a professional.",
        "break finished. *cracks tiny knuckles* so. what now.",
        "I'm back. technically I never left.",
        "break's over. I have one small idea."
    )

    private val requestVerbs = listOf(
        "bring", "open", "set up", "start", "play", "give", "make", "pour", "want",
        "can i", "can we", "can you", "could i", "could we", "could you", "would you",
        "let's", "lets ", "show me", "i'd like", "id like", "please", "put out", "get the"
    )

    private val awayPhrases = listOf(
        "put it away", "put that away", "put everything away", "clean that up",
        "clean this up", "clean it up", "clear it", "clear the stand", "clear your stand",
        "get rid of it", "pack it up", "take it away", "take it down",
        "take the stand down", "stop playing", "tidy up", "put it all away", "away please",
        "bin it", "chuck it", "close the stand", "shut the stand"
    )

    /**
     * What a cleanup verb has to point at before it counts. "clean up" on its own is usually
     * about their kitchen; a bare "clean up your room" must not empty his toy box either.
     */
    private val cleanTargets = Regex("\\b(everything|toys?|them|stand|counter)\\b")

    /** Unambiguously about his toys, so they count however the sentence is phrased. */
    private val quietCores = listOf(
        "no toys", "no games", "no more toys", "no more games", "no playing",
        "stop playing", "put your toys", "toys away", "stop the toys"
    )

    /**
     * Only counts when it is aimed at him. "I need a break from work" is about their day,
     * not a request to pack the lemonade stand away.
     */
    private val quietSoft = listOf(
        "take a break", "not now", "not right now", "leave me alone", "peace and quiet",
        "quiet please", "don't play", "dont play", "give me a minute", "chill out",
        "settle down"
    )

    /** Ways a sentence starts when the person is talking about themselves, not at Pip. */
    private val selfWords = setOf(
        "i", "i'm", "im", "i've", "ive", "i'd", "id", "my", "me", "we", "we're",
        "weve", "we've", "our"
    )

    private val resumePhrases = listOf(
        "you can play", "play again", "bring your toys back", "toys back", "break's over",
        "breaks over", "you're allowed", "youre allowed", "you can come back out",
        "you can make things", "back to it"
    )

    private val dislikePhrases = listOf(
        "don't like", "dont like", "do not like", "hate", "dislike", "gross", "yuck",
        "awful", "terrible", "ugly", "not good", "bad job", "the worst"
    )

    private val dislikeNouns = listOf(
        "lemonade", "drink", "painting", "paint", "art", "drawing", "plant", "game",
        "toy", "it", "that", "this"
    )

    // ------------------------------------------------------------------ matching

    /** Which toy, if any, a message is asking for. Conservative on purpose. */
    fun fromText(raw: String): Toy? {
        val text = raw.lowercase()
        if (text.length > 140) return null
        for (toy in Toy.values()) {
            // Feed commands have their own strict parser and job lifecycle.
            if (toy == Toy.DOOMSCROLL || !toy.mentionedIn(text)) continue
            val words = text.trim().split(Regex("\\s+")).size
            val asking = requestVerbs.any { text.contains(it) } || words <= 2
            if (asking) return toy
        }
        // "open your stand", "set up the stand": an imperative aimed at him, naming it the way
        // people do. Scoped to the verbs that mean putting it there, so "move the stand" or
        // "fix the stand" is not a lemonade request.
        val setsUp = Regex("\\b(open|set up|bring out|put out)\\b").containsMatchIn(text)
        if (setsUp && (text.contains("your stand") || text.contains("the stand"))) {
            return Toy.LEMONADE
        }
        // "pour me a glass", "pour a glass": how they actually ask. Only when pour opens the
        // message, there is something to pour into, and it is a short order — so a story like
        // "pour the milk into a glass" does not open the stand.
        val count = text.trim().split(Regex("\\s+")).size
        val pourFirst = Regex("^pour\\b").containsMatchIn(text)
        val intoGlass = text.contains("glass") || text.contains("drink")
        val addressed = count <= 4 || text.contains(" me ") || text.contains(" us ")
        if (pourFirst && intoGlass && addressed) return Toy.LEMONADE
        return null
    }

    /** Which toy a message names, without needing a request verb. */
    fun toyNamedIn(raw: String): Toy? {
        val text = raw.lowercase()
        return Toy.values().firstOrNull { it.mentionedIn(text) }
    }

    fun wantsAway(raw: String): Boolean {
        val text = raw.lowercase()
        if (awayPhrases.any { text.contains(it) }) return true
        // "put the plant away", "pack the goals away": any putting verb plus "away".
        if (text.contains("away") &&
            Regex("\\b(put|pack|take|clear|tidy|shove|hide)\\b").containsMatchIn(text)
        ) {
            return true
        }
        // "clean up your stand", "tidy the toys": a cleanup verb aimed at something of his.
        val cleaning = Regex("\\b(clean|clear|tidy|bin|chuck|close|shut)\\b").containsMatchIn(text)
        return cleaning && cleanTargets.containsMatchIn(text)
    }

    /**
     * Which toy a cleanup is for: one it names, or the stand by its other names. Scoped to
     * cleanup requests, so "the stand" stays useless as a reason to open one.
     */
    private fun awayTarget(text: String): Toy? =
        toyNamedIn(text) ?: if (text.contains("stand") || text.contains("counter")) {
            Toy.LEMONADE
        } else {
            null
        }

    fun asksForQuiet(raw: String): Boolean {
        val text = raw.lowercase().trim()
        if (quietCores.any { text.contains(it) }) return true
        if (quietSoft.none { text.contains(it) }) return false

        // "can you chill out", "leave me alone for ten minutes"
        if (text.contains("you") || text.contains("your") || text.contains("pip")) return true
        if (text.contains("please")) return true

        // A bare instruction ("take a break", "not now") is aimed at him; "I need to
        // chill out" is not.
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val first = words.firstOrNull()?.trim(',', '.', '!', '?')
        return words.size <= 8 && first !in selfWords
    }

    fun asksToResume(raw: String): Boolean {
        val text = raw.lowercase()
        return resumePhrases.any { text.contains(it) }
    }

    fun dislikesIt(raw: String): Boolean {
        val text = raw.lowercase()
        return dislikePhrases.any { text.contains(it) } && dislikeNouns.any { text.contains(it) }
    }

    /**
     * A length of time a message names, or null when it names none. Shared by the toy break and
     * by standing still, which are asked for in the same words but mean different things when
     * no length is given: half an hour of no toys, or no wandering until they say otherwise.
     */
    fun durationIn(raw: String): Long? {
        val text = raw.lowercase()
        TimedRequest.numericDuration(text)?.let { return it }
        return when {
            text.contains("half an hour") || text.contains("half hour") -> 30 * 60_000L
            text.contains("a few hours") -> 3 * 3_600_000L
            Regex("\\b(an?|one) hour").containsMatchIn(text) -> 3_600_000L
            text.contains("rest of the day") || text.contains("all day") ||
                text.contains("all night") || text.contains("tonight") ||
                text.contains("evening") -> 8 * 3_600_000L
            text.contains("a couple of minutes") -> 2 * 60_000L
            text.contains("a few minutes") -> 5 * 60_000L
            Regex("\\b(an?|one) minute").containsMatchIn(text) -> 60_000L
            else -> null
        }
    }

    /** How long a break they asked for. Half an hour when they did not say. */
    fun quietLength(raw: String): Long = durationIn(raw) ?: run {
        // "for now" is a break's own way of saying "a while": it is not a length, so it is
        // read here rather than in [durationIn], where standing still would inherit it and
        // stop meaning "until I say otherwise".
        val text = raw.lowercase()
        when {
            text.contains("not now") || text.contains("not right now") -> 15 * 60_000L
            text.contains("a bit") || text.contains("for now") -> 15 * 60_000L
            else -> 30 * 60_000L
        }
    }

    fun describeDuration(ms: Long): String {
        // A minute is the smallest thing the toy break deals in, but a hold can be given in
        // seconds, and calling twenty seconds "a minute" is a promise about the wrong thing.
        if (ms < 60_000L) return "${(ms / 1_000L).coerceAtLeast(1L)} seconds"
        val minutes = ms / 60_000L
        return when {
            minutes >= 120 -> "${minutes / 60} hours"
            minutes >= 55 -> "an hour"
            minutes <= 1 -> "a minute"
            else -> "$minutes minutes"
        }
    }

    // ------------------------------------------------------------------- actions

    /**
     * The one way a toy arrives. Every path — a chat request, a main-screen button, or Pip
     * getting bored — goes through here, so each toy's rules live in one place.
     */
    fun summon(host: OverlayService, toy: Toy): Outcome {
        val ctx = host.stageContext()

        if (!PipSettings.toyAllowed(ctx, toy)) {
            return Outcome(false, "${toy.label} is disabled in Pip settings. Say so; don't pretend it is out.")
        }
        if (Quiet.active(ctx)) {
            host.feel(PetView.Mood.ANNOYED, 2000L)
            return Outcome(
                false,
                "you are on a break from toys for ${describeDuration(Quiet.remaining(ctx))} " +
                    "because they asked you to be, so you can't get " +
                    "${toy.label.lowercase()} out right now. say so dryly."
            )
        }

        val out = host.propsOnScreen().filter { toy.owns(it) }

        return when (toy) {
            Toy.DOOMSCROLL -> Outcome(host.startDoomscrollToy(), "Doomscrolling is a phone job, not a prop.")
            Toy.LEMONADE -> {
                val existing = out.filterIsInstance<LemonadeStand>().firstOrNull()
                val stand = existing ?: openStand(host)
                when {
                    stand == null -> Outcome(
                        false,
                        "you tried to set up your lemonade stand and the screen refused. " +
                            "one dry line about running a business on a phone."
                    )
                    !stand.hasRoom() -> {
                        // He still heads back behind his counter, but there is nothing to pour.
                        stand.takePost()
                        Outcome(
                            false,
                            "they asked for another lemonade and there are no glasses left. " +
                                "say one dry line about the glassware situation."
                        )
                    }
                    else -> {
                        if (!stand.pourNow()) return Outcome(false,
                            "the stand is out but you couldn't put down a glass. Say so; don't say you served one.")
                        Outcome(
                            true,
                            if (existing == null) {
                                "you set up your stand and poured them a lemonade straight " +
                                    "away. the glass is on their screen now — they can drag " +
                                    "it and tap it to drink it — and you are walking behind " +
                                    "the counter."
                            } else {
                                "you poured them another lemonade straight away. the glass is " +
                                    "on their screen now — they can drag it and tap it to " +
                                    "drink it — and you are walking back behind the counter."
                            }
                        )
                    }
                }
            }

            Toy.PAINTING -> if (out.isNotEmpty()) {
                Outcome(
                    false,
                    "they asked you to paint again and there is already a painting out. point " +
                        "at the one you already made."
                )
            } else {
                val pictures = PaintingGallery.pictures(ctx).filter { PaintingGallery.enabled(ctx, it) }.shuffled()
                if (pictures.isEmpty()) return Outcome(false, "There are no enabled pictures. Ask them to enable one in the Painting gallery.")
                when (PaintingStart.tryEnabledPictures(pictures) { PaintingScene(host, it).begin() }) {
                    PaintingStart.Result.SHOWN -> Outcome(true, "you have put a canvas on screen and started painting. " +
                        "give them a short line about it, but don't guess the title or say it is finished.")
                    PaintingStart.Result.UNREADABLE -> Outcome(false, "you couldn't load any of the enabled pictures. Say so; there is no canvas out.")
                    PaintingStart.Result.NO_ROOM -> Outcome(false, "you couldn't put a canvas on the screen. Say so instead of promising a painting.")
                }
            }

            Toy.SOCCER -> if (out.isNotEmpty()) {
                Outcome(
                    false,
                    "there is already a game of soccer going. cheer them on and tell them " +
                        "to score something."
                )
            } else if (SoccerGame(host).start()) {
                Outcome(true, "you set up two goals and a ball. you are keeping score, it is first to three.")
            } else {
                Outcome(false, "there wasn't room to set up the entire soccer pitch. Say so.")
            }

            Toy.BUBBLES -> when {
                out.isNotEmpty() -> Outcome(
                    false,
                    "the bubble wrap is already out and mostly unpopped. tease them about " +
                        "all the bubbles they have left."
                )
                else -> {
                    val wrap = BubbleWrap(host)
                    val y = topAboveKeyboard(host, host.stageHeight() * 0.22f, wrap.windowH)
                    if (wrap.bring(host.stageWidth() * 0.52f, y)) {
                        Outcome(
                            true,
                            "you put out a small sheet of bubble wrap and will hop on the bubbles until they're all popped. " +
                                "they can join in by tapping."
                        )
                    } else {
                        Outcome(
                            false,
                            "you dropped the bubble wrap and it bounced off the screen. one " +
                                "dry line about being rejected by a phone."
                        )
                    }
                }
            }

            Toy.PLANT -> when {
                out.isNotEmpty() -> Outcome(
                    false,
                    "the plant is already out. point out that it would love some water."
                )
                else -> {
                    val plant = Plant(host)
                    val y = topAboveKeyboard(host, host.stageHeight() * 0.34f, plant.windowH)
                    if (plant.bring(host.stageWidth() * 0.18f, y)) {
                        Outcome(
                            true,
                            "you put a pot plant on their screen and will water it slowly. " +
                                "they must tap to water it at least once before it can bloom; each stage takes three waterings."
                        )
                    } else {
                        Outcome(
                            false,
                            "you tried to put the plant down and there was no room. one dry " +
                                "line about the screen being too small for a garden."
                        )
                    }
                }
            }
        }
    }

    /** Packs everything away. */
    fun clearAll(host: OverlayService) {
        host.stopDoomscrollToy()
        host.propsOnScreen().forEach { host.dropProp(it) }
    }

    /** Short, understated lines for tidying a toy away because he is done with it. */
    private fun tiredOf(toy: Toy): String = when (toy) {
        Toy.LEMONADE -> "closing the stand. the rush is over."
        Toy.PAINTING -> "that's enough painting for one day."
        Toy.SOCCER -> "done running about. folding the pitch up."
        Toy.BUBBLES -> "bubble wrap's finished. into the bin."
        Toy.PLANT -> "giving the plant its space back."
        Toy.DOOMSCROLL -> "putting the reels down."
    }

    /**
     * Packs one toy away on his own initiative, once he has had enough of it. Every prop of
     * that toy goes, so a stand leaves with its glasses, and the post is released with it.
     */
    fun retire(host: OverlayService, toy: Toy) {
        val mine = host.propsOnScreen().filter { toy.owns(it) }
        if (mine.isEmpty()) return
        mine.forEach { host.dropProp(it) }
        host.feel(PetView.Mood.SLEEPY, 2_500L)
        host.say(tiredOf(toy))
    }

    /**
     * Turns a message into screen business — a break, a stand, a glass, a canvas, a game —
     * and returns a note for Pip's own prompt so his reply matches what just happened.
     */
    fun reactTo(host: OverlayService, raw: String): String? {
        val text = raw.lowercase()
        val ctx = host.stageContext()
        val notes = mutableListOf<String>()

        // Not a toy, but the same kind of thing: a message that changes what his body is
        // doing, with a note so the line he says matches what he is already not doing.
        Still.reactTo(host, raw)?.let { notes += it }

        if (asksToResume(text) && Quiet.active(ctx)) {
            Quiet.clear(ctx)
            host.feel(PetView.Mood.HAPPY, 1800L)
            notes += "they said your break is over, so you can play again."
        }

        if (asksForQuiet(text)) {
            val length = quietLength(raw)
            Quiet.start(ctx, length)
            clearAll(host)
            host.feel(PetView.Mood.ANNOYED, 4000L)
            notes += "they asked you to put your toys away and not get any out for " +
                "${describeDuration(length)}. you packed everything up and you'll honour " +
                "it, with one dry line about it."
            return notes.joinToString(" ")
        }

        if (wantsAway(text)) {
            val props = host.propsOnScreen()
            if (props.isEmpty()) return notesText(notes)

            val named = awayTarget(text)
            val doomed = if (named == null) props else props.filter { named.owns(it) }
            if (doomed.isEmpty()) {
                notes += "they asked you to put ${named?.label?.lowercase()} away, but it " +
                    "isn't out. point that out."
                return notes.joinToString(" ")
            }
            doomed.forEach { host.dropProp(it) }
            host.feel(PetView.Mood.ANNOYED, 2500L)
            notes += if (named == null) {
                "they told you to tidy everything away, so you packed every toy and painting " +
                    "back into your pocket, with one dry remark about it."
            } else {
                "they asked you to put ${named.label.lowercase()} away, so you did, without " +
                    "any fuss about it."
            }
            return notes.joinToString(" ")
        }

        if (dislikesIt(text)) {
            if (host.propsOnScreen().isEmpty()) return notesText(notes)
            host.feel(PetView.Mood.ANNOYED, 6000L)
            notes += "they just poked fun at something you made or gave them. take it in " +
                "good humour, defend it briefly with one dry line, and do not grovel."
            return notes.joinToString(" ")
        }

        val toy = fromText(raw) ?: return notesText(notes)
        notes += summon(host, toy).note
        return notesText(notes)
    }

    private fun notesText(notes: List<String>): String? =
        if (notes.isEmpty()) null else notes.joinToString(" ")

    /** What Pip knows about his own things and his break, for the prompt. */
    fun summary(host: OverlayService): String {
        val blocks = mutableListOf<String>()

        val left = Quiet.remaining(host.stageContext())
        if (Still.active(host.stageContext())) {
            val forHowLong = Still.describe(host.stageContext())
            blocks += "YOU ARE STANDING STILL\n" +
                "- They asked you to stay put" +
                (if (forHowLong == null) " until they say you can move"
                else ", and there is about $forHowLong of that left") +
                ", so you are not wandering about the screen and have not wandered" +
                " anywhere since. You can still talk, and you can still do something they ask" +
                " for. If they ask why you are not moving, that is why."
        }
        if (left > 0L) {
            blocks += "YOU ARE ON A TOY BREAK\n" +
                "- They asked you to lay off the toys, and there is about " +
                describeDuration(left) + " of that break left before you may get one out " +
                "again. If they ask about it, tell them how long is left, dryly. Don't " +
                "offer to start a game, pour a lemonade or paint " +
                "anything until it's over."
        }

        // Always sent, even when empty: a missing list read as "no information", and he
        // filled the gap with whatever toy the conversation had mentioned earlier.
        val lines = host.propsOnScreen()
            .map { it.describe() }
            .filter { it.isNotBlank() }
            .distinct()
        blocks += buildString {
            append("WHAT IS ON THEIR SCREEN RIGHT NOW (this list is the whole truth)\n")
            if (lines.isEmpty()) {
                append("- nothing of yours. their screen is completely clear of toys.\n")
            } else {
                lines.forEach { append("- ").append(it).append('\n') }
            }
            append("Only ever talk about what is listed above. Anything not on that list is ")
            append("not on their screen: do not mention it, do not act as if a game of it is ")
            append("happening, do not ask them to tap something that isn't there. A toy that ")
            append("came up earlier in the conversation is gone unless it is listed again. ")
            append("They can drag anything on the list anywhere and tap it to use it, and they ")
            append("can ask you to put one away. Say something short and dry about what is ")
            append("really there: a rule, the score, how it's going.")
        }

        return blocks.joinToString("\n\n")
    }

    private const val LAST_TOY = "last_toy"

    private fun history(host: OverlayService) = host.stageContext()
        .getSharedPreferences("toy_history", android.content.Context.MODE_PRIVATE)

    /** Count a toy only once it comes out, including toys requested by the user. */
    internal fun rememberArrival(host: OverlayService, toy: Toy) {
        history(host).edit().putString(LAST_TOY, toy.name).apply()
    }

    /** Pip starts something by himself when the screen has been empty for a while. */
    fun spontaneous(host: OverlayService) {
        if (Quiet.active(host.stageContext())) return
        if (host.propsOnScreen().isNotEmpty()) return
        val ctx = host.stageContext()
        if (!PipSettings.enabled(ctx, PipSettings.Option.SPONTANEOUS)) return
        val eligible = Toy.values().filter { PipSettings.toyAllowed(ctx, it) &&
            (it != Toy.PAINTING || PaintingGallery.pictures(ctx).any { picture -> PaintingGallery.enabled(ctx, picture) }) &&
            (it.spontaneousAnywhere || it == Toy.DOOMSCROLL && host.canStartDoomscrollAtHome())
        }
        val last = history(host).getString(LAST_TOY, null)
        val toy = chooseSpontaneousToy(eligible, eligible.firstOrNull { it.name == last }) ?: return
        if (toy == Toy.DOOMSCROLL) {
            host.startDoomscrollToy(automatic = true)
            return
        }
        if (!summon(host, toy).ok) return
        host.say(
            when (toy) {
                Toy.LEMONADE ->
                    "opening the stand. fifty cents, or a compliment."
                Toy.PAINTING -> return // The painting scene spoke after the canvas was attached.
                Toy.SOCCER ->
                    "first to three. the goals stay where I put them."
                Toy.BUBBLES ->
                    "bubble wrap turned up. tiny feet, big plans."
                Toy.PLANT ->
                    "we have a plant now. tap it to water it."
                Toy.DOOMSCROLL -> return
            }
        )
    }

    private fun openStand(host: OverlayService): LemonadeStand? {
        val stand = LemonadeStand(host)
        val y = topAboveKeyboard(host, host.stageHeight() * 0.55f, stand.windowH)
        return if (stand.bring(host.stageWidth() * 0.1f, y)) stand else null
    }

    /**
     * Where a toy's top edge goes: the spot it wanted, unless that would put it under a
     * keyboard someone is typing on. He can put a toy out while they are in another app, and
     * it should not land in the middle of their typing.
     */
    private fun topAboveKeyboard(host: OverlayService, wanted: Float, windowH: Int): Float =
        wanted.coerceAtMost(host.toysFloor() - windowH)
}
