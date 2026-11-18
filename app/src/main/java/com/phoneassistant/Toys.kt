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
}

/**
 * The toy box: what Pip drags onto the screen, what he says when the user asks for
 * something, and how he takes criticism.
 */
object Toys {

    private val requestVerbs = listOf(
        "bring", "open", "set up", "start", "play", "give", "make", "pour", "want",
        "can i", "can we", "can you", "could i", "could we", "could you", "would you",
        "let's", "lets ", "show me", "i'd like", "id like", "please", "put out", "get the"
    )

    private val awayPhrases = listOf(
        "put it away", "put that away", "put everything away", "clean that up",
        "clean this up", "clear it", "get rid of it", "pack it up", "take it away",
        "stop playing", "tidy up", "put it all away"
    )

    private val dislikePhrases = listOf(
        "don't like", "dont like", "do not like", "hate", "dislike", "gross", "yuck",
        "awful", "terrible", "ugly", "not good", "bad job", "the worst"
    )

    private val dislikeNouns = listOf(
        "lemonade", "drink", "painting", "paint", "art", "drawing", "plant", "game",
        "toy", "it", "that", "this"
    )

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

    fun wantsAway(text: String): Boolean {
        val lower = text.lowercase()
        return awayPhrases.any { lower.contains(it) }
    }

    fun dislikesIt(text: String): Boolean {
        val lower = text.lowercase()
        return dislikePhrases.any { lower.contains(it) } &&
            dislikeNouns.any { lower.contains(it) }
    }

    /**
     * Turns a message into screen business — a stand, a glass, a canvas, a game — and
     * returns a note for Pip's own prompt so his reply matches what just happened.
     */
    fun reactTo(host: OverlayService, raw: String): String? {
        val text = raw.lowercase()

        if (wantsAway(text)) {
            val props = host.propsOnScreen()
            if (props.isEmpty()) return null
            props.forEach { host.dropProp(it) }
            host.feel(PetView.Mood.ANNOYED, 2500L)
            return "they told you to tidy your things away, so you packed every toy and " +
                "painting back into your pocket, sulking the entire time."
        }

        if (dislikesIt(text)) {
            if (host.propsOnScreen().isEmpty()) return null
            host.feel(PetView.Mood.ANNOYED, 6000L)
            return "they just criticised something you made or gave them. you are wounded and " +
                "pretending you are not. be a bit annoyed, mock them fondly, do not grovel."
        }

        return when (fromText(raw)) {
            null -> null
            Toy.LEMONADE -> {
                val stand = host.propsOnScreen().filterIsInstance<LemonadeStand>().firstOrNull()
                    ?: spawnStand(host)
                if (stand == null) {
                    "you tried to set up your lemonade stand and the screen refused. complain " +
                        "about how hard it is to run a business here."
                } else if (stand.serve(null)) {
                    "you poured them a lemonade and slid it across. the glass is sitting on " +
                        "their screen now — they can drag it and tap it to drink it."
                } else {
                    "they asked for another lemonade and you are out of glasses. refuse, " +
                        "dramatically."
                }
            }
            Toy.PAINTING -> {
                if (host.propsOnScreen().any { it is Painting }) {
                    "they asked you to paint again and there is already a painting out. point " +
                        "at it and take all the credit instead of making another."
                } else {
                    val style = Painting.Style.values().random()
                    PaintingScene(host, style).begin()
                    "you climbed onto the screen with a canvas and are painting " +
                        "${style.blurb}. answer briefly and tell them to hold on."
                }
            }
            Toy.SOCCER -> {
                if (host.propsOnScreen().any { it is Goal }) {
                    "there is already a game of soccer going. tell them to stop talking and " +
                        "score something."
                } else {
                    SoccerGame(host).start()
                    "you set up two goals and a ball. you are keeping score, it is first to " +
                        "three, and you intend to win."
                }
            }
            Toy.BUBBLES -> {
                if (host.propsOnScreen().any { it is BubbleWrap }) {
                    "the bubble wrap is already out and mostly unpopped. that is on them."
                } else {
                    val sheet = BubbleWrap(host)
                    val ok = sheet.bring(
                        host.stageWidth() * 0.52f,
                        host.stageHeight() * 0.22f
                    )
                    if (ok) "you dumped a sheet of bubble wrap on their screen. it is " +
                        "extremely poppable and you are keeping count."
                    else "you dropped the bubble wrap and it bounced off the screen. rude."
                }
            }
            Toy.PLANT -> {
                if (host.propsOnScreen().any { it is Plant }) {
                    "the plant is already out. they should be watering it, not talking to you."
                } else {
                    val plant = Plant(host)
                    val ok = plant.bring(
                        host.stageWidth() * 0.18f,
                        host.stageHeight() * 0.34f
                    )
                    if (ok) "you put a pot plant on their screen. it needs three waterings per " +
                        "stage to grow, and you are already emotionally attached."
                    else "you tried to put the plant down and there was no room. complain."
                }
            }
        }
    }

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
            append("They can drag any of it anywhere and tap it to use it. Talk about it when ")
            append("it comes up, brag about it, or complain when they mess with it.")
        }.trimEnd()
    }

    /** Puts a toy on the screen right now, with no conversation attached. */
    fun summon(host: OverlayService, toy: Toy): Boolean {
        val on = host.propsOnScreen()
        return when (toy) {
            Toy.LEMONADE -> {
                val stand = on.filterIsInstance<LemonadeStand>().firstOrNull()
                    ?: spawnStand(host)
                stand?.serve(null) ?: false
            }
            Toy.PAINTING -> {
                if (on.any { it is Painting }) return false
                PaintingScene(host, Painting.Style.values().random()).begin()
                true
            }
            Toy.SOCCER -> {
                if (on.any { it is Goal }) return false
                SoccerGame(host).start()
                true
            }
            Toy.BUBBLES ->
                BubbleWrap(host).bring(host.stageWidth() * 0.52f, host.stageHeight() * 0.22f)
            Toy.PLANT ->
                Plant(host).bring(host.stageWidth() * 0.18f, host.stageHeight() * 0.34f)
        }
    }

    /** Packs everything away. */
    fun clearAll(host: OverlayService) {
        host.propsOnScreen().forEach { host.dropProp(it) }
    }

    /** Pip starts something by himself when the screen has been empty for a while. */
    fun spontaneous(host: OverlayService) {
        if (host.propsOnScreen().isNotEmpty()) return
        val toy = Toy.values().random()
        if (!summon(host, toy)) return
        val line = when (toy) {
            Toy.LEMONADE ->
                "opening a lemonade stand. this is my whole personality now. *slides you a glass*"
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
        host.say(line)
    }

    private fun spawnStand(host: OverlayService): LemonadeStand? {
        val stand = LemonadeStand(host)
        val ok = stand.bring(
            host.stageWidth() * 0.1f,
            host.stageHeight() * 0.55f
        )
        return if (ok) stand else null
    }
}
