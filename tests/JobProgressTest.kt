package com.phoneassistant

fun main() {
    val exact = "Please open strava and give kudos to every activity that I haven't given kudos to yet"
    val kudosPlan = JobProgress.FeedRequest("strava", null, JobProgress.FeedMode.ALL_MISSING, JobProgress.Reaction.KUDOS)
    check(JobProgress.feedRequest(exact) == kudosPlan)
    check(JobProgress.feedRequest("“${exact.replace("haven't", "haven’t")}.”") == kudosPlan)
    check(JobProgress.feedRequest("Give kudos to every activity I haven't given kudos to in another app") ==
        kudosPlan.copy(app = "another app"))
    check(JobProgress.feedRequest("Open Instagram and like every post I haven't liked yet") ==
        kudosPlan.copy(app = "instagram", reaction = JobProgress.Reaction.LIKE))
    check(JobProgress.feedRequest("Like all unliked reels on Instagram Reels") ==
        kudosPlan.copy(app = "instagram", surface = "reels", reaction = JobProgress.Reaction.LIKE))

    // Exact wording from the diagnostics must bypass the general agent and its early DONE.
    val shorthand = "Please open strava and give kudos to any activity I haven't yet"
    check(JobProgress.feedRequest(shorthand) == kudosPlan)
    check(JobProgress.feedRequest("Open Another App and give kudos to every activity that I have not yet") ==
        kudosPlan.copy(app = "another app"))
    check(JobProgress.feedRequest("$shorthand and follow the athletes") == null)
    check(JobProgress.feedRequest("Open Strava and give kudos to one activity I haven't yet") == null)
    check(JobProgress.feedRequest("Open Strava and give kudos to any activity I haven't finished yet") == null)

    // Current diagnostic phrasing omits the missing-state qualifier entirely.
    check(JobProgress.feedRequest("Please open strava and give kudos to every activity") == kudosPlan)
    check(JobProgress.feedRequest("Give kudos to every activity in Another App") == kudosPlan.copy(app = "another app"))
    check(JobProgress.feedRequest("Open Strava and give kudos to every activity and share it") == null)
    check(JobProgress.feedRequest("Open Strava and give kudos to this activity") == null)

    val inlineKudos = "Give kudos on any strava activity I haven't yet"
    check(JobProgress.feedRequest(inlineKudos) == kudosPlan)
    check(JobProgress.feedRequest("Give kudos for every Another App activity I haven’t given kudos on yet") ==
        kudosPlan.copy(app = "another app"))
    check(JobProgress.feedRequest("In Strava, give kudos on any activity I haven't yet") == kudosPlan)
    check(JobProgress.feedRequest("Give kudos on any activity I haven't yet on Strava") == kudosPlan)
    check(JobProgress.routeFeedLocally(inlineKudos))
    check(JobProgress.feedRequest("$inlineKudos and message Alex") == null)
    check(JobProgress.routeFeedLocally("$inlineKudos and message Alex")) // rephrase; don't hand to the model
    check(JobProgress.feedRequest("Don't $inlineKudos") == null)
    check(!JobProgress.routeFeedLocally("Don't $inlineKudos"))
    check(!JobProgress.routeFeedLocally("Give kudos on this Strava activity"))

    val selective = JobProgress.FeedRequest("instagram", "reels", JobProgress.FeedMode.CURATE, JobProgress.Reaction.LIKE, JobProgress.BrowseLimit())
    check(JobProgress.feedRequest("Doomscroll Instagram Reels and like any you want") == selective)
    check(JobProgress.feedRequest("Browse Instagram Reels for me and like the reels you enjoy") == selective)
    check(JobProgress.feedRequest("Scroll through YouTube Shorts and like the ones you find interesting") ==
        selective.copy(app = "youtube", surface = "shorts"))
    check(JobProgress.feedRequest("Doomscroll Instagram Reels for me") == selective.copy(reaction = null))
    check(JobProgress.feedRequest("Don't $exact") == null)
    check(JobProgress.feedRequest("$exact and then message Alex") == null)
    check(JobProgress.feedRequest("Open Strava and give kudos to the first activity") == null)
    check(JobProgress.feedRequest("Doomscroll Instagram Reels and like any you want and follow the authors") == null)
    check(JobProgress.feedRequest("Open Instagram and like every cat reel I haven't liked yet") == null)
    check(JobProgress.feedRequest("Doomscroll Instagram Reels and like every reel") == null)

    val browse = selective.copy(reaction = null)
    fun plan(command: String) = checkNotNull(JobProgress.feedRequest(command))
    check(plan("Please doomscroll Instagram Reels for 2 minutes.") ==
        browse.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 120_000)))
    check(plan("Doomscroll Instagram Reels for a minute and 30 seconds and like any you want") ==
        selective.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 90_000)))
    check(plan("Doomscroll Instagram Reels and like any you want for 1.5 minutes then stop") ==
        selective.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 90_000)))
    check(plan("Can you please doomscroll on Instagram Reels for half a minute?") ==
        browse.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 30_000)))
    check(plan("Doomscroll Instagram Reels for about 45s").browseLimit?.durationMs == 45_000L)
    check(plan("Doomscroll ten reels on Instagram") == browse.copy(browseLimit = JobProgress.BrowseLimit(screens = 10)))
    check(plan("Doomscroll Instagram Reels until you've seen twenty-one reels").browseLimit?.screens == 21)
    check(plan("Doomscroll Instagram and stop after 10 reels") == browse.copy(browseLimit = JobProgress.BrowseLimit(screens = 10)))
    check(plan("Doomscroll Instagram Reels for 2 minutes and stop after 10 reels").browseLimit ==
        JobProgress.BrowseLimit(durationMs = 120_000, screens = 10))
    check(plan("Doomscroll Instagram Reels for 3 reels and like any you want") ==
        selective.copy(browseLimit = JobProgress.BrowseLimit(screens = 3)))
    check(plan("Doomscroll Instagram Reels for 0 seconds").browseLimit == null)
    check(plan("Doomscroll Instagram Reels for -5 minutes").browseLimit == null)
    check(plan("Doomscroll Instagram Reels for 1.5 reels").browseLimit == null)
    check(JobProgress.feedRequest("Doomscroll Instagram Reels for a minute and like every reel") == null)

    // Both phrases from the Instagram diagnostics must start timed, reaction-free Reels.
    check(plan("Open Instagram and doomscroll") == browse)
    check(plan("Doomscroll on Insta") == browse)
    check(plan("Doomscroll on Instagram for a little bit") == browse)
    check(plan("Open Instagram and then doomscroll for a short while") == browse)
    check(plan("Doomscroll reels on Instagram") == browse)
    check(plan("Open Instagram and doomscroll for 45 seconds") ==
        browse.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 45_000)))
    check(plan("Open Instagram and doomscroll for 6 reels and like any you want") ==
        selective.copy(browseLimit = JobProgress.BrowseLimit(screens = 6)))
    check(plan("Doomscroll Instagram feed").surface == "feed")
    check(plan("Browse Instagram").surface == null)
    check(plan("Doomscroll Another App for a bit").surface == null)
    check(JobProgress.feedRequest("Open Instagram and doomscroll for a bit and follow the authors") == null)
    check(JobProgress.matchesFeedTab("Reels, Tab 2 of 5", "reels"))
    check(JobProgress.selectedFeedTab("Reels, Tab 2 of 5, selected", "reels"))
    check(!JobProgress.matchesFeedTab("Suggested Reels", "reels"))
    check(!JobProgress.matchesFeedTab("Create a reel", "reels"))
    check(JobProgress.feedSurfaceChanged(setOf("Home", "post A"), setOf("Reels", "clip B")))
    check(!JobProgress.feedSurfaceChanged(setOf("Home", "post A"), setOf("Home", "post B")))
    // The permission may be a separate sentence, but still authorizes only selective likes.
    val optionalSentence = "Open Instagram and doomscroll for 1 minute. You may like reels you like"
    check(plan(optionalSentence) == selective.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 60_000)))
    check(plan("Doomscroll Instagram Reels for 2.5 minutes. You can like any reels you enjoy!") ==
        selective.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 150_000)))
    check(JobProgress.feedRequest("Open Instagram and doomscroll for 1 minute. You may like every reel") == null)
    check(JobProgress.feedRequest("$optionalSentence and follow the authors") == null)
    check(JobProgress.feedRequest("Open Instagram and doomscroll for 1 minute. Send a message") == null)

    val home = JobProgress.SurfaceRead(setOf("Home", "Reels", "Post A"), false, false, false, false)
    val pendingReels = home.copy(labels = setOf("Reels", "Loading"))
    val sparseReels = pendingReels.copy(labels = setOf("Reels"), title = true)
    check(JobProgress.surfaceProof(home, pendingReels, accepted = true, surface = "reels") == null)
    check(JobProgress.surfaceProof(home, sparseReels, accepted = true, surface = "reels") == JobProgress.SurfaceProof.TITLE)
    check(JobProgress.surfaceProof(home, sparseReels, accepted = false, surface = "reels") == null)
    check(JobProgress.surfaceProof(home, home.copy(viewer = true), accepted = false, surface = "reels") == JobProgress.SurfaceProof.VIEWER)
    check(JobProgress.surfaceProof(home, home.copy(selected = true), accepted = true, surface = "reels") == JobProgress.SurfaceProof.SELECTED)
    check(JobProgress.matchesFeedViewer("clips_reels_view_pager", "reels"))
    check(!JobProgress.matchesFeedViewer("reels_viewer_button", "reels"))
    check(!JobProgress.matchesFeedViewer("reel_tab", "reels"))
    check(!JobProgress.matchesFeedViewer("shorts_view_pager", "reels"))

    val dialog = home.copy(labels = setOf("Settings", "Allow", "Cancel"))
    check(JobProgress.surfaceProof(home, dialog, accepted = true, surface = "reels") == null)
    check(JobProgress.matchesFeedViewer("clips_viewer_view_pager", "reels", "com.instagram.android"))
    check(!JobProgress.matchesFeedViewer("clips_viewer_view_pager", "reels", "another.app"))
    val waiting = JobProgress.SurfaceWatch(7)
    check(!waiting.observe(7, null, sameTab = true))
    check(waiting.canRetryTap) // Home refreshed, but the original tab stayed present.
    check(!waiting.observe(null, null, sameTab = false))
    check(!waiting.canRetryTap)
    check(!waiting.observe(8, JobProgress.SurfaceProof.TITLE, sameTab = false))
    check(waiting.observe(8, JobProgress.SurfaceProof.VIEWER, sameTab = false))
    val transientProof = JobProgress.SurfaceWatch(7)
    check(!transientProof.observe(7, JobProgress.SurfaceProof.SELECTED, sameTab = true))
    check(!transientProof.observe(7, null, sameTab = true))
    check(!transientProof.observe(7, JobProgress.SurfaceProof.SELECTED, sameTab = true))
    check(!transientProof.canRetryTap)
    check(JobProgress.isDoomscrollRequest("Open Instagram and doomscroll. You may like every reel"))
    check(JobProgress.feedRequest("Open Instagram and doomscroll. You may like every reel") == null)
    check(JobProgress.isDoomscrollRequest("Pip, please doomscroll Instagram then send a message"))
    check(!JobProgress.isDoomscrollRequest("Tell me why I doomscroll on Instagram"))

    // A rewrite is a supported suggestion only and cannot introduce a different app or action.
    val difficultBrowse = "Open Instagram and doomscroll until the tea is ready"
    val proposedBrowse = "Open Instagram and doomscroll for 1 minute"
    check(JobProgress.feedSuggestion(difficultBrowse, proposedBrowse) == proposedBrowse)
    check(JobProgress.feedSuggestion("Doomscroll on Insta for a jiffy", proposedBrowse) == proposedBrowse)
    check(JobProgress.feedSuggestion(difficultBrowse, "$proposedBrowse and like any reels you want") == null)
    check(JobProgress.feedSuggestion(difficultBrowse, "Open TikTok and doomscroll for 1 minute") == null)
    check(JobProgress.feedSuggestion(difficultBrowse, "Open Instagram and doomscroll for 0 minutes") == null)
    check(JobProgress.feedSuggestion(difficultBrowse, "Open Instagram and delete my account") == null)
    check(JobProgress.feedSuggestion("Give kudos on every Strava thing I missed", inlineKudos) == inlineKudos)
    check(JobProgress.feedSuggestion("Tell me about doomscrolling on Instagram", proposedBrowse) == null)
    val helpBudget = JobProgress.FeedHelpBudget()
    check(helpBudget.take() && !helpBudget.take())
    check(JobProgress.feedRecovery("wait_then_read") == JobProgress.FeedRecovery.WAIT_THEN_READ)
    check(JobProgress.feedRecovery("ask_user") == JobProgress.FeedRecovery.ASK_USER)
    check(JobProgress.feedRecovery("tap anywhere") == null)

    val feedRetries = JobProgress.FailureStreak(JobProgress.FEED_FAILURES_BEFORE_STOP)
    repeat(4) { check(!feedRetries.record(false)) }
    check(!feedRetries.record(true))
    repeat(4) { check(!feedRetries.record(false)) }
    check(feedRetries.record(false))

    val seeded = kotlin.random.Random(178)
    val durations = List(200) { JobProgress.BrowseLimit().session(1000, seeded).durationMs }
    check(durations.all { it != null && it in 30_000..300_000 })
    check(durations.distinct().size > 1)
    val timed = JobProgress.BrowseLimit(durationMs = 5_000).session(1000)
    check(timed.remainingMs(5999) == 1L && timed.remainingMs(6000) == 0L && timed.remainingMs(7000) == 0L)
    check(!timed.reachedCount(1000))
    val counted = JobProgress.BrowseLimit(screens = 10).session(1000)
    check(counted.remainingMs(999_999) == null && !counted.reachedCount(9) && counted.reachedCount(10))

    fun kudos(vararg labels: String, checked: Boolean? = null) =
        JobProgress.reactionState(JobProgress.Reaction.KUDOS, *labels, checked = checked)
    fun like(vararg labels: String, checked: Boolean? = null) =
        JobProgress.reactionState(JobProgress.Reaction.LIKE, *labels, checked = checked)
    check(kudos("Give Kudos") == JobProgress.ReactionState.READY)
    check(kudos("Give Kudos", "Kudos given") == JobProgress.ReactionState.APPLIED)
    check(kudos("Remove Kudos") == JobProgress.ReactionState.APPLIED)
    check(kudos("Kudos", "4 kudos") == JobProgress.ReactionState.UNKNOWN)
    check(kudos("Kudos", checked = false) == JobProgress.ReactionState.READY)
    check(kudos("Kudos", checked = true) == JobProgress.ReactionState.APPLIED)
    check(like("Like this reel") == JobProgress.ReactionState.READY)
    check(like("Like", "Unlike") == JobProgress.ReactionState.APPLIED)
    check(like("Like", checked = true) == JobProgress.ReactionState.APPLIED)
    check(like("Give kudos", "277 likes") == JobProgress.ReactionState.UNKNOWN)

    val kudosReaction = JobProgress.Reaction.KUDOS
    check(JobProgress.reactionSummary(kudosReaction, "6 gave kudos"))
    check(JobProgress.reactionSummary(kudosReaction, "You and 1 other gave kudos"))
    check(JobProgress.reactionSummary(kudosReaction, "You and 2 others gave kudos"))
    check(!JobProgress.reactionSummary(kudosReaction, "Give Kudos"))
    check(kudos("You and 2 others gave kudos") == JobProgress.ReactionState.UNKNOWN)
    val ready = JobProgress.ReactionState.READY
    val applied = JobProgress.ReactionState.APPLIED
    check(JobProgress.verifiedReaction(true, listOf(applied)))
    check(!JobProgress.verifiedReaction(true, emptyList()))
    check(!JobProgress.verifiedReaction(true, listOf(ready, applied)))
    check(!JobProgress.verifiedReaction(false, listOf(applied)))
    val landmarks = setOf("activity A", "activity B", "activity C", "activity D", "old counter")
    check(JobProgress.sameReactionFeed(landmarks, landmarks - "old counter" + "new counter"))
    check(!JobProgress.sameReactionFeed(landmarks, setOf("activity A", "activity details", "comments")))

    val settling = JobProgress.StableFeedRead()
    check(!settling.observe("card y=500", 2))
    check(!settling.observe("card y=230", 2)) // A moving viewport isn't ready for taps.
    check(!settling.observe(null, 2))
    check(!settling.observe("card y=210", 2))
    check(settling.observe("card y=210", 2))
    val delayedScroll = JobProgress.StableFeedRead()
    repeat(5) { check(!delayedScroll.observe("old viewport", 6)) }
    check(!delayedScroll.observe("new viewport", 2))
    check(delayedScroll.observe("new viewport", 2))

    // Fast path only after two samples show evidence; keep the longer grace period for a no-op.
    val reactionWait = JobProgress.FeedSettleGate(JobProgress.FeedWait.REACTION)
    check(!reactionWait.observe("still ready", 100, verified = false))
    check(!reactionWait.observe("applied", 250, verified = true))
    check(reactionWait.observe("applied", 400, verified = true))
    val immediateReaction = JobProgress.FeedSettleGate(JobProgress.FeedWait.REACTION)
    check(!immediateReaction.observe("applied", 100, verified = true))
    check(immediateReaction.observe("applied", 250, verified = true))
    val scrolling = JobProgress.FeedSettleGate(JobProgress.FeedWait.SCROLL)
    check(!scrolling.observe("same feed", 250, verified = false))
    check(!scrolling.observe("same feed", 1_824, verified = false))
    check(scrolling.observe("same feed", 1_825, verified = false))
    val moving = JobProgress.FeedSettleGate(JobProgress.FeedWait.SCROLL)
    check(!moving.observe("in motion", 250, verified = true))
    check(!moving.observe("landed", 400, verified = true))
    check(moving.observe("landed", 550, verified = true))
    // The shared runner uses only vertical strokes; retries change the lane, not the axis.
    for (shortVideo in listOf(false, true)) {
        val strokes = (0..4).map { JobProgress.feedSwipe(shortVideo, it) }
        check(strokes.map { it.x }.distinct().size == 3)
        check(strokes.all { it.x in 0.30f..0.70f && it.fromY > it.toY })
        if (!shortVideo) check(strokes.all { it.fromY - it.toY < 0.5f })
    }
    val swipe = JobProgress.FeedMotion()
    swipe.observe(120, 0)
    check(swipe.evidence(100, 16) == JobProgress.SwipeEvidence.SIDEWAYS)
    swipe.observe(-1, 80)
    swipe.observe(0, 50)
    check(swipe.evidence(100, 16) == JobProgress.SwipeEvidence.DOWN_FEED)
    swipe.observe(0, -130)
    check(swipe.evidence(100, 16) == JobProgress.SwipeEvidence.SIDEWAYS)
    val sameView = JobProgress.FeedMovement.UNCHANGED
    val down = JobProgress.SwipeEvidence.DOWN_FEED
    check(JobProgress.anonymousReelAdvance(true, true, sameView, down))
    check(!JobProgress.anonymousReelAdvance(false, true, sameView, down)) // count-based run
    check(!JobProgress.anonymousReelAdvance(true, true, sameView, JobProgress.SwipeEvidence.SIDEWAYS))
    check(!JobProgress.anonymousReelAdvance(true, false, sameView, down))
    val card = JobProgress.reactionCardKey(setOf("Run A", "3.14 mi", "Yesterday"))
    check(card != null && JobProgress.sameReactionCard(card, JobProgress.reactionCardKey(setOf("Yesterday", "Run A", "3.14 mi"))))
    check(!JobProgress.sameReactionCard(card, JobProgress.reactionCardKey(setOf("Run B", "3.14 mi", "Yesterday"))))
    check(!JobProgress.sameReactionCard(null, null))

    // A run with blank or already-reacted screens can still reach a later eligible card.
    val feed = JobProgress.FeedTraversal()
    val viewports = listOf("first ready", "no controls 1", "no controls 2", "no controls 3",
        "already applied 1", "already applied 2", "already applied 3", "another ready")
    check(feed.enter(viewports.first()))
    for ((before, after) in viewports.zipWithNext()) {
        check(feed.movement(before, after) == JobProgress.FeedMovement.NEW_VIEWPORT)
        check(feed.enter(after))
    }
    check(feed.movement(viewports.last(), viewports.last()) == JobProgress.FeedMovement.UNCHANGED)
    check(feed.movement(viewports.last(), "") == JobProgress.FeedMovement.UNCHANGED)
    check(feed.movement(viewports.last(), viewports.first()) == JobProgress.FeedMovement.REVISIT)
    check(!feed.enter(viewports.first()))

    check(JobProgress.needsWebDestination("Look up all the mods 9 in the browser and open the download page"))
    check(JobProgress.needsWebDestination("Google NASA and open the first result"))
    check(!JobProgress.needsWebDestination("Look up pictures of cats"))
    check(!JobProgress.needsWebDestination("Search cats and take me to the images"))
    val web = JobProgress.WebProgress(true)
    check(!web.searchFinishes(false))
    check(web.unverified)
    val results = setOf("all", "images", "videos", "all the mods 9")
    check(!JobProgress.pageChanged(results, results))
    check(!JobProgress.pageChanged(results, results + setOf("new snippet", "more results")))
    check(!JobProgress.pageChanged(results, emptySet()))
    check(JobProgress.pageChanged(results, setOf("curseforge", "files", "versions")))
    web.failed("CurseForge    All the Mods 9")
    check(web.triedWithoutNavigation("curseforge all the mods 9"))
    check(!web.attemptsExhausted)
    web.failed("All the Mods 9 - Minecraft Modpacks")
    check(web.attemptsExhausted)
    web.navigated()
    check(!web.unverified && !web.attemptsExhausted)
    check(JobProgress.WebProgress(false).searchFinishes(false))
    check(JobProgress.explicitUrl("Please open https://example.com/docs?q=cat#results") ==
        "https://example.com/docs?q=cat#results")
    check(JobProgress.explicitUrl("Visit HTTPS://example.com") == "https://example.com")
    check(JobProgress.explicitUrl("Open https://user:secret@example.com") == null)
    check(JobProgress.explicitUrl("Open javascript:alert(1)") == null)
    check(JobProgress.explicitUrl("Open https://example.com and sign in") == null)
    check(JobProgress.explicitUrl("Open https:///example.com") == null)

    val oldTurn = ChatTurns.begin()
    val nextTurn = ChatTurns.begin()
    check(!ChatTurns.isCurrent(oldTurn) && ChatTurns.isCurrent(nextTurn))
    val jobs = JobLifetime()
    val oldJob = checkNotNull(jobs.start())
    check(jobs.start() == null)
    check(jobs.active(oldJob) && jobs.running())
    check(jobs.cancel() == oldJob && !jobs.current(oldJob))
    val nextJob = checkNotNull(jobs.start())
    check(!jobs.complete(oldJob) && jobs.active(nextJob))
    check(jobs.complete(nextJob) && !jobs.running() && jobs.current(nextJob))
    jobs.cancel()
    check(!jobs.current(nextJob)) // suppress an already-posted completion after Stop

    val visits = JobProgress.RecentVisits()
    val retries = JobProgress.FailureStreak()
    repeat(500) { index ->
        check(visits.add("screen $index"))
        // A transient miss in every viewport must not accumulate into a global cap.
        check(!retries.record(false))
        check(!retries.record(true))
    }
    check(!visits.add("screen 499")) // recent loop is detected
    check(!visits.contains("screen 0")) // long runs have bounded tracking memory
    check(!retries.record(false))
    check(!retries.record(false))
    check(retries.record(false))

    val message = JobProgress.Communication(JobProgress.CommunicationKind.MESSAGE, null)
    val call = JobProgress.Communication(JobProgress.CommunicationKind.CALL, null)
    check(JobProgress.communicationRequest("Message Sam and say I saw it on Discord") == message)
    check(JobProgress.communicationRequest("Text Sam: open Discord and message me back") == message)
    check(JobProgress.communicationRequest("Send Sam a text saying Hi") == message)
    check(JobProgress.communicationRequest("Open Discord and message Sam and say hello") == message.copy(explicitApp = "discord"))
    check(JobProgress.communicationRequest("Message Sam via WhatsApp and say Hi") == message.copy(explicitApp = "whatsapp"))
    check(JobProgress.communicationRequest("Call Sam") == call)
    check(JobProgress.communicationRequest("Call Sam on speaker") == call)
    check(JobProgress.communicationRequest("Open Discord and call Sam") == call.copy(explicitApp = "discord"))
    check(JobProgress.communicationRequest("Don't call Sam") == null)
    check(!JobProgress.communicationActionAllowed("open_app") && !JobProgress.communicationActionAllowed("search"))
    check(JobProgress.communicationActionAllowed("tap"))

    check(JobProgress.playbackLabel("Playback progress: 0:15 / 0:30", false)?.fraction == 0.5f)
    check(JobProgress.playbackLabel("0:15 / 0:30", false) == null)
    check(JobProgress.playbackLabel("0:15 / 0:30", true)?.fraction == 0.5f)
    check(JobProgress.playbackLabel("Video ended", false) == null)
    check(JobProgress.playbackLabel("Replay video", false, replayControl = true)?.ended == true)
    check(JobProgress.playbackRange(120f, 0f, 100f) == null)
    check(JobProgress.playbackRange(0f, -Float.MAX_VALUE, Float.MAX_VALUE)?.fraction == 0.5f)
    check(JobProgress.playbackRange(Float.NaN, 0f, 100f) == null)
    check(JobProgress.playbackLabel("0:40 of 0:30", true) == null)
    val watch = JobProgress.ReelWatch(0)
    check(watch.observe(15_000, JobProgress.Playback(0.45f)) == JobProgress.ReelWatchResult.WAITING)
    check(watch.observe(25_000, JobProgress.Playback(0.86f)) == JobProgress.ReelWatchResult.WAITING)
    check(watch.observe(27_000, JobProgress.Playback(0.11f)) == JobProgress.ReelWatchResult.FINISHED)
    val interruptedPlayback = JobProgress.ReelWatch(0)
    check(interruptedPlayback.observe(750, JobProgress.Playback(0.9f)) == JobProgress.ReelWatchResult.WAITING)
    check(interruptedPlayback.observe(1_500, null) == JobProgress.ReelWatchResult.WAITING)
    check(interruptedPlayback.observe(2_250, JobProgress.Playback(0.1f)) == JobProgress.ReelWatchResult.WAITING)
    val changedTimeline = JobProgress.ReelWatch(0)
    check(changedTimeline.observe(750, JobProgress.Playback(0.9f, durationSeconds = 30)) == JobProgress.ReelWatchResult.WAITING)
    check(changedTimeline.observe(1_500, JobProgress.Playback(0.1f, durationSeconds = 60)) == JobProgress.ReelWatchResult.WAITING)
    val unknown = JobProgress.ReelWatch(2_000)
    check(unknown.observe(19_999, null) == JobProgress.ReelWatchResult.WAITING)
    check(unknown.observe(20_000, null) == JobProgress.ReelWatchResult.ESTIMATED)
    check(JobProgress.ReelWatch(0).observe(3_000, JobProgress.Playback(1f)) == JobProgress.ReelWatchResult.FINISHED)

    // Instagram can add, remove, and restore labels while one reel keeps playing.
    val noMotion = JobProgress.SwipeEvidence.NONE
    val verticalMotion = JobProgress.SwipeEvidence.DOWN_FEED
    val sameReel = JobProgress.ReelIdentity.SAME
    val uncertainReel = JobProgress.ReelIdentity.UNCERTAIN
    val reelObservation = JobProgress.ReelObservation("first caption", 0)
    val reelTimer = JobProgress.ReelWatch(0)
    check(reelObservation.observe(0, "first caption", false, noMotion) == sameReel)
    check(reelObservation.observe(750, "caption + counts", true, noMotion) == uncertainReel)
    check(reelObservation.observe(1500, "caption + counts", true, noMotion) == uncertainReel)
    check(reelObservation.observe(7_500, "caption + more counts", true, noMotion) == uncertainReel)
    check(reelTimer.observe(7_500, null) == JobProgress.ReelWatchResult.WAITING)
    check(reelObservation.observe(18_000, "", false, noMotion) == uncertainReel) // readable window, sparse reel
    check(reelTimer.observe(18_000, null) == JobProgress.ReelWatchResult.ESTIMATED)
    check(reelObservation.observe(18_750, "first caption", false, noMotion) == sameReel)
    check(reelObservation.observe(19_500, "next caption", true, verticalMotion) == uncertainReel)
    check(reelObservation.observe(20_250, "next caption", true, verticalMotion) == JobProgress.ReelIdentity.ADVANCED)
    check(reelObservation.observe(26_249, null, false, noMotion) == uncertainReel)
    check(reelObservation.observe(26_250, null, false, noMotion) == JobProgress.ReelIdentity.UNREADABLE)

    println("Job progress tests passed.")
}
