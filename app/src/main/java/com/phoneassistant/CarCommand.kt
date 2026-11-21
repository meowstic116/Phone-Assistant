package com.phoneassistant

/** Deliberately narrow: no model tool calls, UI automation, or arbitrary Android intents. */
internal sealed interface CarCommand {
    enum class Spotify : CarCommand { PLAY, PAUSE, NEXT, PREVIOUS }
    data class Navigate(val destination: String) : CarCommand

    companion object {
        private val route = Regex("^(?:please )?(?:navigate(?: me)? to|start (?:navigation|directions) to|give me directions to|take me to) (.+)$",
            RegexOption.IGNORE_CASE)
        private val home = Regex("^(?:please )?(?:navigate(?: me)?|take me|drive me) home$", RegexOption.IGNORE_CASE)
        private val spotify = mapOf(
            "play spotify" to Spotify.PLAY, "resume spotify" to Spotify.PLAY, "pause spotify" to Spotify.PAUSE,
            "next song on spotify" to Spotify.NEXT, "next track on spotify" to Spotify.NEXT,
            "skip song on spotify" to Spotify.NEXT, "skip this song on spotify" to Spotify.NEXT,
            "previous song on spotify" to Spotify.PREVIOUS, "previous track on spotify" to Spotify.PREVIOUS
        )
        private val whitespace = Regex("[\\s\\p{Z}]+")
        private val chained = Regex("\\b(?:and then|then (?:open|send|play|navigate)|and (?:open|send|play|navigate)|after that)\\b",
            RegexOption.IGNORE_CASE)

        fun parse(text: String): CarCommand? {
            if (text.length > 300 || text.any { it.isISOControl() && it != '\t' }) return null
            val spoken = text.trim().trimEnd('.', '!', '?').replace(whitespace, " ")
            val media = spoken.lowercase(java.util.Locale.ROOT).removePrefix("please ")
            spotify[media]?.let { return it }
            if (home.matches(spoken)) return Navigate("home")
            val place = route.matchEntire(spoken)?.groupValues?.get(1)?.trim()?.takeIf {
                it.length in 2..180 && it.any(Char::isLetterOrDigit) && !chained.containsMatchIn(it) &&
                    it.none { char -> char in "&=?#\\" } && !it.contains("://")
            } ?: return null
            return Navigate(place)
        }
    }
}
