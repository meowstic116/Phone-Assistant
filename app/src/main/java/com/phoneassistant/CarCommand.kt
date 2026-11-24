package com.phoneassistant

/** Deliberately narrow: no model tool calls, UI automation, or arbitrary Android intents. */
internal sealed interface CarCommand {
    sealed interface Media : CarCommand
    enum class Spotify : Media { PLAY, PAUSE, NEXT, PREVIOUS }
    data class SpotifyPlaylist(val title: String) : Media
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

        fun looksLikeTask(text: String) = taskStart.containsMatchIn(spoken(text).replace(Regex("^please ", RegexOption.IGNORE_CASE), ""))

        private fun playlist(text: String): String? {
            val titleRequest = text.replace(spotifySuffix, "")
            if (otherMediaService.containsMatchIn(titleRequest)) return null
            val explicit = SpotifyQuickPlan.playlistRequest("$titleRequest on Spotify")
            val bare = if (explicit == null) barePlaylist.matchEntire(titleRequest)?.let { match ->
                (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
                    ?.replace(Regex(""" (?:for|to) me$""", RegexOption.IGNORE_CASE), "")
            } else null
            return (explicit ?: bare)?.trim()?.trim('"', '“', '”')?.trim()?.takeIf {
                it.length in 1..120 && it.any(Char::isLetterOrDigit) && !it.contains("://") &&
                    it.none { char -> char in ";,\\" } && !additionalTask.containsMatchIn(it) &&
                    !playWithSomeone.containsMatchIn(it)
            }
        }

        fun parse(text: String): CarCommand? {
            if (text.length > 300 || text.any { it.isISOControl() && it != '\t' }) return null
            val spoken = spoken(text)
            // One lowercase, then both lookups on it: the offline media path runs while driving
            // and allocates twice otherwise.
            val media = spoken.lowercase(java.util.Locale.ROOT).removePrefix("please ")
            val exact = spotify[media]
            if (exact != null) return exact
            spotify[media.removeSuffix(" on spotify")]?.let { return it }
            playlist(spoken.replace(Regex("^please ", RegexOption.IGNORE_CASE), ""))?.let { return SpotifyPlaylist(it) }
            if (home.matches(spoken)) return Navigate("home")
            if (stop.matches(spoken)) return StopNavigation
            if (navState.matches(spoken)) return NavigationState
            val place = route.matchEntire(spoken)?.groupValues?.get(1)?.trim()?.takeIf {
                // "Take me home" is its own command, and "where am I" is not a destination.
                it.length in 2..180 && it.any(Char::isLetterOrDigit) && !chained.containsMatchIn(it) &&
                    it.none { char -> char in "&=?#\\" } && !it.contains("://") &&
                    !home.containsMatchIn("take me $it") && !navState.containsMatchIn(it)
            } ?: return null
            return Navigate(place)
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
