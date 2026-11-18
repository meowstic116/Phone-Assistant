package com.phoneassistant

/** Everything Pip can put down on the screen. */
enum class Toy(val label: String, private val words: List<String>) {
    LEMONADE(
        "Lemonade stand",
        listOf("lemonade", "lemonade stand", "lemon drink", "a drink", "fifty cents")
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
    );

    fun mentionedIn(text: String): Boolean = words.any { text.contains(it) }

    /** Whether this prop belongs to this toy. */
    fun owns(prop: Prop): Boolean = when (this) {
        LEMONADE -> prop is LemonadeStand || prop is LemonadeGlass
        PAINTING -> prop is Painting
        SOCCER -> prop is Goal || prop is Ball || prop is ScoreBoard
        BUBBLES -> prop is BubbleWrap
        PLANT -> prop is Plant
    }
}

/**
 * The toy box: the single place that decides how a toy arrives, what Pip says about it,
 * how long he is on a break for, and how he takes criticism.
 */
object Toys {

    /** What came of asking for a toy: whether it happened, and how Pip should describe it. */
    data class Outcome(val ok: Boolean, val note: String)

    private val requestVerbs = listOf(
        "bring", "open", "set up", "start", "play", "give", "make", "pour", "want",
        "can i", "can we", "can you", "could i", "could we", "could you", "would you",
        "let's", "lets ", "show me", "i'd like", "id like", "please", "put out", "get the"
    )

    private val awayPhrases = listOf(
        "put it away", "put that away", "put everything away", "clean that up",
        "clean this up", "clear it", "get rid of it", "pack it up", "take it away",
        "stop playing", "tidy up", "put it all away", "away please"
    )

