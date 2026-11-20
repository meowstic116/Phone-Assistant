package com.phoneassistant

import java.net.URI
import kotlin.random.Random

/** Local routing and stopping rules; no model or Android runtime required. */
internal object JobProgress {
    const val FAILURES_BEFORE_STOP = 3
    const val FEED_FAILURES_BEFORE_STOP = 5

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

    // Reaction totals are volatile labels, not buttons or evidence that the viewport moved.
    private val kudosSummary = Regex(
        "(?:[0-9][0-9,.]* gave kudos|you(?: and [0-9][0-9,.]* others?)? gave kudos|no kudos yet|be the first to give kudos)"
    )

    fun reactionSummary(reaction: Reaction?, label: String): Boolean =
        reaction == Reaction.KUDOS && kudosSummary.matches(normalize(label).trimEnd('.', '!'))

    class StableFeedRead {
        private var previous: String? = null
        private var repeats = 0

        fun observe(key: String?, required: Int): Boolean {
            repeats = when {
                key.isNullOrBlank() -> 0
                key == previous -> repeats + 1
                else -> 1
            }
            previous = key
            return repeats >= required
        }
    }

    enum class FeedWait(val firstReadMs: Long, val verifiedMs: Long, val uncertainMs: Long) {
        EXISTING(0, 150, 150),
        REACTION(100, 250, 1_100),
        SCROLL(250, 400, 1_825),
        BROWSE_SCROLL(700, 925, 1_825),
        DECLINED_SCROLL(0, 250, 450)
    }

    class FeedSettleGate(private val wait: FeedWait) {
        private val stable = StableFeedRead()
        private val stableEvidence = StableFeedRead()

        fun observe(key: String?, elapsedMs: Long, verified: Boolean): Boolean {
            val quiet = stable.observe(key, 2)
            val backed = stableEvidence.observe(key.takeIf { verified }, 2)
            return if (verified) backed && elapsedMs >= wait.verifiedMs
                else quiet && elapsedMs >= wait.uncertainMs
        }
    }

    data class FeedSwipe(val x: Float, val fromY: Float, val toY: Float, val durationMs: Long)

    // Try different vertical lanes if a container consumes the gesture, never a sideways
    // gesture. The list stroke keeps more than half the previous screen in view.
    fun feedSwipe(shortVideo: Boolean, retry: Int): FeedSwipe {
        val lane = listOf(0.48f, 0.34f, 0.62f)[retry % 3]
        return if (shortVideo) FeedSwipe(lane, 0.78f, 0.25f, 475L)
        else FeedSwipe(lane, 0.71f, 0.30f, 550L)
    }

    enum class SwipeEvidence { NONE, DOWN_FEED, SIDEWAYS }

    class FeedMotion {
        private var signedY = 0L
        private var horizontal = 0L

        @Synchronized fun observe(deltaX: Int, deltaY: Int) {
            // Android uses -1 when one axis wasn't reported. Treat that as no evidence.
            val x = if (deltaX == -1) 0L else deltaX.toLong()
            val y = if (deltaY == -1) 0L else deltaY.toLong()
            if (kotlin.math.abs(y) > kotlin.math.abs(x)) signedY += y else horizontal += kotlin.math.abs(x)
        }

        @Synchronized fun evidence(minDown: Int, minSide: Int): SwipeEvidence = when {
            signedY >= minDown -> SwipeEvidence.DOWN_FEED
            horizontal >= minSide && signedY < minSide -> SwipeEvidence.SIDEWAYS
            else -> SwipeEvidence.NONE
        }
    }

    fun sameReactionCard(before: String?, after: String?): Boolean = before != null && before == after

    fun anonymousReelAdvance(timedOnly: Boolean, swipeCompleted: Boolean, movement: FeedMovement, evidence: SwipeEvidence): Boolean =
        timedOnly && swipeCompleted && movement == FeedMovement.UNCHANGED && evidence == SwipeEvidence.DOWN_FEED

    fun reactionCardKey(anchors: Set<String>): String? = anchors.takeIf { it.size >= 3 }
        ?.sorted()?.joinToString("\n")

