package com.phoneassistant

import java.net.URI
import kotlin.random.Random

/** Local routing and stopping rules; no model or Android runtime required. */
internal object JobProgress {
    const val CLEAN_SCREENS = 3
    const val FAILURES_BEFORE_STOP = 3

    enum class FeedMode { ALL_MISSING, CURATE }

    data class FeedRequest(
        val app: String, val surface: String?, val mode: FeedMode, val reaction: Reaction?,
        val browseLimit: BrowseLimit? = null
    )

    data class BrowseLimit(val durationMs: Long? = null, val screens: Int? = null) {
        fun session(startMs: Long, random: Random = Random.Default) = BrowseSession(
            startMs, durationMs ?: if (screens == null) random.nextLong(30, 301) * 1000 else null, screens
        )
    }

    class BrowseSession(private val startMs: Long, val durationMs: Long?, val screens: Int?) {
        fun remainingMs(nowMs: Long): Long? = durationMs?.let { (it - (nowMs - startMs)).coerceAtLeast(0) }
        fun reachedCount(viewed: Int): Boolean = screens != null && viewed >= screens
    }

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
    private val browse = Regex("(?:doomscroll(?: through)?|doom scroll(?: through)?|browse|scroll through) (.+)")
    private val stopSuffix = Regex(",? (?:and (?:then )?|then )stop$")
    private val smallNumbers = (listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen")).withIndex().associate { it.value to it.index }
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        .withIndex().associate { it.value to (it.index + 2) * 10 }
    private val number = "(?:-?[0-9]+(?:\\.[0-9]+)?|an?|${smallNumbers.keys.joinToString("|")}|" +
        "(?:${tens.keys.joinToString("|")})(?:[- ](?:${smallNumbers.keys.drop(1).take(9).joinToString("|")}))?)"
    private const val TIME_UNIT = "(?:hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)"
    private const val COUNT_UNIT = "(?:reels?|shorts?|videos?|posts?|items?|screens?)"
    private val timePart = Regex("($number) ?($TIME_UNIT)\\b")
    private val timeSuffix = Regex("(?:,? (?:for|(?:and )?stop after) (?:about )?)" +
        "(half an? $TIME_UNIT|$number ?$TIME_UNIT(?:(?: and)? $number ?$TIME_UNIT)?)$")
    private val countSuffix = Regex("(?:,? (?:for|after|(?:and )?stop after|until (?:you(?:'ve| have)? (?:viewed|seen|watched)|you (?:see|view|watch))) )" +
        "($number) ($COUNT_UNIT)$")
    private val countPrefix = Regex("($number) ($COUNT_UNIT) (?:on|in) (.+)")

    private fun numeric(raw: String): Double? {
        raw.toDoubleOrNull()?.let { return it }
        if (raw == "a" || raw == "an") return 1.0
        smallNumbers[raw]?.let { return it.toDouble() }
        val words = raw.split(' ', '-')
        val base = tens[words.first()] ?: return null
        return (base + (words.getOrNull(1)?.let { smallNumbers[it] } ?: 0)).toDouble()
    }

    private fun millis(raw: String): Long? {
        fun unitMs(unit: String) = when (unit.first()) {
            'h' -> 3_600_000L
            'm' -> 60_000L
            else -> 1_000L
        }
        if (raw.startsWith("half ")) return unitMs(raw.substringAfterLast(' ')) / 2
        val total = timePart.findAll(raw).sumOf { part ->
            (numeric(part.groupValues[1]) ?: 0.0) * unitMs(part.groupValues[2])
        }
        return total.takeIf { it >= 1 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private fun count(raw: String): Int? = numeric(raw)?.takeIf {
        it in 1.0..Int.MAX_VALUE.toDouble() && it % 1 == 0.0
    }?.toInt()

    private fun browseRequest(raw: String): FeedRequest? {
        var duration: Long? = null
        var screens: Int? = null
        var countUnit: String? = null
        var invalid = false
        fun limitSuffix(input: String): String {
            var text = input.removeSuffix(" for me")
            while (true) {
                val time = timeSuffix.find(text)
                val counted = countSuffix.find(text)
                if (time != null) {
                    val parsed = millis(time.groupValues[1])
                    if (parsed == null || duration != null) invalid = true
                    duration = parsed
                } else if (counted != null) {
                    val parsed = count(counted.groupValues[1])
                    if (parsed == null || screens != null) invalid = true
                    screens = parsed
                    countUnit = counted.groupValues[2]
                } else return text
                text = text.substring(0, (time ?: checkNotNull(counted)).range.first).removeSuffix(" for me")
            }
        }
        val parts = limitSuffix(raw.replace(stopSuffix, "")).split(Regex(",? and (?=like )"), limit = 2)
        val target = limitSuffix(parts[0])
        val choice = parts.getOrNull(1).orEmpty()
        if (parts.size == 2 && !chooseLikes.matches(choice)) return null
        val prefix = countPrefix.matchEntire(target)
        val rawApp = if (prefix == null) target.removePrefix("on ").removePrefix("in ") else {
            val (amount, items, app) = prefix.destructured
            if (screens != null) invalid = true
            screens = count(amount)
            if (screens == null) invalid = true
            countUnit = items
            app
        }
        val countedSurface = when (countUnit) {
            "reel", "reels" -> "reels"
            "short", "shorts" -> "shorts"
            "video", "videos" -> "videos"
            "post", "posts" -> "posts"
            else -> null
        }
        val appAndSurface = if (countedSurface != null && feedSurface.matchEntire(rawApp) == null) {
            "$rawApp $countedSurface"
        } else rawApp
        val (app, surface) = feedTarget(appAndSurface) ?: return null
        return FeedRequest(app, surface, FeedMode.CURATE, Reaction.LIKE.takeIf { choice.isNotEmpty() },
            if (invalid) null else BrowseLimit(duration, screens))
    }
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
            .removePrefix("pip, ").removePrefix("please ")
            .removePrefix("can you ").removePrefix("could you ").removePrefix("would you ")
            .removePrefix("please ").removeSuffix(", please").removeSuffix(" please")
        browse.matchEntire(text)?.let { return browseRequest(it.groupValues[1]) }
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

    /** Success resets the retry budget; there is no total step budget. */
    class FailureStreak(private val limit: Int = FAILURES_BEFORE_STOP) {
        var failures = 0
            private set

        fun record(progress: Boolean): Boolean {
            failures = if (progress) 0 else failures + 1
            return failures >= limit
        }
    }

    /** Detect short cycles without retaining an ever-growing history on infinite feeds. */
    class RecentVisits(private val capacity: Int = 64) {
        private val recent = LinkedHashSet<String>()

        fun contains(key: String): Boolean = key in recent

        fun add(key: String): Boolean {
            val fresh = recent.add(key)
            if (fresh && recent.size > capacity) recent.remove(recent.first())
            return fresh
        }
    }

    class FeedCoverage {
        var clean = 0
            private set
        private var last: String? = null
        private val visited = RecentVisits()

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