    private val quietPhrases = listOf(
        "no toys", "no games", "not now", "not right now", "take a break", "leave me alone",
        "peace and quiet", "quiet please", "don't play", "dont play", "give me a minute",
        "chill out", "settle down", "sit still", "stop playing"
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
            if (!toy.mentionedIn(text)) continue
            val words = text.trim().split(Regex("\\s+")).size
            val asking = requestVerbs.any { text.contains(it) } || words <= 2
            if (asking) return toy
        }
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
        return text.contains("away") &&
            Regex("\\b(put|pack|take|clear|tidy|shove|hide)\\b").containsMatchIn(text)
    }

    fun asksForQuiet(raw: String): Boolean {
        val text = raw.lowercase()
        return quietPhrases.any { text.contains(it) }
    }

    fun asksToResume(raw: String): Boolean {
        val text = raw.lowercase()
        return resumePhrases.any { text.contains(it) }
    }

    fun dislikesIt(raw: String): Boolean {
        val text = raw.lowercase()
        return dislikePhrases.any { text.contains(it) } && dislikeNouns.any { text.contains(it) }
    }

    /** How long a break they asked for. Half an hour when they did not say. */
    fun quietLength(raw: String): Long {
        val text = raw.lowercase()
        Regex("(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(text)?.let { found ->
            val amount = found.groupValues[1].toLongOrNull() ?: 1L
            val unit = found.groupValues[2]
            return when {
                unit.startsWith("s") -> amount * 1_000L
                unit.startsWith("h") -> amount * 3_600_000L
                else -> amount * 60_000L
            }
        }
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
            text.contains("not now") || text.contains("not right now") -> 15 * 60_000L
            text.contains("a bit") || text.contains("for now") -> 15 * 60_000L
            else -> 30 * 60_000L
        }
    }

    fun describeDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
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

        if (Quiet.active(ctx)) {
            host.feel(PetView.Mood.ANNOYED, 2000L)
            return Outcome(
                false,
                "you are on a break from toys for ${describeDuration(Quiet.remaining(ctx))} " +
                    "because they asked you to be, so you can't get " +
                    "${toy.label.lowercase()} out right now. be mock-indignant about it."
            )
        }

        val out = host.propsOnScreen().filter { toy.owns(it) }

        return when (toy) {
            Toy.LEMONADE -> {
                val stand = out.filterIsInstance<LemonadeStand>().firstOrNull()
                    ?: openStand(host)
                when {
                    stand == null -> Outcome(
                        false,
                        "you tried to set up your lemonade stand and the screen refused. " +
                            "complain about how hard it is to run a business here."
                    )
                    stand.serve() -> Outcome(
                        true,
                        "you poured them a lemonade and slid it across. the glass is sitting " +
                            "on their screen now — they can drag it and tap it to drink it."
                    )
                    else -> Outcome(
                        false,
                        "they asked for another lemonade and you are out of glasses. refuse, " +
                            "dramatically."
                    )
                }
            }

            Toy.PAINTING -> if (out.isNotEmpty()) {
                Outcome(
                    false,
                    "they asked you to paint again and there is already a painting out. point " +
                        "at it and take all the credit instead of making another."
                )
            } else {
                val style = Painting.Style.values().random()
                PaintingScene(host, style).begin()
                Outcome(
                    true,
                    "you climbed onto the screen with a canvas and are painting " +
                        "${style.blurb}. answer briefly and tell them to hold on."
                )
            }

            Toy.SOCCER -> if (out.isNotEmpty()) {
                Outcome(
                    false,
                    "there is already a game of soccer going. tell them to stop talking and " +
                        "score something."
                )
            } else {
                SoccerGame(host).start()
                Outcome(
                    true,
                    "you set up two goals and a ball. you are keeping score, it is first to " +
                        "three, and you intend to win."
                )
            }

            Toy.BUBBLES -> when {
                out.isNotEmpty() -> Outcome(
                    false,
                    "the bubble wrap is already out and mostly unpopped. that is on them."
                )
                BubbleWrap(host).bring(host.stageWidth() * 0.52f, host.stageHeight() * 0.22f) ->
                    Outcome(
                        true,
                        "you dumped a sheet of bubble wrap on their screen. it is extremely " +
                            "poppable and you are keeping count."
                    )
                else -> Outcome(
                    false,
                    "you dropped the bubble wrap and it bounced off the screen. rude."
                )
            }

            Toy.PLANT -> when {
                out.isNotEmpty() -> Outcome(
                    false,
                    "the plant is already out. they should be watering it, not talking to you."
                )
                Plant(host).bring(host.stageWidth() * 0.18f, host.stageHeight() * 0.34f) ->
                    Outcome(
                        true,
                        "you put a pot plant on their screen. it needs three waterings per " +
                            "stage to grow, and you are already emotionally attached."
                    )
                else -> Outcome(
                    false,
                    "you tried to put the plant down and there was no room. complain."
                )
            }
        }
    }

    /** Packs everything away. */
    fun clearAll(host: OverlayService) {
        host.propsOnScreen().forEach { host.dropProp(it) }
    }

    /**
     * Turns a message into screen business — a break, a stand, a glass, a canvas, a game —
     * and returns a note for Pip's own prompt so his reply matches what just happened.
     */
    fun reactTo(host: OverlayService, raw: String): String? {
        val text = raw.lowercase()
        val ctx = host.stageContext()
        val notes = mutableListOf<String>()

        if (asksToResume(text) && Quiet.active(ctx)) {
            Quiet.clear(ctx)
            host.feel(PetView.Mood.HAPPY, 1800L)
            notes += "they said your break is over, so you can play again."
        }

        if (asksForQuiet(text)) {
            val length = quietLength(raw)
            Quiet.start(ctx, length)
            host.propsOnScreen().forEach { host.dropProp(it) }
            host.feel(PetView.Mood.ANNOYED, 4000L)
            notes += "they asked you to put your toys away and not get any out for " +
                "${describeDuration(length)}. you packed everything up, with a sigh, and " +
                "you'll honour it. mostly."
            return notes.joinToString(" ")
        }

        if (wantsAway(text)) {
            val props = host.propsOnScreen()
            if (props.isEmpty()) return notesText(notes)

            val named = toyNamedIn(text)
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
                    "back into your pocket, sulking the entire time."
            } else {
                "they asked you to put ${named.label.lowercase()} away, so you did, with " +
                    "the face of someone doing a favour they resent."
            }
            return notes.joinToString(" ")
        }

        if (dislikesIt(text)) {
            if (host.propsOnScreen().isEmpty()) return notesText(notes)
            host.feel(PetView.Mood.ANNOYED, 6000L)
            notes += "they just criticised something you made or gave them. you are wounded " +
                "and pretending you are not. be a bit annoyed, mock them fondly, do not grovel."
            return notes.joinToString(" ")
        }

        val toy = fromText(raw) ?: return notesText(notes)
        notes += summon(host, toy).note
        return notesText(notes)
    }

    private fun notesText(notes: List<String>): String? =
        if (notes.isEmpty()) null else notes.joinToString(" ")

    /** What Pip knows about his own things, for the prompt. */
    fun summary(host: OverlayService): String {
        val lines = host.propsOnScreen()
            .map { it.describe() }
            .filter { it.isNotBlank() }
            .distinct()
        if (lines.isEmpty()) return ""
        return buildString {
            append("WHAT YOU HAVE OUT ON THEIR SCREEN (you put it there, it is yours)\n")
            lines.forEach { append("- ").append(it).append('\n') }
            append("They can drag any of it anywhere, tap it to use it, and hold it to put it ")
            append("away. Talk about it when it comes up, brag about it, or complain when they ")
            append("mess with it.")
        }.trimEnd()
    }

    /** Pip starts something by himself when the screen has been empty for a while. */
    fun spontaneous(host: OverlayService) {
        if (Quiet.active(host.stageContext())) return
        if (host.propsOnScreen().isNotEmpty()) return
        val toy = Toy.values().random()
        if (!summon(host, toy).ok) return
        host.say(
            when (toy) {
                Toy.LEMONADE ->
                    "opening a lemonade stand. this is my whole personality now. " +
                        "*slides you a glass*"
                Toy.PAINTING ->
                    "I'm painting something. watch, or don't. it'll be a limited edition either way."
                Toy.SOCCER ->
                    "I'm bored. first to three. you're going down, and the goals stay where I put them."
                Toy.BUBBLES ->
                    "found bubble wrap. I'm not going to pop it. that's your job. " +
                        "*pops one immediately*"
                Toy.PLANT ->
                    "I got us a plant. tap it to water it. we're plant people now."
            }
        )
    }

    private fun openStand(host: OverlayService): LemonadeStand? {
        val stand = LemonadeStand(host)
        return if (stand.bring(host.stageWidth() * 0.1f, host.stageHeight() * 0.55f)) {
            stand
        } else {
            null
        }
    }
}