    // Only for recovery with the same window AND the same scroll container. This never
    // proves a kudos. A redraw may change counters and layout around the original card.
    fun sameReactionFeed(before: Set<String>, after: Set<String>): Boolean {
        val shared = before.count { it in after }
        return shared >= 3 && shared * 2 >= maxOf(before.size, after.size)
    }

    /** Confirm the same toggle, not another applied button or new reaction-count text. */
    fun verifiedReaction(sameContent: Boolean, matchingStates: List<ReactionState>): Boolean =
        sameContent && matchingStates.size == 1 && matchingStates.single() == ReactionState.APPLIED

    private const val ITEMS = "(?:activity|activities|posts?|reels?|videos?|items?|shorts?)"
    private const val SCOPE = "(?:any|all|every|each)"
    private const val MISSING = "(?:that )?(?:i )?(?:have not|haven't|never|have never) (?:already )?"
    // Whole-command matches only: support both “activities on Strava” and “Strava
    // activities,” without accepting a singular request or guessing at extra instructions.
    private const val KUDOS_START = "give kudos (?:to|on|for) $SCOPE"
    private const val KUDOS_MISSING = "(?: (?:${MISSING}given kudos (?:to|on|for)(?: yet)?|" +
        "(?:that )?(?:i )?(?:haven't|have not) yet))?"
    private val kudosCommand = Regex("$KUDOS_START $ITEMS$KUDOS_MISSING")
    private val appQualifiedKudos = Regex("$KUDOS_START (.{1,60}?) $ITEMS$KUDOS_MISSING")
    private val bulkKudosStart = Regex("^$KUDOS_START\\b")
    private val likeCommand = Regex("like $SCOPE (?:unliked $ITEMS|$ITEMS ${MISSING}liked(?: yet)?)")
    private val openApp = Regex("open(?: up)? (.+?),? and (.+)")
    private val appFirst = Regex("(?:in|on) (.+?), (.+)")
    private val appLast = Regex("(.+) (?:in|on) (.+)")
    private val browse = Regex("(doomscroll(?: through)?|doom scroll(?: through)?|browse|scroll through)(?: (.+))?")
    private val vagueDuration = Regex("(?:,? for (?:a bit|a little bit|a while|a little while|a short while))$")
    private val surfaceOnApp = Regex("(reels|shorts|feed|posts|videos) (?:on|in) (.+)")
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

