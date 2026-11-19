package com.phoneassistant

import java.net.URI

/** Local routing and stopping rules; no model or Android runtime required. */
internal object JobProgress {
    const val CLEAN_SCREENS = 3
    const val MAX_FEED_SCREENS = 12

    enum class FeedMode { ALL_MISSING, CURATE }

    data class FeedRequest(
        val app: String, val surface: String?, val mode: FeedMode, val reaction: Reaction?
    )

    enum class Reaction(
        val verb: String, val available: Set<String>, val applied: Set<String>, val toggle: String
    ) {
        KUDOS("give kudos", setOf("give kudos"), setOf(
            "remove kudos", "undo kudos", "revoke kudos", "kudos given", "kudos already given",
            "given kudos", "you've given kudos", "you have given kudos", "un-kudos", "unkudos"
        ), "kudos"),
        LIKE("like", setOf("like", "like this post", "like this reel", "like this video"), setOf(
            "unlike", "liked", "remove like", "undo like", "unlike this post", "unlike this reel", "unlike this video"
        ), "like")
    }

    enum class ReactionState { READY, APPLIED, UNKNOWN }

    fun reactionState(reaction: Reaction, vararg labels: String, checked: Boolean? = null): ReactionState {
        val names = labels.map { normalize(it).trimEnd('.', '!') }
        return when {
            names.any { it in reaction.applied } -> ReactionState.APPLIED
            names.any { it in reaction.available } || (reaction.toggle in names && checked != null) ->
                if (checked == true) ReactionState.APPLIED else ReactionState.READY
            else -> ReactionState.UNKNOWN
        }
    }

