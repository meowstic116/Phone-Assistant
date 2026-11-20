package com.phoneassistant

/** Narrow, deterministic rules for a named Spotify playlist. Never use a partial title. */
internal object SpotifyQuickPlan {
    const val PACKAGE = "com.spotify.music"
    private val request = Regex(
        """^(?:please )?play (?:me )?(.{1,120}?) (?:on|in) spotify(?: please)?[.!?]*$""",
        RegexOption.IGNORE_CASE
    )
    private val extraAction = Regex("""[,;]|\band\s+(?:then|play|open|queue|skip|add|send|share|like|turn|put)\b|\bthen\b""")
    private val homeTab = Regex("""^home, tab \d+ of \d+(?:, selected)?$""")

    fun playlistRequest(text: String): String? {
        var name = request.matchEntire(text.trim())?.groupValues?.get(1)?.trim() ?: return null
        name = name.replace(Regex("""^(?:the|my)\s+""", RegexOption.IGNORE_CASE), "")
        val trailingType = Regex("""\s+playlist$""", RegexOption.IGNORE_CASE)
        val leadingType = Regex("""^playlist\s+(?:(?:called|named)\s+)?""", RegexOption.IGNORE_CASE)
        name = when {
            trailingType.containsMatchIn(name) -> name.replace(trailingType, "")
            leadingType.containsMatchIn(name) -> name.replace(leadingType, "")
            else -> return null
        }.trim()
        val quoted = (name.startsWith('"') && name.endsWith('"')) ||
            (name.startsWith('“') && name.endsWith('”'))
        if (quoted) name = name.substring(1, name.length - 1).trim()
        if (name.isEmpty() || (!quoted && extraAction.containsMatchIn(JobProgress.normalize(name)))) return null
        return name
    }

    fun isHome(label: String, selected: Boolean): Boolean {
        val value = JobProgress.normalize(label)
        return value == "home, selected" ||
            (value == "home" || homeTab.matches(value)) && (selected || value.endsWith(", selected"))
    }

    fun isRecentHeading(label: String): Boolean = JobProgress.normalize(label) in
        setOf("recently played", "recently played on spotify", "jump back in")

    fun isTitle(label: String, title: String): Boolean = JobProgress.normalize(label) == JobProgress.normalize(title)

    fun isCard(label: String, title: String): Boolean {
        val value = JobProgress.normalize(label)
        val wanted = JobProgress.normalize(title)
        // Explicit card labels are sometimes decorated with the playlist's owner or type.
        return value == wanted || value == "playlist: $wanted" || value == "playlist, $wanted" ||
            value == "$wanted, playlist" || value.startsWith("$wanted, playlist, ") ||
            value.startsWith("playlist, $wanted, ")
    }

    fun isPlay(label: String): Boolean = JobProgress.normalize(label) in
        setOf("play playlist", "shuffle play playlist", "play this playlist", "shuffle play this playlist")

    fun isPause(label: String): Boolean = JobProgress.normalize(label) in
        setOf("pause playlist", "pause this playlist", "pause shuffle play playlist")

    fun <T> unique(items: List<T>, matches: (T) -> Boolean): T? = items.singleOrNull(matches)
}