    private fun browseRequest(raw: String, doomscroll: Boolean): FeedRequest? {
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
                } else if (vagueDuration.containsMatchIn(text)) {
                    text = text.replace(vagueDuration, "").removeSuffix(" for me")
                    continue
                } else return text
                text = text.substring(0, (time ?: checkNotNull(counted)).range.first).removeSuffix(" for me")
            }
        }
        val parts = limitSuffix(raw.replace(stopSuffix, "")).split(likeClause, limit = 2)
        val target = limitSuffix(parts[0])
        val choice = parts.getOrNull(1).orEmpty()
        if (parts.size == 2 && !chooseLikes.matches(choice)) return null
        // Never interpret the rest of a sentence as an installed app label.
        if (Regex("[.!?] ").containsMatchIn(target)) return null
        val prefix = countPrefix.matchEntire(target)
        val rawApp = if (prefix == null) {
            val named = surfaceOnApp.matchEntire(target)
            if (named != null) "${named.groupValues[2]} ${named.groupValues[1]}"
            else target.removePrefix("on ").removePrefix("in ")
        } else {
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
        val (rawLabel, explicitSurface) = feedTarget(appAndSurface) ?: return null
        val app = if (doomscroll && rawLabel == "insta") "instagram" else rawLabel
        val surface = explicitSurface ?: "reels".takeIf { doomscroll && app == "instagram" }
        return FeedRequest(app, surface, FeedMode.CURATE, Reaction.LIKE.takeIf { choice.isNotEmpty() },
            if (invalid) null else BrowseLimit(duration, screens))
    }
    private val likeClause = Regex("(?:,? and (?:you (?:may|can) )?|[.!?;] you (?:may|can) )(?=like )")
    private val chooseLikes = Regex(
        "(?:like (?:(?:any|the)(?: (?:ones?|posts?|reels?|videos?|shorts?|items?))?|(?:posts|reels|videos|shorts|items)) " +
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

    private val tabMetadata = Regex("(?:,? tab(?: [0-9]+ of [0-9]+)?)$")

    fun matchesFeedTab(label: String, surface: String): Boolean =
        normalize(label).trimEnd('.').removeSuffix(", selected").replace(tabMetadata, "").trimEnd(',') == surface

    fun selectedFeedTab(label: String, surface: String): Boolean =
        normalize(label).trimEnd('.').endsWith(", selected") && matchesFeedTab(label, surface)

    fun feedSurfaceChanged(before: Set<String>, after: Set<String>): Boolean =
        (after - before).size >= 2 && (before - after).isNotEmpty()

    private val viewerId = Regex("(?:[a-z0-9]+_)*(reels?|shorts?)_(?:view_?pager|pager|viewer|video_player)(?:_container)?")

    private val instagramViewerId = Regex("clips_(?:viewer_(?:view_?pager|pager|root|container)|view_?pager)")

    fun matchesFeedViewer(id: String?, surface: String, appPackage: String? = null): Boolean {
        val name = id.orEmpty()
        return viewerId.matchEntire(name)?.groupValues?.get(1)?.trimEnd('s') == surface.trimEnd('s') ||
            (appPackage == "com.instagram.android" && surface == "reels" && instagramViewerId.matches(name))
    }

    fun shortVideoSurface(surface: String?) = surface == "reels" || surface == "shorts"

    data class SurfaceRead(
        val labels: Set<String>, val selected: Boolean, val heading: Boolean,
        val title: Boolean, val viewer: Boolean
    )

    enum class SurfaceProof(val description: String) {
        SELECTED("tab selected"), VIEWER("matching video viewer visible"),
        TITLE("matching page title appeared"), CONTENT("new page content after tab click")
    }

    /** Compare observations with the pre-click screen, never with an intermediate loading frame. */
    fun surfaceProof(before: SurfaceRead, after: SurfaceRead, accepted: Boolean, surface: String): SurfaceProof? = when {
        after.selected -> SurfaceProof.SELECTED
        after.viewer -> SurfaceProof.VIEWER
        !accepted -> null
        (!before.heading && after.heading) || (!before.title && after.title) -> SurfaceProof.TITLE
        // A dialog or a refresh can change the labels without opening a video viewer.
        !shortVideoSurface(surface) && feedSurfaceChanged(before.labels, after.labels) -> SurfaceProof.CONTENT
        else -> null
    }

    /** A malformed doomscroll command must not fall through to free-form phone actions. */
    fun isDoomscrollRequest(message: String): Boolean {
        val text = feedText(message)
        val command = (openApp.matchEntire(text) ?: appFirst.matchEntire(text))
            ?.groupValues?.get(2)?.removePrefix("then ") ?: text
        return doomscrollStart.containsMatchIn(command)
    }

    fun isBulkKudosRequest(message: String): Boolean {
        val text = feedText(message)
        val command = (openApp.matchEntire(text) ?: appFirst.matchEntire(text))
            ?.groupValues?.get(2)?.removePrefix("then ") ?: text
        return bulkKudosStart.containsMatchIn(command)
    }

    fun routeFeedLocally(message: String): Boolean =
        feedRequest(message) != null || isDoomscrollRequest(message) || isBulkKudosRequest(message)

    private val doomscrollStart = Regex("^(?:doomscroll|doom scroll)\\b")

    private fun feedText(message: String): String =
        normalize(message).trim('"', '“', '”').trim().trimEnd('.', '!', '?')
            .removePrefix("pip, ").removePrefix("please ")
            .removePrefix("can you ").removePrefix("could you ").removePrefix("would you ")
            .removePrefix("please ").removeSuffix(", please").removeSuffix(" please")

    private val playbackPrefix = Regex("^(?:reel|video|playback) (?:progress|position|time)(?::|,)? ?")
    private val playbackClock = Regex("([0-9]{1,3}):([0-5][0-9]) (?:/|of) ([0-9]{1,3}):([0-5][0-9])")
    private val playbackEnded = setOf("reel ended", "video ended", "playback ended", "replay reel", "replay video")

    data class Playback(val fraction: Float = 0f, val ended: Boolean = false)

    fun playbackLabel(raw: String, timeline: Boolean): Playback? {
        val label = normalize(raw).trimEnd('.')
        if (label in playbackEnded) return Playback(ended = true)
        val withoutPrefix = label.replace(playbackPrefix, "")
        if (!timeline && withoutPrefix == label) return null
        val match = playbackClock.matchEntire(withoutPrefix) ?: return null
        val (minutes, seconds, totalMinutes, totalSeconds) = match.destructured
        val position = minutes.toInt() * 60 + seconds.toInt()
        val duration = totalMinutes.toInt() * 60 + totalSeconds.toInt()
        if (duration <= 0 || position > duration) return null
        return Playback(position.toFloat() / duration)
    }

    enum class ReelWatchResult { WAITING, FINISHED, ESTIMATED }

    class ReelWatch(started: Long, private val estimatedMs: Long = 18_000L) {
        private val start = started
        private var lastProgressAt = started
        private var previous: Float? = null
        fun observe(now: Long, playback: Playback?): ReelWatchResult {
            if (playback?.ended == true || playback != null && playback.fraction >= 0.995f) return ReelWatchResult.FINISHED
            if (playback != null) {
                val prior = previous
                // Reels generally loop instead of showing an ended screen.
                if (prior != null && prior >= 0.65f && playback.fraction <= 0.20f && prior - playback.fraction >= 0.50f) {
                    return ReelWatchResult.FINISHED
                }
                if (prior == null || playback.fraction > prior) lastProgressAt = now
                previous = playback.fraction
            }
            return if (now - start >= estimatedMs && (previous == null || now - lastProgressAt >= 8_000L)) {
                ReelWatchResult.ESTIMATED
            } else ReelWatchResult.WAITING
        }
    }

    enum class CommunicationKind { MESSAGE, CALL }
    data class Communication(val kind: CommunicationKind, val explicitApp: String?)

    private val messageStart = Regex("^(?:message|text|sms|send (?:a )?(?:text(?: message)?|message) to) (.+)$")
    private val sendToPerson = Regex("^send (.+?) (?:a )?(?:text(?: message)?|message)(.*)$")
    private val callStart = Regex("^(?:call|dial|ring|phone) (.+)$")
    private val bodyStart = Regex(":|,? and (?:say|tell|write|send)\\b|[, ]+(?:saying|with (?:the )?(?:message|text))\\b|[\"“]")
    private val appSuffix = Regex(".+ (?:on|via|using|in) (.+)")

    fun communicationRequest(original: String): Communication? {
        val text = feedText(original)
        val openedFirst = openApp.matchEntire(text)
        val command = openedFirst?.groupValues?.get(2)?.removePrefix("then ") ?: text
        val message = messageStart.matchEntire(command)
        val send = if (message == null) sendToPerson.matchEntire(command) else null
        val call = if (message == null && send == null) callStart.matchEntire(command) else null
        val target = (message ?: call)?.groupValues?.get(1)
            ?: send?.let { it.groupValues[1] + it.groupValues[2] } ?: return null
        val kind = if (message != null || send != null) CommunicationKind.MESSAGE else CommunicationKind.CALL
        val head = target.substring(0, bodyStart.find(target)?.range?.first ?: target.length).trim()
        val suffix = appSuffix.matchEntire(head)?.groupValues?.get(1)
            ?.takeIf { it !in setOf("speaker", "speakerphone", "the phone") }
        return Communication(kind, openedFirst?.groupValues?.get(1) ?: suffix)
    }

    // Communication jobs are pinned to the app requested by the user, not the model.
    fun communicationActionAllowed(action: String): Boolean = action in
        setOf("tap", "long_press", "type", "scroll_down", "scroll_up", "back", "wait", "done", "sign_in")

    /** Only an explicit whole command enables bulk or discretionary reactions. */
    fun feedRequest(message: String): FeedRequest? {
        val text = feedText(message)
        browse.matchEntire(text)?.let {
            return browseRequest(it.groupValues[2], it.groupValues[1].startsWith("doom"))
        }
        appQualifiedKudos.matchEntire(text)?.let {
            val (app, surface) = feedTarget(it.groupValues[1]) ?: return null
            return FeedRequest(app, surface, FeedMode.ALL_MISSING, Reaction.KUDOS)
        }
        val namedFirst = openApp.matchEntire(text) ?: appFirst.matchEntire(text)
        val namedLast = if (namedFirst == null) appLast.matchEntire(text) else null
        val target = namedFirst?.groupValues?.get(1) ?: namedLast?.groupValues?.get(2) ?: return null
        val command = namedFirst?.groupValues?.get(2)?.removePrefix("then ")
            ?: namedLast?.groupValues?.get(1) ?: return null
        if (namedFirst != null) browse.matchEntire(command)?.let {
            val tail = it.groupValues[2]
            val withApp = if (tail.isBlank()) target else "$target $tail"
            return browseRequest(withApp, it.groupValues[1].startsWith("doom"))
        }
        val reaction = when {
            kudosCommand.matches(command) -> Reaction.KUDOS
            likeCommand.matches(command) -> Reaction.LIKE
            else -> return null
        }
        val (app, surface) = feedTarget(target) ?: return null
        return FeedRequest(app, surface, FeedMode.ALL_MISSING, reaction)
    }

    /** The API's rewrite is advice only; the original unsupported request never runs it. */
    fun feedSuggestion(original: String, suggestion: String): String? {
        val example = suggestion.trim().takeIf { it.length in 1..180 && it.none { ch -> ch.isISOControl() } }
            ?: return null
        val plan = feedRequest(example) ?: return null
        val text = feedText(original)
        val matchesApp = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(plan.app)}(?![\\p{L}\\p{N}])")
            .containsMatchIn(text) || plan.app == "instagram" && Regex("\\binsta\\b").containsMatchIn(text)
        if (!matchesApp) return null
        val keepsScope = when {
            isDoomscrollRequest(original) -> {
                val permission = likeClause.find(text)?.let { chooseLikes.matches(text.substring(it.range.last + 1)) } == true
                plan.mode == FeedMode.CURATE && plan.browseLimit != null && (plan.reaction == null || permission)
            }
            isBulkKudosRequest(original) -> plan.mode == FeedMode.ALL_MISSING && plan.reaction == Reaction.KUDOS
            else -> false
        }
        return example.takeIf { keepsScope }
    }

    enum class FeedRecovery { WAIT_THEN_READ, ASK_USER }

    fun feedRecovery(action: String): FeedRecovery? = when (action) {
        "wait_then_read" -> FeedRecovery.WAIT_THEN_READ
        "ask_user" -> FeedRecovery.ASK_USER
        else -> null
    }

    class FeedHelpBudget {
        private var used = false
        fun take(): Boolean {
            if (used) return false
            used = true
            return true
        }
    }

    class SurfaceWatch(private val originalWindow: Int) {
        private var consecutive = 0
        private var contentKey: Set<String>? = null
        private var candidateWindow: Int? = null
        var canRetryTap = true
            private set

        fun observe(window: Int?, proof: SurfaceProof?, sameTab: Boolean, labels: Set<String> = emptySet()): Boolean {
            if (window != originalWindow || proof != null || !sameTab) canRetryTap = false
            val key = labels.takeIf { proof == SurfaceProof.CONTENT }
            if (proof == null || window == null) {
                consecutive = 0
                candidateWindow = null
                contentKey = null
                return false
            }
            consecutive = if (window == candidateWindow && key == contentKey) consecutive + 1 else 1
            candidateWindow = window
            contentKey = key
            return consecutive >= 2
        }
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

    enum class FeedMovement { NEW_VIEWPORT, REVISIT, UNCHANGED }

    /** Tracks movement, not reaction availability: an already-reacted card isn't the end. */
    class FeedTraversal {
        private val visited = RecentVisits()

        fun enter(viewport: String): Boolean = visited.add(viewport)

        fun movement(previous: String, next: String): FeedMovement = when {
            next.isBlank() || next == previous -> FeedMovement.UNCHANGED
            visited.contains(next) -> FeedMovement.REVISIT
            else -> FeedMovement.NEW_VIEWPORT
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
