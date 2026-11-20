package com.phoneassistant

internal object NowPlaying {
    private val question = Regex(
        """(?:(?:hey pip[, ]+|pip[, ]+)?(?:(?:can|could|would) you tell me )?(?:what(?:'s| is) (?:this|that) (?:song|track)|what (?:song|track|music) (?:am i listening to|(?:(?:is|am i) playing))|what(?:'s| is) playing|what am i listening to|(?:tell me |check )?what(?:'s| is) (?:currently playing|playing on my phone))(?: (?:right now|currently|now|please))?|(?:identify|name) (?:this|that|the current) (?:song|track))"""
    )

    fun requested(text: String): Boolean = question.matches(
        text.lowercase().replace('’', '\'').trim().trimEnd('?', '.', '!').replace(Regex("\\s+"), " ")
    )

    data class Track(val playing: Boolean, val title: String?, val artist: String?)

    fun answer(sessions: List<Track>): String {
        val active = sessions.filter { it.playing }
        val tracks = active.mapNotNull { track ->
            val title = track.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artist = track.artist?.trim()?.takeIf { it.isNotEmpty() }
            if (artist == null) "“$title”" else "“$title” by $artist"
        }.distinct()
        return when {
            active.isEmpty() -> "Android isn’t reporting any media playing right now."
            tracks.isEmpty() -> "Something is playing, but the player isn’t sharing its title."
            tracks.size == 1 && active.all { !it.title.isNullOrBlank() } -> "Now playing: ${tracks.single()}."
            tracks.size == 1 -> "I can identify ${tracks.single()}, but another session isn’t sharing its title."
            else -> "Android reports more than one player: ${tracks.take(3).joinToString("; ")}."
        }
    }
}