    private const val ITEMS = "(?:activity|activities|posts?|reels?|videos?|items?|shorts?)"
    private const val SCOPE = "(?:any|all|every|each)"
    private const val MISSING = "(?:that )?(?:i )?(?:have not|haven't|never|have never) (?:already )?"
    private val kudosCommand = Regex("give kudos to $SCOPE $ITEMS ${MISSING}given kudos to(?: yet)?")
    private val likeCommand = Regex("like $SCOPE (?:unliked $ITEMS|$ITEMS ${MISSING}liked(?: yet)?)")
    private val openApp = Regex("open(?: up)? (.+?),? and (.+)")
    private val appFirst = Regex("(?:in|on) (.+?), (.+)")
    private val appLast = Regex("(.+) (?:in|on) (.+)")
    private val browse = Regex("(?:doomscroll|browse|scroll through) (.+?)(?: for me)?(?: and (.+))?")
    private val chooseLikes = Regex(
        "(?:like (?:any|the)(?: (?:ones?|posts?|reels?|videos?|shorts?|items?))? " +
            "(?:that )?you (?:like|enjoy|want|find interesting)|" +
            "like (?:a|the) (?:reel|post|video|short) if you (?:want|like|feel like it))"
    )
    private val feedSurface = Regex("(.+?) (reels|shorts|feed|posts|videos)")
    private val appLabel = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} .&+'()-]{0,59}")

    private fun feedTarget(raw: String): Pair<String, String?>? {
        val parts = feedSurface.matchEntire(raw)
        val app = (parts?.groupValues?.get(1) ?: raw).trim()
        if (!appLabel.matches(app) || " and " in app || " then " in app) return null
        return app to parts?.groupValues?.get(2)
    }

    /** Only an explicit whole command enables bulk or discretionary reactions. */
    fun feedRequest(message: String): FeedRequest? {
        val text = normalize(message).trim('"', '“', '”').trim().trimEnd('.', '!', '?')
            .removePrefix("please ").removeSuffix(", please").removeSuffix(" please")
        browse.matchEntire(text)?.let { match ->
            val (app, surface) = feedTarget(match.groupValues[1]) ?: return null
            val choice = match.groupValues[2]
            if (choice.isNotEmpty() && !chooseLikes.matches(choice)) return null
            return FeedRequest(app, surface, FeedMode.CURATE, Reaction.LIKE.takeIf { choice.isNotEmpty() })
        }
        val namedFirst = openApp.matchEntire(text) ?: appFirst.matchEntire(text)
        val namedLast = if (namedFirst == null) appLast.matchEntire(text) else null
        val target = namedFirst?.groupValues?.get(1) ?: namedLast?.groupValues?.get(2) ?: return null
        val command = namedFirst?.groupValues?.get(2) ?: namedLast?.groupValues?.get(1) ?: return null
        val reaction = when {
            kudosCommand.matches(command) -> Reaction.KUDOS
            likeCommand.matches(command) -> Reaction.LIKE
            else -> return null
        }
        val (app, surface) = feedTarget(target) ?: return null
        return FeedRequest(app, surface, FeedMode.ALL_MISSING, reaction)
    }

    class FeedCoverage {
        var clean = 0
            private set
        private var last: String? = null
        private val visited = mutableSetOf<String>()

        // Observe before pressing, so our own changes don't count as a clean viewport.
        fun complete(viewport: String, available: Int, given: Int): Boolean {
            if (viewport == last) return false
            last = viewport
            clean = if (visited.add(viewport) && available == 0 && given > 0) clean + 1 else 0
            return clean >= CLEAN_SCREENS
        }
    }

    private val openLink = Regex("(?:please +)?(?:open|visit|go +to) +(https?://[^\\s]+)", RegexOption.IGNORE_CASE)

    fun explicitUrl(message: String): String? {
        val raw = openLink.matchEntire(message.trim())?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return uri.scheme.lowercase() + ":" + uri.rawSchemeSpecificPart +
            (uri.rawFragment?.let { "#$it" } ?: "")
    }

    private val searchAndOpen = Regex(
        """\b(?:search|google|look\s+(?:up|for)|find)\b.+\b(?:then|and)\s+""" +
            """(?:open|click|visit|tap|go\s+to|take\s+me\s+to)\b"""
    )
    private val openFromGoogle = Regex(
        """\b(?:open|click|visit|tap|go\s+to|take\s+me\s+to)\b.+\b""" +
            """(?:first\s+(?:organic\s+)?(?:google|search)\s+(?:result|link)|(?:on|via|using|with|from)\s+google)\b"""
    )
    private val resultsOnly = Regex(
        """\b(?:open|go\s+to|take\s+me\s+to)\s+(?:the\s+)?(?:google\s+)?""" +
            """(?:images|pictures|photos|search results)(?:\s+page|\s+tab)?""" +
            """(?:\s+(?:on|via|using|with|from)\s+google)?[.!?]*$"""
    )

    fun needsWebDestination(message: String): Boolean = normalize(message).let {
        !resultsOnly.containsMatchIn(it) && (searchAndOpen.containsMatchIn(it) || openFromGoogle.containsMatchIn(it))
    }

    private fun resemblesResults(labels: Set<String>) = "google search" in labels ||
        ("all" in labels && "images" in labels && ("videos" in labels || "news" in labels))

    // Different positions or one dynamic label are not sufficient evidence of navigation.
    fun pageChanged(before: Set<String>, after: Set<String>): Boolean =
        !resemblesResults(after) && (after - before).size >= 2

    class WebProgress(val destinationRequested: Boolean) {
        var followUp = false
            private set
        var navigationObserved = false
            private set
        private val failedTitles = mutableSetOf<String>()

        fun searchFinishes(modelWantsMore: Boolean): Boolean {
            followUp = destinationRequested || modelWantsMore
            return !followUp
        }

        fun failed(title: String) { failedTitles += normalize(title) }
        fun triedWithoutNavigation(title: String) = normalize(title) in failedTitles
        val attemptsExhausted get() = failedTitles.size >= 2
        val unverified get() = destinationRequested && (!navigationObserved || failedTitles.isNotEmpty())

        fun navigated() {
            navigationObserved = true
            failedTitles.clear()
        }
    }

    fun normalize(text: String): String = text.trim().lowercase()
        .replace('’', '\'').replace(Regex("\\s+"), " ")
}
