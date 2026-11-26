package com.phoneassistant

/** Deliberately narrow: no model tool calls, UI automation, or arbitrary Android intents. */
internal sealed interface CarCommand {
    sealed interface Media : CarCommand
    enum class Spotify : Media { PLAY, PAUSE, NEXT, PREVIOUS }
    data class SpotifyPlaylist(val title: String, val namedPlaylist: Boolean = true) : Media
    data class Navigate(val destination: String) : CarCommand

    /** Every way of saying "I'm done with this route". */
    data object StopNavigation : CarCommand

    /** Read-only: asks Maps whether a route is still running; changes nothing. */
    data object NavigationState : CarCommand

    companion object {
        private val route = Regex("^(?:please )?(?:navigate(?: me)? to|start (?:navigation|directions) to|give me directions to|take me to) (.+)$",
            RegexOption.IGNORE_CASE)
        private val home = Regex("^(?:please )?(?:navigate(?: me)?|take me|drive me) home$", RegexOption.IGNORE_CASE)
        // Exact, so ordinary speech ("stop" on its own, "cancel my alarm") never reaches Maps.
        private val stop = Regex("""^(?:please )?(?:(?:cancel|stop|end|exit|quit|close|kill|turn off|shut off) (?:the |my |this |this active |active |current )?(?:navigation|route|directions|guidance|trip|drive)|(?:cancel|stop|end|exit|quit|close) (?:navigating|driving)(?: for me)?)$""",
            RegexOption.IGNORE_CASE)
        private val navState = Regex("""^(?:please )?(?:are (?:we|you) (?:still )?navigat(?:ing|e)|(?:am i|are we) still (?:on route|on the route|headed (?:to|there))|is (?:the |my )?navigation still (?:on|running|active)|how (?:much )?(?:long|far)(?:er)?(?: do i have)?(?: left| until (?:i|we) (?:arrive|get there))?|(?:what(?:'s| is) my eta|how (?:long|far) to (?:go|the destination)))$""",
            RegexOption.IGNORE_CASE)
        private val spotify = mapOf(
            "play spotify" to Spotify.PLAY, "resume spotify" to Spotify.PLAY, "pause spotify" to Spotify.PAUSE,
            "resume music" to Spotify.PLAY, "pause music" to Spotify.PAUSE,
            "play music" to Spotify.PLAY, "play some music" to Spotify.PLAY,
            "play a song" to Spotify.PLAY, "play songs" to Spotify.PLAY,
            "play something" to Spotify.PLAY, "play something else" to Spotify.PLAY,
            "start the music" to Spotify.PLAY,
            "play" to Spotify.PLAY, "pause" to Spotify.PAUSE,
            "next song" to Spotify.NEXT, "next track" to Spotify.NEXT, "skip song" to Spotify.NEXT,
            "skip this song" to Spotify.NEXT, "skip track" to Spotify.NEXT, "skip this track" to Spotify.NEXT,
            "skip" to Spotify.NEXT, "skip it" to Spotify.NEXT, "next" to Spotify.NEXT,
            "previous song" to Spotify.PREVIOUS, "previous track" to Spotify.PREVIOUS,
            "previous" to Spotify.PREVIOUS, "last song" to Spotify.PREVIOUS, "back" to Spotify.PREVIOUS
        )
        // "play jazz" is how people name music, so a play with anything like a name after it
        // goes to Spotify's search; the playlist and mix shapes keep their own wording.
        private val barePlaylist = Regex("""^play (?:me )?(?:(?:the |my )?playlist (?:called |named )?(.+)|(.+ mix)|(.+))$""",
            RegexOption.IGNORE_CASE)
        private val taskStart = Regex("""^(?:play|resume|pause|skip|next song|next track|previous song|previous track|navigate|take me|drive me|start navigation|start directions|give me directions|are we still navigating|is navigation still|stop navigation|cancel navigation|open|send|text|message|call|dial|doomscroll|remove|delete|clear|dismiss|press|click|tap|hit)\b""",
            RegexOption.IGNORE_CASE)
        private val spotifySuffix = Regex(""" (?:on|in) spotify(?: please)?$""", RegexOption.IGNORE_CASE)
        private val otherMediaService = Regex("""\b(?:on|in|from) (?:spotify|youtube(?: music)?|apple music|amazon music|pandora|tidal|soundcloud|deezer)\b""",
            RegexOption.IGNORE_CASE)
        private val additionalTask = Regex("""\b(?:then|and (?:play|pause|open|queue|skip|send|text|call|navigate|add|share))\b""",
            RegexOption.IGNORE_CASE)
        // "Play a game with me" is an invitation, not a record: words playing with a person
        // never become a music search.
        private val playWithSomeone = Regex("""\b(?:with|against) (?:me|us|him|her|them|you)\b""",
            RegexOption.IGNORE_CASE)
        private val whitespace = Regex("[\\s\\p{Z}]+")
        private val chained = Regex("\\b(?:and then|then (?:open|send|play|navigate)|and (?:open|send|play|navigate)|after that)\\b",
            RegexOption.IGNORE_CASE)

        private fun spoken(text: String) = text.trim().trimEnd('.', '!', '?').replace(whitespace, " ")

        // A spoken street number arrives as words: "one one two four Main Street" is
        // 1124 Main Street. Only a leading run of number words becomes digits; the name
        // of a place stays exactly as heard, and one lonely number word ("six flags",
        // "one world trade center") is left to Maps.
        private val singles = mapOf("zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3,
            "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19)
        private val decades = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        private val ordinals = setOf("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth",
            "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
            "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth",
            "eightieth", "ninetieth", "hundredth", "thousandth")

        private fun spokenAddress(destination: String): String {
            val parts = destination.split(whitespace)
            val numberWord = { w: String -> w in singles || w in decades || w == "hundred" || w == "and" || w == "a" }
            val run = parts.takeWhile { numberWord(it.lowercase(java.util.Locale.ROOT)) }
            if (run.size < 2) return destination
            // "fifty fifth street" is a street name, not 50: an ordinal after the run keeps
            // every word of it.
            val after = parts.getOrNull(run.size)?.lowercase(java.util.Locale.ROOT)
            if (after != null && after in ordinals) return destination
            val lower = run.map { it.lowercase(java.util.Locale.ROOT) }
            val number = when {
                lower.all { it in singles } -> lower.joinToString("") { singles.getValue(it).toString() }
                else -> {
                    val groups = numberGroups(lower) ?: return destination
                    when {
                        groups.size == 1 -> groups[0].toString()
                        groups.size == 2 && groups.all { it in 0..99 } -> groups.joinToString("")
                        else -> return destination
                    }
                }
            }
            return (listOf(number) + parts.drop(run.size)).joinToString(" ")
        }

        /** Number groups of at most 999: "two hundred and five" is one, "twelve thirty four" is two. */
        private fun numberGroups(tokens: List<String>): List<Int>? {
            val groups = mutableListOf<Int>()
            var i = 0
            while (i < tokens.size) {
                var value = 0
                var consumed = false
                if (i + 1 < tokens.size && tokens[i + 1] == "hundred" &&
                    (tokens[i] == "a" || (singles[tokens[i]] ?: -1) in 1..9)) {
                    value = if (tokens[i] == "a") 100 else singles.getValue(tokens[i]) * 100
                    i += 2
                    if (i < tokens.size && tokens[i] == "and") i++
                    consumed = true
                }
                if (i < tokens.size && tokens[i] in decades) {
                    value += decades.getValue(tokens[i]); i++
                    if (i < tokens.size && (singles[tokens[i]] ?: -1) in 1..9) {
                        value += singles.getValue(tokens[i]); i++
                    }
                    consumed = true
                } else if (i < tokens.size && (singles[tokens[i]] ?: -1) in 1..19) {
                    value += singles.getValue(tokens[i]); i++
                    consumed = true
                }
                if (!consumed) return null
                groups += value
            }
            return groups
        }

        fun looksLikeTask(text: String) = taskStart.containsMatchIn(spoken(text).replace(Regex("^please ", RegexOption.IGNORE_CASE), ""))

        // "play the song called hello" names a song the way people say it; the wrapper is
        // stripped before the shapes see it, so the search gets the name alone.
        private val songWrapper = Regex("""^play (?:me )?(?:the |my |a )?(?:song|album|track) (?:called |named |by )?(.+)$""",
            RegexOption.IGNORE_CASE)

        // A request that names nothing to find — "play a playlist", "play a song or
        // playlist", "play some songs" — is an ask to start music, not a search for the
        // word "a". The exact map takes the common ones; this takes the rest.
        private val fillerWords = setOf("a", "an", "the", "my", "please", "song", "songs", "music",
            "playlist", "playlists", "album", "albums", "for", "me", "from", "of", "or",
            "some", "any", "something")
        private fun fillerTitle(title: String): Boolean =
            title.lowercase(java.util.Locale.ROOT).split(whitespace)
                .all { it.trim('"', '“', '”') in fillerWords }

        /** The title and whether the words named a playlist or mix; a plain name is searched free. */
        private fun playlist(text: String): Pair<String, Boolean>? {
            val titleRequest = text.replace(spotifySuffix, "")
            if (otherMediaService.containsMatchIn(titleRequest)) return null
            val explicit = SpotifyQuickPlan.playlistRequest("$titleRequest on Spotify")
            val wrapper = songWrapper.matchEntire(titleRequest)?.groupValues?.get(1)
            val bare = if (explicit == null && wrapper == null) barePlaylist.matchEntire(titleRequest)?.let { match ->
                (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
                    ?.replace(Regex(""" (?:for|to) me$""", RegexOption.IGNORE_CASE), "")
                    ?.let { it to (match.groups[1]?.value != null || match.groups[2]?.value != null) }
            } else null
            val (raw, named) = explicit?.let { it to true } ?: wrapper?.let { it to false } ?: bare ?: return null
            return raw.trim().trim('"', '“', '”').trim().takeIf {
                // A comma is a song's own ("Hey, Soul Sister"), not a second task: only a
                // backslash is kept out of the search box.
                it.length in 1..120 && it.any(Char::isLetterOrDigit) && !it.contains("://") &&
                    it.none { char -> char.code == 92 } && !additionalTask.containsMatchIn(it) &&
                    !playWithSomeone.containsMatchIn(it)
            }?.let { it to named }
        }

        fun parse(text: String): CarCommand? {
            if (text.length > 300 || text.any { it.isISOControl() && it != '\t' }) return null
            val spoken = spoken(text)
            // One lowercase, then both lookups on it: the offline media path runs while driving
            // and allocates twice otherwise.
            val media = spoken.lowercase(java.util.Locale.ROOT).removePrefix("please ")
                .removeSuffix(" please").trim()
            val exact = spotify[media]
            if (exact != null) return exact
            spotify[media.removeSuffix(" on spotify")]?.let { return it }
            playlist(spoken.replace(Regex("^please ", RegexOption.IGNORE_CASE), ""))?.let {
            if (fillerTitle(it.first)) return Spotify.PLAY
            return SpotifyPlaylist(it.first, it.second)
        }
            if (home.matches(spoken)) return Navigate("home")
            if (stop.matches(spoken)) return StopNavigation
            if (navState.matches(spoken)) return NavigationState
            val place = route.matchEntire(spoken)?.groupValues?.get(1)?.trim()?.takeIf {
                // "Take me home" is its own command, and "where am I" is not a destination.
                it.length in 2..180 && it.any(Char::isLetterOrDigit) && !chained.containsMatchIn(it) &&
                    it.none { char -> char in "&=?#\\" } && !it.contains("://") &&
                    !home.containsMatchIn("take me $it") && !navState.containsMatchIn(it)
            } ?: return null
            return Navigate(spokenAddress(place))
        }
    }
}

/** Projection and opt-in are independent. Neither chat nor accessibility is a fallback for driving. */
internal enum class CarVoiceRoute {
    ORDINARY, REVIEW_WHEN_PARKED, EXECUTE, DISABLED, CONNECTION_UNVERIFIED, UNSUPPORTED;

    companion object {
        /** One fixed phrase for diagnostics; never the destination or the transcript. */
        fun label(route: CarVoiceRoute, projected: Boolean?): String = when (route) {
            EXECUTE -> "Android Auto projection verified; running the command"
            DISABLED -> "Projection connected, driving commands off"
            CONNECTION_UNVERIFIED -> if (projected == null) "Projection could not be read"
                else "Projection not connected"
            UNSUPPORTED -> "Projection connected; not a driving command"
            ORDINARY -> "Ordinary speech"
            REVIEW_WHEN_PARKED -> "No projection; driving command not run"
        }

        fun choose(enabled: Boolean, projected: Boolean?, supported: Boolean, task: Boolean): CarVoiceRoute = when {
            enabled && supported -> if (projected == true) EXECUTE else CONNECTION_UNVERIFIED
            !task -> ORDINARY
            projected == true && !enabled -> DISABLED
            projected == true || (projected == null && enabled) -> UNSUPPORTED
            else -> REVIEW_WHEN_PARKED
        }
    }
}
