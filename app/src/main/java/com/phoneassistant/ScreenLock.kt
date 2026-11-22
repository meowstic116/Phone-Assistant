package com.phoneassistant

/**
 * "Lock my screen", recognised on the phone.
 *
 * This is deliberately not left to the model. Locking is the one phone action where the reply
 * has to happen at the moment the screen goes dark — there is no screen left to read, no window
 * to inspect, and no way to ask a second time. A round trip to a chat provider that answers with
 * a refusal, a clarifying question, or "I can't do that" leaves the phone awake while it
 * decides, which is the opposite of what was asked.
 *
 * Matching is narrow on purpose: a lock verb and a screen noun must appear together. A phrase
 * that names something else to lock is not a request to lock the phone, and a question about
 * locking is not a request to do it.
 *
 * - "lock my screen", "lock the screen", "turn the screen off", "screen off"
 * - "lock it" — but only straight after a lock request, because on its own "lock it" could be a
 *   door, a folder, or an account
 *
 * Rejected by design: "lock the door", "lock my keys in the car", "block the screen",
 * "unlock the screen" (the opposite), "how do I lock my screen" (a question), "remind me to lock
 * the screen" (a reminder, which another route already owns).
 */
object ScreenLock {
    /** Confidence that this message is a request to lock the phone. */
    enum class Match {
        /** The screen is named outright: "lock the screen", "turn my phone off". */
        NAMED,

        /** "lock it", only meaningful straight after a lock request — see [requested]. */
        REFERRED
    }

    private const val NOUN = """(?:screen|display|phone|device|handset|mobile)"""

    /**
     * Every pattern needs both halves of the request. Nothing here matches a bare "lock", which
     * is what "lock the door" and "lock" degrade to once the object is stripped away.
     */
    private val named = listOf(
        // lock (up) the screen / my phone / this device
        Regex("""\block\b\s+(?:up\b\s+)?(?:my\b\s+|the\b\s+|this\b\s+|your\b\s+|it\b\s+)?$NOUN\b"""),
        // the screen lock / phone locked — only as an instruction, see blocked()
        Regex("""\b(?:my|the|this)\b\s+$NOUN\b\s+lock\b"""),
        // turn / switch / shut (off) the screen (off)
        Regex("""\b(?:turn|switch|shut)\b\s+(?:off\b\s+)?(?:my\b\s+|the\b\s+|this\b\s+)?$NOUN\b(?:\s+off\b)?"""),
        // put / send the screen to sleep, sleep the screen
        Regex("""\b(?:put|send)\b\s+(?:my\b\s+|the\b\s+|this\b\s+)?$NOUN\b\s+to\s+sleep\b"""),
        Regex("""\bsleep\b\s+(?:my\b\s+|the\b\s+|this\b\s+)?$NOUN\b""")
    )

    /** "lock it", "lock that", "lock them up" — pronouns depend on context to mean the phone. */
    private val referred = Regex("""\block\b\s+(?:it|that|them)\b(?:\s+up)?\s*$""")

    /**
     * A question about locking, rather than an instruction to lock. "How do I lock my screen"
     * and "does the screen lock" are questions. "Can you lock my screen" is a polite request and
     * is deliberately not blocked — the second person is the tell.
     */
    private val questionStart = Regex(
        """^(?:how|what|where|why|when|which|who|do|does|did|is|are|should|if|tell me)\b""".trimIndent()
    )

    /** "can I lock my screen" is asking whether it is possible, not asking for it. */
    private val aboutSelf = Regex("""^(?:can|could|should|do|does|will)\s+(?:i|we)\b""")

    /**
     * "lock screen wallpaper", "lock screen timeout" — the lock screen named as a setting, not
     * a thing to lock. Without this, the noun in "lock screen settings" would read as a request
     * to darken the display.
     */
    private val lockScreenSetting = Regex(
        """\block\b\s+screen\b\s+(?:wallpapers?|time|timeout|timer|settings?|brightness|pin|password|passcode|pattern|code|sound|music|orientation|rotation|shortcuts?|widgets?|security|notifications?|layout|style|theme)\b"""
    )

    /** A reminder, a prohibition, or something to stop — all owned by other routes. */
    private val notAnInstruction = Regex("""\b(?:remind|reminder|don't|do not|never|stop|prevent|without|instead of)\b""")

    /** The message is about locking, but the object is something else. */
    private val otherObject = Regex(
        """\block\b\s+(?:up\b\s+)?(?:my\b\s+|the\b\s+|this\b\s+|your\b\s+)?(?:car|door|house|home|garage|shed|cabinet|drawer|fridge|freezer|bike|bicycle|gate|locker|desk|office|account|accounts|profile|folder|file|files|photos?|note|notes|app|apps|keyboard|rotation)\b"""
    )

    /** Normalises curly apostrophes and case, and pads so word boundaries work at the ends. */
    private fun normalize(message: String): String =
        " " + message.lowercase()
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('\u201c', '"')
            .replace('\u201d', '"')
            .replace(Regex("""\s+"""), " ")
            .trim() + " "

    /** Strips the words that would otherwise let "unlock" and "block" pass as "lock". */
    private fun unwrapped(flat: String): String =
        flat.replace("unlock", " ").replace("block", " ").replace("lockscreen", " screen ")

    /**
     * Whether [message] asks Pip to lock the phone.
     *
     * @param afterLockRequest true when the previous thing said was itself a lock request, which
     *   is the only situation where a bare "lock it" is unambiguous.
     */
    fun requested(message: String, afterLockRequest: Boolean = false): Match? {
        val flat = normalize(message)
        if (questionStart.containsMatchIn(flat.trim())) return null
        if (notAnInstruction.containsMatchIn(flat)) return null
        // A reminder or a prohibition can still name the screen, so it is checked before the
        // pronoun form as well as before the named form.
        val body = unwrapped(flat)
        if (aboutSelf.containsMatchIn(body.trim())) return null
        if (lockScreenSetting.containsMatchIn(body)) return null
        if (otherObject.containsMatchIn(body)) return null
        // "screen off" is a whole instruction; anywhere else it could be a status report.
        if (Regex("""^screen\s+off$""").containsMatchIn(body.trim())) return Match.NAMED
        if (named.any { it.containsMatchIn(body) }) return Match.NAMED
        if (afterLockRequest && referred.containsMatchIn(body)) return Match.REFERRED
        return null
    }

    /**
     * A nickname for the action, for diagnostics: never the user's own words, since a lock
     * request can arrive with anything else in the sentence.
     */
    fun label(match: Match): String = when (match) {
        Match.NAMED -> "lock the screen"
        Match.REFERRED -> "lock it, after a lock request"
    }
}
