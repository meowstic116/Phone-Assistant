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
    val instagram = InstagramReelsPolicy
    check(instagram.requested("com.instagram.android", "reels", bulk = false))
    check(!instagram.requested("com.instagram.android", "feed", bulk = false))
    check(!instagram.requested("com.instagram.android", "reels", bulk = true))
    check(!instagram.requested("com.facebook.orca", "reels", bulk = false))
    check(instagram.tab("clips_tab") == "reels" && instagram.tab("direct_inbox") == null)
    check(instagram.viewer("clips_viewer_view_pager", broad = true))
    check(!instagram.viewer("clips_viewer_view_pager", broad = false))
    check(instagram.blocked("direct_thread_message_list", broad = false))
    check(instagram.blocked("comments_bottom_sheet", broad = true))
    check(!instagram.blocked("direct_share_button", broad = false))
    check(!instagram.reelItem("direct_thread_message_text", inViewer = true))
    check(!instagram.reelItem("comment_text", inViewer = true))
    check(!instagram.reelItem("clips_caption", inViewer = false))
    check(instagram.reelItem("clips_caption", inViewer = true))
    check(instagram.readable(false, true, hasViewer = false, hasReelsTab = true, requireViewer = false))
    check(!instagram.readable(false, true, hasViewer = false, hasReelsTab = true, requireViewer = true))
    check(instagram.readable(false, true, hasViewer = true, hasReelsTab = false, requireViewer = true))
    check(!instagram.readable(true, true, hasViewer = true, hasReelsTab = true, requireViewer = false))
    check(!instagram.readable(false, false, hasViewer = true, hasReelsTab = true, requireViewer = false))
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
    check(JobProgress.reelPollMs(true) == 250L)
    check(JobProgress.reelPollMs(false) == 750L)
    check(JobProgress.inlineReelTimeline("progress_bar", "View", wide = true, nearBottom = true))
    check(JobProgress.inlineReelTimeline(null, "SeekBar", wide = true, nearBottom = true))
    check(!JobProgress.inlineReelTimeline("loading_progress", "ProgressBar", wide = true, nearBottom = true))
    check(!JobProgress.inlineReelTimeline(null, "SeekBar", wide = true, nearBottom = false))
    check(JobProgress.reelAdDisclosure(null, "Sponsored", description = false))
    check(JobProgress.reelAdDisclosure("ad_disclosure", "Sponsorisé", description = false))
    check(!JobProgress.reelAdDisclosure("caption_text", "Sponsored", description = false))
    check(!JobProgress.reelAdDisclosure(null, "This reel was sponsored by a friend", description = true))
    check(!JobProgress.reelAdDisclosure(null, "Paid partnership", description = true))
    val quickWrap = JobProgress.ReelWatch(0)
    check(quickWrap.observe(750, JobProgress.Playback(0.74f)) == JobProgress.ReelWatchResult.WAITING)
    check(quickWrap.observe(1_000, JobProgress.Playback(0.26f)) == JobProgress.ReelWatchResult.FINISHED)
    val smallJump = JobProgress.ReelWatch(0)
    check(smallJump.observe(750, JobProgress.Playback(0.68f)) == JobProgress.ReelWatchResult.WAITING)
    check(smallJump.observe(1_000, JobProgress.Playback(0.29f)) == JobProgress.ReelWatchResult.WAITING)
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
    check(unknown.observe(13_999, null) == JobProgress.ReelWatchResult.WAITING)
    check(unknown.observe(14_000, null) == JobProgress.ReelWatchResult.ESTIMATED)
    val stalledTimeline = JobProgress.ReelWatch(0)
    check(stalledTimeline.observe(1_000, JobProgress.Playback(0.20f)) == JobProgress.ReelWatchResult.WAITING)
    check(stalledTimeline.observe(12_000, null) == JobProgress.ReelWatchResult.WAITING)
    check(stalledTimeline.observe(18_000, null) == JobProgress.ReelWatchResult.ESTIMATED)
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
    check(reelTimer.observe(12_000, null) == JobProgress.ReelWatchResult.ESTIMATED)
    check(reelObservation.observe(18_750, "first caption", false, noMotion) == sameReel)
    check(reelObservation.observe(19_500, "next caption", true, verticalMotion) == uncertainReel)
    check(reelObservation.observe(20_250, "next caption", true, verticalMotion) == JobProgress.ReelIdentity.ADVANCED)
    check(reelObservation.observe(26_249, null, false, noMotion) == uncertainReel)
    check(reelObservation.observe(26_250, null, false, noMotion) == JobProgress.ReelIdentity.UNREADABLE)

    val untouchedToy = ToyLifetime(100L)
    check(!untouchedToy.expired(15_099L, held = false))
    check(!untouchedToy.expired(15_100L, held = true))
    check(untouchedToy.expired(15_100L, held = false)) // Dragging didn't extend the clock.
    val playedToy = ToyLifetime(100L)
    playedToy.tapped(4_000L)
    check(!playedToy.expired(15_100L, held = false))
    check(!playedToy.expired(63_999L, held = false))
    playedToy.tapped(63_999L)
    check(!playedToy.expired(123_998L, held = false))
    check(playedToy.expired(123_999L, held = false))

    val interrupted = mutableListOf<Boolean>()
    val ballChase = ToyApproach("scoreboard", interrupted::add)
    ballChase.finish(false)
    ballChase.finish(true) // A late arrival must not kick the ball after cancellation.
    check(ballChase.owner == "scoreboard" && interrupted == listOf(false))
    ToyApproach("scoreboard", interrupted::add).finish(true)
    check(interrupted == listOf(false, true)) // The next chase can still finish.
    val clock = ToyPlayClock()
    check(clock.frame(0L, paused = false) == 0L)
    check(clock.frame(33L, paused = false) == 33L)
    check(clock.frame(40L, paused = true) == 33L)
    check(clock.frame(30_000L, paused = false) == 33L)
    check(clock.frame(30_033L, paused = false) == 66L)
    check(clock.frame(120_000L, paused = false) == 166L) // No frames while the screen was off.
    playedToy.keepFor(100_000L, 10_000L)
    check(!playedToy.expired(123_998L, held = false)) // A short interruption doesn't shorten a tap grace.
    playedToy.keepFor(120_000L, 25_000L)
    check(!playedToy.expired(144_999L, held = false) && playedToy.expired(145_000L, held = false))

    val hops = BubbleHopSchedule(100L)
    val wrapLifetime = ToyLifetime(100L)
    check(!hops.due(849L) && hops.due(850L))
    hops.attempted(850L, started = false)
    check(!hops.reservesInput(900L) && !hops.due(949L) && hops.due(950L))
    check(!wrapLifetime.expired(20_000L, held = hops.keepsSheet(20_000L))) // Still waiting for a first jump.
    hops.attempted(40_000L, started = true)
    check(hops.reservesInput(40_400L) && !hops.due(40_400L))
    hops.finished(40_400L, landed = true)
    check(!hops.due(40_779L) && hops.due(40_780L) && hops.reservesInput(40_780L))
    hops.attempted(40_780L, started = true)
    hops.finished(41_100L, landed = false)
    check(!hops.reservesInput(41_101L))
    check(!hops.due(41_299L) && hops.due(41_300L))
    check(!wrapLifetime.expired(45_500L, held = hops.keepsSheet(45_500L))) // Pausing mid-sheet doesn't retire it.
    check(wrapLifetime.expired(71_100L, held = hops.keepsSheet(71_100L))) // But idle cleanup still works.

    val centreBubble = bubbleLanding(138f, 225f, 10f, petX = 100f, petWidth = 64, stageWidth = 360, density = 1f)
    check(centreBubble == DragMotion.Point(130.5f, 231.5f))
    check(centreBubble.x + ToyFooting.SHOE_CENTRE_DP == 138f)
    val rightBubble = bubbleLanding(337f, 250f, 10f, petX = 328f, petWidth = 64, stageWidth = 360, density = 1f)
    check(rightBubble.x <= 328f)
    check(kotlin.math.abs(rightBubble.x + ToyFooting.SHOE_CENTRE_DP - 337f) <= ToyFooting.SHOE_WIDTH_DP / 2f)
    val leftBubble = bubbleLanding(25f, 180f, 10f, petX = 35f, petWidth = 64, stageWidth = 360, density = 1f)
    check(leftBubble.x == 32.5f && leftBubble.y == 186.5f)

    val bubbles = PopSheet()
    check(bubbles.total == 20 && bubbles.nextBubble { 16 } == 16)
    check((0 until bubbles.total).map { rank -> bubbles.nextBubble { rank } } == (0 until bubbles.total).toList())
    check(bubbles.pop(16, byPip = false) == PopSheet.Milestone.FIRST)
    check(bubbles.pop(16, byPip = true) == null && bubbles.pipCount == 0)
    check((0 until bubbles.total - 1).map { rank -> bubbles.nextBubble { rank } } ==
        (0 until bubbles.total).filterNot { it == 16 })
    val pipMilestones = mutableListOf<PopSheet.Milestone>()
    val bubbleRandom = kotlin.random.Random(7)
    while (true) {
        val index = bubbles.nextBubble(bubbleRandom::nextInt) ?: break
        pipMilestones += checkNotNull(bubbles.pop(index, byPip = true))
    }
    check(bubbles.nextBubble { error("No roll for an empty sheet") } == null)
    check(bubbles.count == 20 && bubbles.pipCount == 19)
    check(pipMilestones.filter { it != PopSheet.Milestone.NONE } == listOf(
        PopSheet.Milestone.FIRST, PopSheet.Milestone.FIVE, PopSheet.Milestone.TEN,
        PopSheet.Milestone.FIFTEEN, PopSheet.Milestone.COMPLETE
    ))
    check(hopLift(0f) == 0f && hopLift(0.5f) == 1f && hopLift(1f) == 0f)

    val byPip = PlantGrowth.Waterer.PIP
    val byUser = PlantGrowth.Waterer.USER
    val garden = PlantGrowth()
    repeat(PlantGrowth.MAX_STAGE) { stage ->
        val now = stage * 2_000L
        check(garden.thirsty && garden.stage == stage && garden.water == 0)
        repeat(PlantGrowth.WATER_PER_STAGE) { drop ->
            check(garden.water(now + drop, if (stage == 0 && drop == 0) byUser else byPip))
        }
        check(garden.wateredByUser && garden.growing && !garden.thirsty && garden.water == PlantGrowth.WATER_PER_STAGE)
        check(!garden.water(now + 3, byUser) && !garden.water(now + 3, byPip))
        check(!garden.advance(now + 621))
        check(garden.advance(now + 622) && !garden.advance(now + 622))
    }
    check(garden.blooming && !garden.water(10_000L, byUser) && !garden.advance(10_000L))

    val unattended = PlantGrowth()
    check(!unattended.blooming && !unattended.waitingForUser)
    repeat(PlantGrowth.MAX_STAGE - 1) { stage ->
        val now = stage * 2_000L
        repeat(PlantGrowth.WATER_PER_STAGE) { check(unattended.water(now + it, byPip)) }
        check(!unattended.water(now + 50L, byUser) && !unattended.wateredByUser) // A tap during growth adds no water.
        check(unattended.advance(now + 622L))
    }
    repeat(PlantGrowth.WATER_PER_STAGE - 1) { check(unattended.water(8_000L + it, byPip)) }
    check(unattended.waitingForUser && !unattended.pipMayWater && unattended.thirsty)
    repeat(5) { check(!unattended.water(9_000L + it, byPip) && !unattended.advance(10_000L + it)) }
    check(!unattended.blooming && unattended.water(11_000L, byUser))
    check(!unattended.waitingForUser && !unattended.blooming)
    check(unattended.advance(11_620L) && unattended.blooming && unattended.wateredByUser)
    check(PlantGrowth.FIRST_POUR_DELAY_MS >= 2_000L && PlantGrowth.PIP_POUR_PAUSE_MS >= 3_000L)

    val drag = DragMotion(density = 2f)
    drag.begin(200f, 400f, 180f, 370f, 1000L)
    check(!drag.beyondSlop(202f, 403f, 8f))
    check(drag.beyondSlop(212f, 403f, 8f))
    check(drag.target(260f, 390f) == DragMotion.Point(240f, 360f))
    drag.sample(230f, 390f, 1030L)
    drag.sample(280f, 380f, 1060L)
    val glide = checkNotNull(drag.coast(1060L))
    check(glide.x > 0f && glide.y < 0f && kotlin.math.hypot(glide.x, glide.y) <= 104.01f)
    check(drag.coast(1141L) == null) // A pause before lifting is a placement, not a fling.
    drag.begin(10f, 10f, 0f, 0f, 2000L)
    drag.sample(400f, 10f, 2040L)
    check(checkNotNull(drag.coast(2040L)).x == 104f)
    check(DragMotion.coastFraction(0f) == 0f && DragMotion.coastFraction(1f) == 1f)
    check(DragMotion.coastFraction(0.25f) > 0.5f) // Quick start, gentle landing.

    val bootStart = PipTransition.pose(entering = true, fraction = 0f)
    val bootEnd = PipTransition.pose(entering = true, fraction = 1f)
    check(bootStart.opacity == 0f && bootStart.sx < 1f)
    check(bootEnd.opacity == 1f && bootEnd.sx == 1f && bootEnd.sy == 1f && bootEnd.spark == 0f)
    val exitStart = PipTransition.pose(entering = false, fraction = 0f)
    val exitMiddle = PipTransition.pose(entering = false, fraction = 0.5f)
    val exitEnd = PipTransition.pose(entering = false, fraction = 1f)
    check(exitStart.opacity == 1f && exitStart.sx == 1f && exitStart.sy == 1f)
    check(exitMiddle.spark > 0f && exitMiddle.sy < exitStart.sy)
    check(exitEnd.opacity == 0f && exitEnd.spark == 0f)

    check(JobProgress.weatherRequest("What's the weather like today?") == JobProgress.WeatherRequest(time = "today"))
    check(JobProgress.weatherRequest("What was the weather like today?") == JobProgress.WeatherRequest(time = "today"))
    check(JobProgress.weatherRequest("Tell me what the weather was like today.") == JobProgress.WeatherRequest(time = "today"))
    check(JobProgress.weatherRequest("What will the weather be like tomorrow?")?.search == "weather tomorrow near me")
    check(JobProgress.weatherRequest("Check today's weather in Colorado Springs.")?.search == "weather today colorado springs")
    check(JobProgress.weatherRequest("Weather in Denver tomorrow")?.search == "weather tomorrow denver")
    check(JobProgress.weatherRequest("Forecast for today")?.place == null)
    check(JobProgress.weatherRequest("Open my weather app")?.appOnly == true)
    check(JobProgress.routePhoneLocally("Will it rain today?") && JobProgress.routesWithoutAccessibility("Weather today"))
    check(JobProgress.weatherRequest("What is weather?") == null)
    check(JobProgress.weatherRequest("Why does weather change?") == null)
    check(!JobProgress.routesWithoutAccessibility("Message Alex and say what the weather was like today"))
    check(JobProgress.weatherAppRank("Météo", "com.sec.android.daemonapp") == 0)
    check(JobProgress.weatherAppRank("Weather", "com.example.weather") == 1)
    check(JobProgress.weatherAppRank("Weather wallpapers", "com.example.pictures") == null)

    for (motion in ToyMotion.values()) {
        val away = motion.pose(entering = true, fraction = 0f)
        val open = motion.pose(entering = true, fraction = 1f)
        val close = motion.pose(entering = false, fraction = 0f)
        val packed = motion.pose(entering = false, fraction = 1f)
        check(away.progress == 0f && away.alpha == 0f && away.xScale < 0.25f)
        check(open.progress == 1f && open.alpha == 1f && open.xScale == 1f && open.yScale == 1f && open.lift == 0f)
        check(close.progress == 0f && close.alpha == 1f && close.xScale == 1f)
        check(packed.progress == 1f && packed.alpha == 0f && packed.xScale < 0.1f)
    }
    check(ToyMotion.BALL.pose(true, 0.4f).lift > ToyMotion.STAND.pose(true, 0.4f).lift)
    check(ToyMotion.CANVAS.pose(true, 0f).yScale < ToyMotion.PLANT.pose(true, 0f).yScale)

    val spotify = SpotifyQuickPlan
    val playbackRequest = "Play the emo rap Playlist on Spotify"
    check(spotify.playlistRequest(playbackRequest) == "emo rap")
    check(JobProgress.routePhoneLocally(playbackRequest) && !JobProgress.routesWithoutAccessibility(playbackRequest))
    check(spotify.playlistRequest("Please play my playlist called “Emo Rap Mix” in Spotify!") == "Emo Rap Mix")
    check(spotify.playlistRequest("Play the \"Rock, Pop & Jazz\" playlist on Spotify") == "Rock, Pop & Jazz")
    check(spotify.playlistRequest("Play the emo rap song on Spotify") == null)
    check(spotify.playlistRequest("Play the emo rap playlist on YouTube") == null)
    check(spotify.playlistRequest("Play emo rap and then share the playlist on Spotify") == null)
    check(spotify.playlistRequest("How do I play the emo rap playlist on Spotify?") == null)
    check(spotify.isHome("Home, Tab 1 of 4", selected = true))
    check(!spotify.isHome("Home, Tab 1 of 4", selected = false))
    check(spotify.isRecentHeading("Recently Played"))
    check(spotify.isCard("Emo Rap Mix, Playlist, Spotify", "Emo Rap Mix"))
    check(!spotify.isCard("Emo Rap Mix", "Emo Rap"))
    check(!spotify.isCard("Emo Rap, Song", "Emo Rap"))
    check(spotify.unique(listOf("Emo Rap", "Emo Rap, Playlist")) { spotify.isCard(it, "Emo Rap") } == null)
    check(spotify.isPlay("Shuffle Play playlist") && !spotify.isPlay("Play"))
    check(spotify.isPause("Pause playlist") && !spotify.isPause("Pause"))

    check(plan(DoomscrollMotion.COMMAND) == browse)
    check(plan("Doomscroll") == browse)
    check(plan("Doomscroll for 12 reels") == browse.copy(browseLimit = JobProgress.BrowseLimit(screens = 12)))
    check(plan("Doomscroll for 45 seconds") == browse.copy(browseLimit = JobProgress.BrowseLimit(durationMs = 45_000)))
    check(JobProgress.feedRequest("Tell me about doomscrolling") == null)
    val cue = DoomscrollMotion.Cue.WATCH
    val watching = checkNotNull(DoomscrollMotion.pose(cue, 200L, animated = true))
    check(watching.lean > 0f && watching.gazeX > 0f && watching.gazeY < 0f && watching.arm == 0f)
    val swipeCue = DoomscrollMotion.Cue.SWIPE
    val thumbDown = checkNotNull(DoomscrollMotion.pose(swipeCue, 70L, animated = true))
    val thumbUp = checkNotNull(DoomscrollMotion.pose(swipeCue, 220L, animated = true))
    check(thumbDown.arm == 1f && thumbUp.handY < thumbDown.handY && thumbUp.trail > 0f)
    val settled = checkNotNull(DoomscrollMotion.pose(swipeCue, DoomscrollMotion.SWIPE_MS, animated = true))
    check(settled.arm == 0f && settled.lean > 0f && settled.trail == 0f)
    val stillLook = checkNotNull(DoomscrollMotion.pose(swipeCue, 220L, animated = false))
    check(stillLook.lean == 0f && stillLook.arm == 0f && stillLook.trail == 0f)
    check(DoomscrollMotion.pose(DoomscrollMotion.Cue.CLEAR, 200L, animated = true) == null)

    val launcherHome = setOf("workspace", "hotseat", "qsb")
    check(HomeScreenPolicy.isWorkspace(launcherHome))
    check(HomeScreenPolicy.isWorkspace(setOf("home_screen", "cell_layout")))
    check(!HomeScreenPolicy.isWorkspace(emptySet()))
    check(!HomeScreenPolicy.isWorkspace(setOf("workspace")))
    check(!HomeScreenPolicy.isWorkspace(setOf("hotseat", "qsb")))
    for (panel in listOf("apps_view", "apps_screen", "overview_panel", "recents_view", "finder", "folder_content",
        "widget_picker", "popup_container", "discover_feed", "home_edit_layout")) {
        check(!HomeScreenPolicy.isWorkspace(launcherHome + panel))
    }

    check(SetupPolicy.Permission.entries.map { it.name } == listOf(
        "NOTIFICATIONS", "NOTIFICATION_ACCESS", "OVERLAY", "MICROPHONE", "USAGE", "ACCESSIBILITY",
        "NEARBY_DEVICES", "LOCATION"))
    val setup = SetupPolicy
    val welcomeStep = SetupPolicy.Step.WELCOME
    val apiStep = SetupPolicy.Step.API
    val permissionStep = SetupPolicy.Step.PERMISSIONS
    check(setup.resumeStep(null, verified = false) == welcomeStep)
    check(setup.resumeStep(apiStep, verified = false) == apiStep)
    check(setup.resumeStep(permissionStep, verified = false) == apiStep)
    check(setup.resumeStep(permissionStep, verified = true) == permissionStep)
    check(setup.resumeStep(null, verified = true) == permissionStep)
    val startedReplay = setup.proofForRun(replay = true, storedProof = true, replayProof = false)
    check(setup.resumeStep(null, verified = startedReplay) == welcomeStep)
    check(setup.resumeStep(permissionStep, verified = startedReplay) == apiStep)
    check(setup.proofForRun(replay = true, storedProof = false, replayProof = true))
    check(!setup.proofForRun(replay = false, storedProof = false, replayProof = true))
    check(setup.validApiKey("api-token_123"))
    check(!setup.validApiKey("bad token"))
    check(!setup.validApiKey("bad\r\nheader"))
    check(!setup.validApiKey("a".repeat(4097)))

    check(java.io.StringReader("hello").readBounded(5) == "hello")
    check(java.io.StringReader("").readBounded(0).isEmpty())
    check(runCatching { java.io.StringReader("a".repeat(8192)).readBounded(6000) }.exceptionOrNull() is java.io.IOException)
    val drip = object : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int { cbuf[off] = 'x'; return 1 }
        override fun close() = Unit
    }
    var clockNanos = 0L
    check(runCatching { drip.readBounded(100, budgetNanos = 12L) { ++clockNanos } }
        .exceptionOrNull() is java.net.SocketTimeoutException)
    Thread.currentThread().interrupt()
    try {
        check(runCatching { drip.readBounded(100) }.exceptionOrNull() is java.io.InterruptedIOException)
        check(Thread.currentThread().isInterrupted)
    } finally { Thread.interrupted() }


    check(OverlayBounds.clamp(100f, 20f, -20f) == 20f)
    check(OverlayBounds.clamp(-100f, 0f, 80f) == 0f)
    check(OverlayBounds.panelY(200, 86, 40, 8, 20, 600) == 152)
    check(OverlayBounds.panelY(35, 86, 40, 8, 20, 600) == 129)
    check(OverlayBounds.panelY(45, 86, 150, 8, 20, 160) == 20)

    check(setup.validUrl("https://generativelanguage.googleapis.com/v1beta/openai"))
    check(setup.validUrl("https://api.example.org:9443/v1/chat/completions"))
    check(!setup.validUrl("http://api.example.org/v1"))
    check(!setup.validUrl("https://user:password@api.example.org/v1"))
    check(!setup.validUrl("https://api.example.org/v1?trace=1"))
    check(!setup.validUrl("https://api.example.org/v1#test"))
    check(setup.endpoint("https://api.example.org/v1/") == "https://api.example.org/v1/chat/completions")
    check(setup.endpoint("https://api.example.org/v1/chat/completions/") == "https://api.example.org/v1/chat/completions")
    check(setup.response(200, null, true) == SetupPolicy.Check.OK)
    check(setup.response(200, null, false) == SetupPolicy.Check.RESPONSE)
    check(setup.response(401, null, false) == SetupPolicy.Check.KEY)
    check(setup.response(403, null, false) == SetupPolicy.Check.KEY)
    check(setup.response(400, "API_KEY_INVALID", false) == SetupPolicy.Check.KEY)
    check(setup.response(404, "model_not_found", false) == SetupPolicy.Check.MODEL)
    check(setup.response(429, null, false) == SetupPolicy.Check.LIMIT)
    check(setup.response(502, null, false) == SetupPolicy.Check.PROVIDER)
    check(setup.response(302, null, false) == SetupPolicy.Check.URL)

    val potX = 180f
    val potTop = 240f
    val leftHand = WateringPose.grip(potX, potTop, true, 1f)
    val rightHand = WateringPose.grip(potX, potTop, false, 1f)
    check(leftHand.y == rightHand.y && leftHand.x + rightHand.x == 2 * potX)
    val leftStance = WateringPose.stance(leftHand, true, 1f)
    val rightStance = WateringPose.stance(rightHand, false, 1f)
    check(leftHand.x - leftStance.x == 27f && rightStance.x - rightHand.x == 27f)
    check(leftStance.y == rightStance.y && leftStance.y == potTop + WateringPose.POT_DEPTH_DP)
    val tilted = WateringPose.tilt(0.5f)
    val leftSpout = WateringPose.spout(leftHand, true, 1f, tilted)
    val rightSpout = WateringPose.spout(rightHand, false, 1f, tilted)
    check(kotlin.math.abs(leftSpout.x + rightSpout.x - 2 * potX) < 0.01f)
    check(leftSpout.y == rightSpout.y && leftSpout.y in leftHand.y..potTop)
    check(WateringPose.spout(leftHand, true, 1f, 0f).y < leftSpout.y)
    check(WateringPose.grip(potX * 2, potTop * 2, true, 2f).x == leftHand.x * 2)

    check(PreferredName.parse("Can you please refer to me as ‘Ren’?") == "Ren")
    check(PreferredName.parse("Pip, please address me as Ana María from now on.") == "Ana María")
    check(PreferredName.parse("Call me Nova") == "Nova")
    check(PreferredName.parse("Remember my name is D’Angelo.") == "D’Angelo")
    check(PreferredName.parse("I prefer to be called ‘J. K.’ instead.") == "J. K.")
    check(PreferredName.parse("Going forward, call me 李雷.") == "李雷")
    for (notAName in listOf("Call me back later", "Call me at 555-0100", "Call me when you get home",
        "Call me a cab", "Text Sam and say call me Nova", "Don't call me Nova", "My friend's name is Nova",
        "Call me Nova and help with the weather", "Refer to me as Nova. Send a message")) {
        check(PreferredName.parse(notAName) == null) { notAName }
    }
    check(JobProgress.communicationRequest("Call Sam") != null)
    val namedNotes = PreferredName.save(listOf("My name is Sam.", "Preferred name: Ren", "I like music."), "Nova", 40)
    check(namedNotes == listOf("My name is Sam.", "I like music.", "Preferred name: Nova"))
    check(PreferredName.save(namedNotes, "Nova", 40) == namedNotes)

    val suggestions = MemorySuggestion
    val nickname = "Everybody knows me as Roo"
    val learnedName = checkNotNull(suggestions.accept("Nice to meet you. $nickname!", "preferred_name", nickname, "Roo"))
    check(learnedName.text == "Roo" && learnedName.preferredName)
    check(suggestions.accept(nickname, "preferred_name", nickname, "Robin") == null)
    check(suggestions.merge(listOf("Preferred name: Nova"), learnedName, 40) == listOf("Preferred name: Nova"))
    check(suggestions.merge(emptyList(), learnedName, 40) == listOf("Preferred name: Roo"))
    val reading = "I’d pick paper books over an ebook any day."
    val learnedFact = checkNotNull(suggestions.accept(reading, "preference", reading))
    check(learnedFact.text == reading)
    check(suggestions.merge(listOf(reading.trimEnd('.')), learnedFact, 40) == listOf(reading.trimEnd('.')))
    check(suggestions.accept("I used to like paper books but not anymore.", "preference", "I like paper books") == null)
    check(suggestions.accept("Should I pick paper books?", "preference", "Should I pick paper books") == null)
    check(suggestions.accept("I save my password in a notebook.", "routine", "I save my password in a notebook.") == null)
    check(!suggestions.maySuggest("Please don’t remember this. $reading"))
    check(!suggestions.maySuggest("Send it to me at test@example.com"))
    check(suggestions.explicitNote("Remember that I prefer vinyl.") == "I prefer vinyl.")
    check(suggestions.explicitNote("Remember to buy milk") == null)
    check(suggestions.explicitNote("Remember that my password is orange") == null)
    check(suggestions.splitReply("Fair enough.\nPIP_MEMORY: {\"kind\":\"preference\"}") ==
        MemorySuggestion.Reply("Fair enough.", "{\"kind\":\"preference\"}"))
    check(suggestions.splitReply("{\"action\":\"delegate\",\"task\":\"Open Maps\"}").payload == null)
    check(suggestions.splitReply("Fair enough.\nPIP_MEMORY: x").payload == null)

    check(MessageNotifications.senderRequested("What did Maya text me?") == "Maya")
    check(MessageNotifications.senderRequested("Hey Pip, what did O’Connor message me just now?") == "O’Connor")
    check(MessageNotifications.senderRequested("What did Maya text me and then reply?") == null)
    check(MessageNotifications.senderRequested("Repeat ‘what did Maya text me?’") == null)
    check(MessageNotifications.senderRequested("Text Maya that I’m running late") == null)
    check(MessageNotifications.request("What did Maya text me on WhatsApp?") == MessageNotifications.Request("Maya", "WhatsApp"))
    check(MessageNotifications.request("What did Maya text me and then reply?") == null)
    val exactQuote = "[Check this] *not a stage direction*\nMeet me at 6."
    check(SpeechCaption.literal(exactQuote, 100)?.text == exactQuote)
    check(SpeechCaption.literal(exactQuote, 8) == null)
    val prior = MessageNotifications.Preview("Maya Chen", "Earlier preview", 100, 400)
    val latest = MessageNotifications.Preview("Maya Chen", "Meet at six", 200, 300)
    val other = MessageNotifications.Preview("Amaya Davis", "Unrelated message", 500, 500)
    val ambiguous = MessageNotifications.Preview("Maya Patel", "Different Maya", 600, 600)
    check(MessageNotifications.answer("maya", listOf(other, latest, prior)) == "From Maya Chen: “Meet at six”")
    check(MessageNotifications.answer("maya", listOf(latest, ambiguous)).startsWith("More than one"))
    check(MessageNotifications.answer("MAYA  CHEN", listOf(latest, ambiguous)) == "From Maya Chen: “Meet at six”")
    check(MessageNotifications.answer("maya", listOf(other)).startsWith("I couldn’t find"))
    val noPreview = latest.copy(body = null, sentAt = 0, postedAt = 450)
    check(MessageNotifications.answer("maya", listOf(prior, latest, noPreview)).contains("doesn’t include"))
    check(MessageNotifications.excerpt("hi\nthere\u202E", 20) == "hi there")
    check(MessageNotifications.excerpt("abcd", 3) == "abc…")

    check(NowPlaying.requested("What song am I listening to right now?"))
    check(NowPlaying.requested("Hey Pip, what’s playing?"))
    check(NowPlaying.requested("Could you tell me what song is playing now?"))
    check(!NowPlaying.requested("What music should I play?"))
    check(!NowPlaying.requested("What’s playing and skip it"))
    val pausedSong = NowPlaying.Track(false, "Old song", "Old artist")
    val currentSong = NowPlaying.Track(true, "Current song", "Current artist")
    check(NowPlaying.answer(listOf(pausedSong, currentSong)) == "Now playing: “Current song” by Current artist.")
    check(NowPlaying.answer(listOf(pausedSong)).contains("isn’t reporting any media"))
    check(NowPlaying.answer(listOf(NowPlaying.Track(true, null, null))).contains("sharing its title"))
    check(NowPlaying.answer(listOf(currentSong, currentSong.copy(title = "Other song"))).contains("more than one"))
    check(PhoneContext.mayInspect("What’s the date shown on this screen?"))
    check(PhoneContext.mayInspect("Can you check the price on this page?"))
    check(!PhoneContext.mayInspect("I saw the price on this page"))
    check(!PhoneContext.mayInspect("What is the capital of Colorado?"))
    check(!PhoneContext.permitsWindow("com.android.systemui"))

    val watchFrom = DragMotion.Point(30f, 40f)
    val watchSpot = DragMotion.Point(410f, 810f)
    val walk = JobApproach(watchFrom, watchSpot, 1f)
    check(!walk.arrived && walk.durationMs in 130..620)
    val firstStep = walk.advance(33L)
    check(firstStep.x in watchFrom.x..watchSpot.x && firstStep.y in watchFrom.y..watchSpot.y)
    check(firstStep != watchFrom && firstStep != watchSpot)
    var lastStep = firstStep
    repeat(20) { lastStep = walk.advance(33L) }
    check(walk.arrived && lastStep == watchSpot)
    check(JobApproach(watchSpot, watchSpot, 1f).arrived)
    val arrives = JobApproachGate()
    arrives.finish(true)
    check(arrives.await { true } && !arrives.await { false })
    val stopped = JobApproachGate()
    stopped.finish(false)
    stopped.finish(true)
    check(!stopped.await { true })

    val pickedUp = PickupMotion.pose(1f, 0f, 0L, 0f, true)
    check(pickedUp.scale == PickupMotion.SCALE && pickedUp.carry == 0f && pickedUp.tilt == 0f)
    val draggedRight = PickupMotion.pose(1f, 1f, 0L, 1f, true)
    val draggedLeft = PickupMotion.pose(1f, 1f, 0L, -1f, true)
    check(draggedRight.tilt > 0f && draggedLeft.tilt < 0f)
    check(draggedRight.leftFoot != PickupMotion.pose(1f, 1f, 100L, 1f, true).leftFoot)
    val release = PickupMotion.blend(1f, 0f, 65L, PickupMotion.RELEASE_MS)
    check(release in 0.01f..0.99f)
    check(PickupMotion.blend(release, 1f, 0L, PickupMotion.LIFT_MS) == release)
    check(PickupMotion.blend(1f, 0f, PickupMotion.RELEASE_MS, PickupMotion.RELEASE_MS) == 0f)
    check(PickupMotion.pose(0f, 0f, 500L, 1f, true).scale == 1f)
    check(PickupMotion.pose(1f, 1f, 100L, 1f, false) == PickupMotion.pose(1f, 1f, 0L, -1f, false))

    val varieties = PlantSpecies.values()
    check(varieties.size == 11 && varieties.map { it.id }.toSet().size == varieties.size)
    check(varieties.map { it.discoveryColor }.toSet().size == varieties.size)
    val firstBloom = PlantDiscovery.record(0)
    check(firstBloom == PlantDiscovery.Bloom(1, true))
    check(PlantDiscovery.record(firstBloom.count) == PlantDiscovery.Bloom(2, false))
    check(PlantDiscovery.record(Long.MAX_VALUE) == PlantDiscovery.Bloom(Long.MAX_VALUE, false))
    check(PlantDiscovery.shimmer(625L) == 0.5f && PlantDiscovery.shimmer(1_500L) == null)
    check(PlantDiscovery.shimmer(2_700L) == 0f)
    val blooms = mutableMapOf<PlantSpecies, Long>()
    for (variety in listOf(PlantSpecies.STAR, PlantSpecies.COSMOS, PlantSpecies.STAR)) {
        blooms[variety] = PlantDiscovery.record(blooms[variety] ?: 0).count
    }
    check(blooms[PlantSpecies.STAR] == 2L && blooms[PlantSpecies.COSMOS] == 1L && PlantSpecies.BLUEBELL !in blooms)

    val regularPlants = PlantSelection.regular
    check(regularPlants.size == 10 && PlantSpecies.GOLDEN !in regularPlants)
    check(PlantSpecies.COSMOS.id == "pocket_cosmos" && PlantSpecies.STAR.id == "lemon_star" &&
        PlantSpecies.BLUEBELL.id == "blueberry_bell")
    val completeCollection = regularPlants.associateWith { 1L }
    check(!PlantSelection.goldenEligible(blooms))
    check(PlantSelection.collectionProgress(emptyMap()) == (0 to 10))
    // Even a deliberately favorable roll cannot yield gold if any one variety is missing.
    for (missing in regularPlants) {
        val incomplete = completeCollection - missing
        check(!PlantSelection.goldenEligible(incomplete))
        check(PlantSelection.collectionProgress(incomplete) == (9 to 10))
        check(PlantSelection.collectionProgress(incomplete + (PlantSpecies.GOLDEN to 7L)) == (9 to 10))
        check(PlantSelection.pick(incomplete) { 0 } in regularPlants)
        check(!PlantSelection.goldenEligible(incomplete + (PlantSpecies.GOLDEN to 7L)))
    }
    check(PlantSelection.goldenEligible(completeCollection))
    check(PlantSelection.collectionProgress(completeCollection) == (10 to 11))
    check(PlantSelection.collectionProgress(completeCollection + (PlantSpecies.GOLDEN to 1L)) == (11 to 11))
    check(PlantSelection.pick(completeCollection) { 0 } == PlantSpecies.GOLDEN)
    val outcomes = (0 until PlantSelection.GOLDEN_ROLLS).map { roll -> PlantSelection.pick(completeCollection) { roll } }
    check(outcomes.count { it == PlantSpecies.GOLDEN } == 1)
    check(regularPlants.indices.map { index -> PlantSelection.pick(emptyMap()) { index } } == regularPlants)

    // Phone-width pitch: the old top-left board overlapped the centered goal.
    val tuning = SoccerBallTuning
    check(tuning.PLAYER_KICK < 1150f && tuning.PIP_KICK < 720f && tuning.SAVE_KICK < tuning.PIP_KICK)
    check(tuning.retainedSpeed(1f) in 0.40f..0.45f)
    check(kotlin.math.abs(tuning.retainedSpeed(0.033f) * tuning.retainedSpeed(0.033f) -
        tuning.retainedSpeed(0.066f)) < 0.0001f)
    check(tuning.REBOUND * tuning.REBOUND < 0.30f) // Two impacts should take most of the speed out.
    check(tuning.PIP_KICK * tuning.rollSeconds(2f) > 350f) // A clean shot can still reach from midfield.

    val smallPitch = SoccerAi.Pitch(21f, 339f, 45f, 700f, 122f, 238f, 679f)
    val pipFeet = DragMotion.Point(158f, 385f)
    val rolling = SoccerAi.Ball(DragMotion.Point(178f, 280f), 190f, -30f)
    val chase = SoccerAi.plan(pipFeet, rolling, smallPitch, userPressing = false)
    check(chase.role == SoccerAi.Role.CHASE && chase.feet.x > rolling.centre.x)
    val fastWide = SoccerAi.Ball(DragMotion.Point(302f, 246f), 950f, -20f)
    val rebound = SoccerAi.plan(pipFeet, fastWide, smallPitch, userPressing = false)
    check(rebound.feet.x in smallPitch.left..smallPitch.right && rebound.feet.y in smallPitch.top..smallPitch.bottom)
    val heldFar = SoccerAi.Ball(DragMotion.Point(65f, 235f), 0f, 0f)
    check(SoccerAi.plan(pipFeet, heldFar, smallPitch, userPressing = true).role == SoccerAi.Role.GUARD)
    check(SoccerAi.plan(heldFar.centre, heldFar, smallPitch, userPressing = true).role == SoccerAi.Role.CHASE)
    val shot = SoccerAi.Ball(DragMotion.Point(155f, 460f), 120f, 770f)
    val guard = SoccerAi.plan(DragMotion.Point(280f, 615f), shot, smallPitch, userPressing = false)
    check(guard.role == SoccerAi.Role.GUARD && guard.feet.x > shot.centre.x && guard.feet.y < smallPitch.goalY)
    val stillBall = DragMotion.Point(180f, 300f)
    val shootingFeet = SoccerAi.kickStance(stillBall)
    check(shootingFeet == DragMotion.Point(180f, 317f))
    check(SoccerAi.kickContact(shootingFeet, stillBall, stillBall) != null)
    check(SoccerAi.kickContact(DragMotion.Point(180f, 280f), stillBall, stillBall) == null) // Above the ball.
    check(SoccerAi.kickContact(DragMotion.Point(180f, 300f), stillBall, stillBall) == null) // On its centre, not the edge.
    val lowestBall = DragMotion.Point(180f, smallPitch.bottom)
    val lowStance = SoccerAi.plan(pipFeet, SoccerAi.Ball(lowestBall, 0f, 0f), smallPitch, false).feet
    check(lowStance.y == 717f && SoccerAi.kickContact(lowStance, lowestBall, lowestBall) != null)
    val wallBall = DragMotion.Point(21f, 300f)
    check(SoccerAi.kickContact(DragMotion.Point(32f, 317f), wallBall, wallBall) != null) // Window stops at the wall.
    check(SoccerAi.kickContact(DragMotion.Point(48f, 317f), wallBall, wallBall) == null)
    check(SoccerAi.headerContact(DragMotion.Point(180f, 45f), DragMotion.Point(180f, 42f),
        DragMotion.Point(180f, 110f), minimumFeetY = 110f))
    check(!SoccerAi.headerContact(DragMotion.Point(215f, 45f), DragMotion.Point(180f, 42f),
        DragMotion.Point(180f, 110f), minimumFeetY = 110f))
    val fromShot = DragMotion.Point(140f, 617f)
    val toShot = DragMotion.Point(200f, 705f)
    val goalX = SoccerAi.crossingX(fromShot, toShot, smallPitch.goalY) ?: error("shot should cross the goal")
    check(goalX in 181f..183f)
    val atLine = DragMotion.Point(goalX, smallPitch.goalY)
    check(SoccerAi.kickContact(DragMotion.Point(161f, 663f), fromShot, atLine) != null)
    check(SoccerAi.kickContact(DragMotion.Point(285f, 663f), fromShot, atLine) == null)
    val tooLate = DragMotion.Point(toShot.x, toShot.y + 17f)
    check(SoccerAi.kickContact(tooLate, fromShot, atLine) == null)
    check(SoccerAi.kickContact(tooLate, fromShot, toShot) != null)
    check(SoccerAi.crossingX(fromShot, toShot, 720f) == null)
    check(SoccerAi.aim(DragMotion.Point(130f, 360f), 180f, 60f, 0f) > 0f)

    val phoneScore = SoccerLayout.scoreboard(360f, 24f, 109f, 78f, 124f, 10f, 8f)
    check(phoneScore == DragMotion.Point(10f, 86f))
    val wideScore = SoccerLayout.scoreboard(480f, 24f, 169f, 78f, 124f, 10f, 8f)
    check(wideScore == DragMotion.Point(10f, 34f) && wideScore.x + 124f + 8f <= 169f)
    check(SoccerLayout.scoreboard(426f, 44f, 142f, 98f, 124f, 10f, 8f) == DragMotion.Point(10f, 54f))

    fun spokenText(text: String) = SpeechCaption.from(text, VoiceProtocol.MAX_TEXT)?.text
    check(spokenText("*adjusts antenna* Hello,   world.") == "Hello, world.")
    check(spokenText("{\"action\":\"delegate\"}") == null)
    check(spokenText("*flops over*") == null)
    check(spokenText("a".repeat(440))?.length == VoiceProtocol.MAX_TEXT)
    check(VoiceProtocol.isWav("RIFF....WAVE".toByteArray()))
    check(!VoiceProtocol.isWav("RIFF....WEBP".toByteArray()))

    val textOnlyBubble = ReplyBubbleState(12, spoken = false)
    check(textOnlyBubble.visibleEnd == 12 && !textOnlyBubble.waiting)
    val attemptedPictures = mutableListOf<String>()
    check(PaintingStart.tryEnabledPictures(listOf("unreadable", "good", "unused")) {
        attemptedPictures += it
        if (it == "unreadable") PaintingStart.Result.UNREADABLE else PaintingStart.Result.SHOWN
    } == PaintingStart.Result.SHOWN)
    check(attemptedPictures == listOf("unreadable", "good"))
    check(PaintingStart.tryEnabledPictures(listOf(1, 2)) { picture ->
        check(picture == 1) // A window rejection won't be fixed by decoding another image.
        PaintingStart.Result.NO_ROOM
    } == PaintingStart.Result.NO_ROOM)
    check(PaintingStart.tryEnabledPictures(listOf(1)) { PaintingStart.Result.UNREADABLE } == PaintingStart.Result.UNREADABLE)

    val wakeStartup = WakeStartupPolicy()
    val app = WakeStartupPolicy.Source.APP
    val boot = WakeStartupPolicy.Source.BOOT
    val recovery = WakeStartupPolicy.Source.RECOVERY
    check(wakeStartup.start(app, true, true, bootEnabled = false, bootMicAllowed = false))
    check(!wakeStartup.start(app, false, true, bootEnabled = true, bootMicAllowed = true))
    check(!wakeStartup.start(app, true, false, bootEnabled = true, bootMicAllowed = true))
    check(!wakeStartup.start(recovery, true, true, bootEnabled = true, bootMicAllowed = true))
    check(!wakeStartup.appVisible(true, true, true))
    check(!wakeStartup.start(boot, true, true, bootEnabled = false, bootMicAllowed = true))
    check(!wakeStartup.start(boot, false, true, bootEnabled = true, bootMicAllowed = false))
    check(!wakeStartup.start(boot, true, false, bootEnabled = true, bootMicAllowed = false))
    check(!wakeStartup.appVisible(true, true, true))
    check(wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = true))
    check(!wakeStartup.appVisible(true, true, true))
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false))
    check(wakeStartup.appVisible(true, true, true))
    check(!wakeStartup.appVisible(true, true, true)) // Reopening after a manual stop doesn't restart the mic.
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false))
    check(!wakeStartup.appVisible(false, true, true)) // Opt-out after boot cancels the deferred start.
    check(!wakeStartup.appVisible(true, true, true))
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false))
    check(!wakeStartup.appVisible(true, true, false)) // Turning off boot startup also cancels it.
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false, openAppAtBoot = true))
    check(!wakeStartup.takeAppLaunch(unlocked = false, enabled = true))
    check(wakeStartup.takeAppLaunch(unlocked = true, enabled = true))
    check(!wakeStartup.takeAppLaunch(unlocked = true, enabled = true))
    check(wakeStartup.appVisible(true, true, true)) // The mic waits for the real activity.
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false, openAppAtBoot = true))
    check(wakeStartup.appVisible(true, true, true)) // Manual opening consumes both requests.
    check(!wakeStartup.takeAppLaunch(unlocked = true, enabled = true))
    check(!wakeStartup.start(boot, true, true, bootEnabled = true, bootMicAllowed = false, openAppAtBoot = true))
    check(!wakeStartup.takeAppLaunch(unlocked = true, enabled = false))
    check(!wakeStartup.takeAppLaunch(unlocked = true, enabled = true))
    val restarted = WakeStartupPolicy()
    check(!restarted.start(recovery, true, true, bootEnabled = true, bootMicAllowed = false, openAppAtBoot = true))
    check(!restarted.takeAppLaunch(unlocked = true, enabled = true))

    check(TimedRequest.numericDuration("for 20 seconds") == 20_000L)
    check(TimedRequest.numericDuration("wait 1.5 hours") == 5_400_000L)
    check(TimedRequest.numericDuration("for 000000000000000000000001 min") == 60_000L)
    check(TimedRequest.numericDuration("for 9223372036854775807 hrs") == TimedRequest.MAX_MS)
    check(TimedRequest.numericDuration("for 999999999999999999999999999 hours") == TimedRequest.MAX_MS)
    check(TimedRequest.numericDuration("for 5 hourglass turns") == null)
    check(TimedRequest.numericDuration("for now") == null)
    check(PhoneContext.asksForAppLabel("Which app am I in?"))
    check(PhoneContext.asksForAppLabel("Hey Pip, what app is open right now?"))
    check(!PhoneContext.asksForAppLabel("Pretend I asked which app am I in?"))
    check(!PhoneContext.asksForAppLabel("What app should I open?"))
    check(!PhoneContext.asksForAppLabel("What's on my screen?"))

    val toys = listOf("painting", "soccer", "plant")
    val toyRandom = kotlin.random.Random(186)
    var lastToy: String? = null
    repeat(30) {
        val next = chooseSpontaneousToy(toys, lastToy, toyRandom)
        check(next in toys && next != lastToy)
        lastToy = next
    }
    check(chooseSpontaneousToy(listOf("plant"), null) == "plant")
    check(chooseSpontaneousToy(listOf("plant"), "plant") == null)
    check(chooseSpontaneousToy(listOf("plant"), "soccer") == "plant")
    check(chooseSpontaneousToy(emptyList<String>(), "plant") == null)

    check(CarCommand.parse("Navigate home") == CarCommand.Navigate("home"))
    check(CarCommand.parse("Please navigate to 123 Main Street.") == CarCommand.Navigate("123 Main Street"))
    check(CarCommand.parse("Take me to Denver International Airport") == CarCommand.Navigate("Denver International Airport"))
    check(CarCommand.parse("Please pause SPOTIFY!") == CarCommand.Spotify.PAUSE)
    check(CarCommand.parse("resume Spotify") == CarCommand.Spotify.PLAY)
    check(CarCommand.parse("Next song on Spotify") == CarCommand.Spotify.NEXT)
    check(CarCommand.parse("Previous track on Spotify") == CarCommand.Spotify.PREVIOUS)
    check(CarCommand.parse("Could you ask her to pause Spotify") == null)
    check(CarCommand.parse("Play Spotify and send a message") == null)
    check(CarCommand.parse("Navigate to Main Street and then send a message") == null)
    check(CarCommand.parse("navigate to test&mode=w") == null)
    check(CarCommand.parse("navigate to https://example.com/") == null)
    check(CarCommand.parse("pause YouTube") == null)
    check(CarCommand.parse("play emo route mix") == CarCommand.SpotifyPlaylist("emo route mix"))
    check(CarCommand.parse("Play Emo Route Mix on Spotify.") == CarCommand.SpotifyPlaylist("Emo Route Mix"))
    check(CarCommand.parse("Play the Emo Route Mix playlist on Spotify") == CarCommand.SpotifyPlaylist("Emo Route Mix"))
    check(CarCommand.parse("Play my playlist called Road Trip") == CarCommand.SpotifyPlaylist("Road Trip"))
    check(CarCommand.parse("skip this song") == CarCommand.Spotify.NEXT)
    check(CarCommand.parse("pause music") == CarCommand.Spotify.PAUSE)
    check(CarCommand.parse("Play Emo Route Mix on YouTube") == null)
    check(CarCommand.parse("Play playlist Rock on Apple Music") == null)
    check(CarCommand.parse("Play a game with me") == null)
    check(CarCommand.parse("Play then open the road mix") == null)
    check(CarCommand.parse("Play playlist https://example.com") == null)
    check(CarCommand.looksLikeTask("play emo route mix"))
    check(!CarCommand.looksLikeTask("can you hear me"))
    check(CarVoiceRoute.choose(true, true, supported = true, task = true) == CarVoiceRoute.EXECUTE)
    check(CarVoiceRoute.choose(true, null, supported = true, task = true) == CarVoiceRoute.CONNECTION_UNVERIFIED)
    check(CarVoiceRoute.choose(true, false, supported = true, task = true) == CarVoiceRoute.CONNECTION_UNVERIFIED)
    check(CarVoiceRoute.choose(false, true, supported = true, task = true) == CarVoiceRoute.DISABLED)
    check(CarVoiceRoute.choose(true, true, supported = false, task = true) == CarVoiceRoute.UNSUPPORTED)
    check(CarVoiceRoute.choose(true, null, supported = false, task = true) == CarVoiceRoute.UNSUPPORTED)
    check(CarVoiceRoute.choose(false, false, supported = true, task = true) == CarVoiceRoute.REVIEW_WHEN_PARKED)
    // A car's own command needs no wake word, but only when opted in with a hands-free link.
    check(CarVoice.unaddressedCommand(true, true, "pause Spotify") == CarCommand.Spotify.PAUSE)
    check(CarVoice.unaddressedCommand(true, true, "Next song") == CarCommand.Spotify.NEXT)
    check(CarVoice.unaddressedCommand(true, true, "take me home") == CarCommand.Navigate("home"))
    check(CarVoice.unaddressedCommand(true, true, "play Emo Route Mix") == CarCommand.SpotifyPlaylist("Emo Route Mix"))
    check(CarVoice.unaddressedCommand(false, true, "pause Spotify") == null)
    check(CarVoice.unaddressedCommand(true, false, "pause Spotify") == null)
    check(CarVoice.unaddressedCommand(true, true, "tell me a joke") == null)
    check(CarVoice.unaddressedCommand(true, true, "buy me a coffee") == null)
    check(CarVoice.unaddressedCommand(true, true, "open Instagram") == null)
    // Housekeeping: a scope word plus a dismissal is a bulk request, even said loosely.
    check(ClearRows.requested("Please press remove on all categories listed on screen"))
    check(ClearRows.requested("Just click every remove button on those screen I'm on right now"))
    check(ClearRows.requested("remove all of them"))
    check(ClearRows.requested("delete every row"))
    check(ClearRows.requested("clear all"))
    check(ClearRows.requested("dismiss all of those"))
    // One named thing is a selection, not housekeeping.
    check(!ClearRows.requested("remove the milk from my shopping list"))
    check(!ClearRows.requested("remove every photo of the dog"))
    check(!ClearRows.requested("delete the photo of the dog"))
    check(!ClearRows.requested("remove my subscription"))
    // Money is never swept up in a bulk job.
    check(!ClearRows.requested("remove all items from my cart"))
    check(!ClearRows.requested("buy all of them"))
    check(!ClearRows.requested("check out all of the items"))
    // Only a bare dismissal on the row is pressed.
    check(ClearRows.isDismissal("Remove"))
    check(ClearRows.isDismissal("delete"))
    check(ClearRows.isDismissal("remove item"))
    check(ClearRows.isDismissal("✕"))
    check(!ClearRows.isDismissal("Unsubscribe"))
    check(!ClearRows.isDismissal("Delete account"))
    check(!ClearRows.isDismissal("Cancel order"))
    check(!ClearRows.isDismissal("Remove ads"))
    check(!ClearRows.isDismissal("Remove $4.99"))
    check(!ClearRows.isDismissal("Remove member"))
    // A sweep treats a changed screen as the end, not as more to press.
    check(ClearRows.sameList(setOf("remove", "delete"), setOf("remove")))
    check(!ClearRows.sameList(setOf("remove"), setOf("remove", "delete account")))
    // The exact refusals from a real transcript, so they are caught and handed to the hands.
    check(ClearRows.soundsLikeRefusal("deleting things without a name makes me nervous, sticky sticky Ricky. tell me the exact category to drop and i'll get the scissors."))
    check(ClearRows.soundsLikeRefusal("that sounds like a job for a human hand, sticky sticky Ricky. my tiny legs only do walking."))
    check(ClearRows.soundsLikeRefusal("my little legs can’t reach those buttons, sticky sticky Ricky. you’ll have to do the honors."))
    check(!ClearRows.soundsLikeRefusal("Done — pressed twelve remove buttons."))
    check(!ClearRows.soundsLikeRefusal("I couldn’t see the screen, so I stopped."))
    check(ClearRows.report(0, false).startsWith("I didn’t find"))
    check(ClearRows.report(12, false).contains("12"))

    // Every toy sound must actually make noise, be a sensible length, and be quiet enough at
    // the ends that it does not click. A silent "sound" is the failure this catches.
    for (sound in ToySounds.Sound.entries) {
        val voice = ToySounds.voice(sound, 1f)
        val clip = ToySounds.render(voice)
        check(ToySounds.audible(clip)) { "$sound rendered silence" }
        val length = ToySounds.length(sound)
        check(length in 0.03f..0.6f) { "$sound is $length s" }
        // The first and last sample are faded, so a track never starts or stops mid-waveform.
        check(kotlin.math.abs(clip.samples.first().toInt()) < 1_200) { "$sound starts loud" }
        check(kotlin.math.abs(clip.samples.last().toInt()) < 1_200) { "$sound ends loud" }
    }
    // Variation is real: the same sound at two pitches is two different renders.
    val low = ToySounds.render(ToySounds.voice(ToySounds.Sound.POP, 0.8f))
    val high = ToySounds.render(ToySounds.voice(ToySounds.Sound.POP, 1.2f))
    check(!low.samples.contentEquals(high.samples))
    // Pitch is clamped to the small range that keeps a family recognisable.
    check(ToySounds.voice(ToySounds.Sound.POP, 5f).pitch == ToySounds.MAX_PITCH)
    check(ToySounds.voice(ToySounds.Sound.POP, 0.1f).pitch == ToySounds.MIN_PITCH)
    // The same voice renders identical bytes, which is what makes the cache safe.
    check(ToySounds.render(ToySounds.voice(ToySounds.Sound.SQUEAK, 1.03f)).samples
        .contentEquals(ToySounds.render(ToySounds.voice(ToySounds.Sound.SQUEAK, 1.03f)).samples))
    // A pop decays: most of its energy is at the front, and it is nearly gone by the end.
    val pop = ToySounds.render(ToySounds.voice(ToySounds.Sound.POP, 1f))
    check(ToySounds.rms(pop, 0f, 0.2f) > ToySounds.rms(pop, 0.8f, 1f) * 4)
    val pour = ToySounds.render(ToySounds.voice(ToySounds.Sound.POUR, 1f))
    check(ToySounds.rms(pour, 0.3f, 0.7f) > 100.0) { "pour is silent in its middle" }
    // Two joined clips are longer than either, and still end quietly.
    val joined = ToySounds.join(pop, pour)
    check(joined.samples.size > pop.samples.size + pour.samples.size - 4)
    check(kotlin.math.abs(joined.samples.last().toInt()) < 1_200)
    // Stopping a route is a bounded car command, and must never need the screen.
    check(CarCommand.parse("stop navigation") == CarCommand.StopNavigation)
    check(CarCommand.parse("Cancel navigation.") == CarCommand.StopNavigation)
    check(CarCommand.parse("Please end navigation") == CarCommand.StopNavigation)
    check(CarCommand.parse("exit navigation") == CarCommand.StopNavigation)
    check(CarCommand.parse("stop the route") == CarCommand.StopNavigation)
    check(CarCommand.parse("quit navigating") == CarCommand.StopNavigation)
    check(CarCommand.parse("stop") == null)
    check(CarCommand.parse("cancel my alarm") == null)
    check(CarCommand.parse("stop the music") == null)
    check(CarCommand.parse("stop playing Spotify") == null)
    check(CarVoice.unaddressedCommand(true, true, "stop navigation") == CarCommand.StopNavigation)
    // A question only reports; it is offered even when the command opt-in is off.
    check(CarCommand.parse("are we still navigating") == CarCommand.NavigationState)
    check(CarCommand.parse("Are we still navigating?") == CarCommand.NavigationState)
    check(CarCommand.parse("is navigation still on") == CarCommand.NavigationState)
    check(CarCommand.parse("how much longer") == CarCommand.NavigationState)
    check(CarCommand.parse("what's my eta") == CarCommand.NavigationState)
    check(CarCommand.parse("where am I") == null)
    check(CarCommand.parse("take me where navigating is fun") == null)
    check(CarVoice.unaddressedOrQuestion(true, true, true, "are we still navigating") == CarCommand.NavigationState)
    check(CarVoice.unaddressedOrQuestion(false, true, true, "are we still navigating") == CarCommand.NavigationState)
    check(CarVoice.unaddressedOrQuestion(false, false, true, "are we still navigating") == null)
    check(CarVoice.unaddressedOrQuestion(false, true, false, "are we still navigating") == null)
    check(CarVoice.unaddressedOrQuestion(true, true, true, "stop navigation") == CarCommand.StopNavigation)
    // Messages never claim a stop that was not seen.
    check(NavVoice.message(NavVoice.Action.STOPPED_AND_VERIFIED, null, true) == "Stopped navigation.")
    check(NavVoice.message(NavVoice.Action.NOT_RUNNING, null, true).startsWith("You’re not navigating"))
    check(NavVoice.message(NavVoice.Action.NO_MAPS, null, true).contains("Google Maps isn’t installed"))
    check(NavVoice.message(NavVoice.Action.STATUS, true, true).startsWith("Yes"))
    check(NavVoice.message(NavVoice.Action.STATUS, false, true).startsWith("You’re not navigating"))
    check(!NavVoice.message(NavVoice.Action.STATUS, null, false).contains("didn’t see"))
    check(NavVoice.message(NavVoice.Action.STATUS, null, true).contains("didn’t see"))
    check(NavVoice.message(NavVoice.Action.STOPPED_UNVERIFIED, null, true).contains("still there"))
    check(WakeFeedback.CAR_VERIFIED.isOutcome && WakeFeedback.CAR_VERIFIED.canSpeak)
    check(CarVoiceRoute.choose(true, true, supported = false, task = false) == CarVoiceRoute.ORDINARY)
    check(CarVoiceRoute.label(CarVoiceRoute.EXECUTE, true).contains("verified"))
    check(CarVoiceRoute.label(CarVoiceRoute.CONNECTION_UNVERIFIED, null).contains("could not be read"))
    check(CarVoiceRoute.label(CarVoiceRoute.CONNECTION_UNVERIFIED, false).contains("not connected"))
    check(CarVoiceRoute.label(CarVoiceRoute.DISABLED, true).contains("off"))
    check(WakeFeedback.CAR_NOT_RUN.isOutcome && WakeFeedback.CAR_COMMAND.isOutcome)
    check(WakeFeedback.CHAT_TIMED_OUT.isOutcome && WakeFeedback.CHAT_TIMED_OUT.canSpeak)
    check(WakeFeedback.PURCHASE_REVIEW.isOutcome && WakeFeedback.PURCHASE_REVIEW.canSpeak)
    // Only spending money stops a spoken request; every other task runs straight away.
    check(PurchasePolicy.asksToPay("Buy me a coffee at the cafe"))
    check(PurchasePolicy.asksToPay("order a pizza from the delivery app"))
    check(PurchasePolicy.asksToPay("pay the electric bill on the website"))
    check(PurchasePolicy.asksToPay("check out the two things in my cart"))
    check(PurchasePolicy.asksToPay("checkout on Amazon"))
    check(PurchasePolicy.asksToPay("subscribe to that music service"))
    check(PurchasePolicy.asksToPay("renew my parking session"))
    check(PurchasePolicy.asksToPay("top up my transit card"))
    check(PurchasePolicy.asksToPay("purchase the ticket"))
    check(PurchasePolicy.asksToPay("click Buy Now on that page"))
    check(!PurchasePolicy.asksToPay("open my shopping list"))
    check(!PurchasePolicy.asksToPay("open Spotify and play my playlist"))
    check(!PurchasePolicy.asksToPay("send Alex a message"))
    check(!PurchasePolicy.asksToPay("buy me some time, it's a joke"))
    check(!PurchasePolicy.asksToPay("pay attention to the road"))
    check(!PurchasePolicy.asksToPay("check out this video on YouTube"))
    check(!PurchasePolicy.asksToPay("tell me a joke"))
    check(!PurchasePolicy.asksToPay("put the kettle on"))
    check(!PurchasePolicy.asksToPay("in order to finish, scroll to the end"))
    check(WakeFeedback.SENDING.isMicEvent && WakeFeedback.NO_NAME.isMicEvent && WakeFeedback.MIC_SILENT.isMicEvent)
    check(!WakeFeedback.LISTENING.isMicEvent && !WakeFeedback.PLAYING.isMicEvent)

    val wakePhrase = WakePhrase()
    check(wakePhrase.accept("Ask Pip what he thinks", 1) == "Ask what he thinks" && !wakePhrase.awaiting)
    check(wakePhrase.accept("Piper, how are you?", 2) == null && !wakePhrase.awaiting)
    check(wakePhrase.accept("Hey, PIP, tell me a joke!", 3) == "tell me a joke!")
    check(wakePhrase.accept("Pip.", 100) == null && wakePhrase.awaiting)
    check(wakePhrase.accept("How are you?", 5_000) == "How are you?" && !wakePhrase.awaiting)
    check(wakePhrase.accept("Pip", 10_000) == null && wakePhrase.awaiting)
    check(wakePhrase.expire(30_001) && !wakePhrase.awaiting)
    check(wakePhrase.accept("This must not send after expiry", 31_000) == null)
    check(wakePhrase.hasAddress("“HEY — P. I. P, tell me a joke”"))
    check(wakePhrase.accept("Hey Peep — what time is it?", 23_000) == "what time is it?")
    check(wakePhrase.accept("Peep the window", 24_000) == null)
    check(!wakePhrase.hasAddress("I said Hey Pip yesterday"))
    wakePhrase.arm(25_000) // A Speak now tap or a fresh reply arms only one message.
    check(wakePhrase.accept("Tell me a joke", 26_000) == "Tell me a joke")
    check(wakePhrase.accept("Not part of the same request", 26_500) == null)
    wakePhrase.arm(27_000)
    check(wakePhrase.accept("Too late", 48_000) == null) // The window is generous, not endless.

    check(wakePhrase.accept("Good one, Pip!", 40_000) == "Good one!")
    check(wakePhrase.accept("good one pip", 41_000) == "good one") // The recognizer may omit punctuation.
    check(wakePhrase.accept("Can you, Pip, tell me another?", 42_000) == "Can you tell me another?")
    check(wakePhrase.accept("Tell me another, P. I. P.", 43_000) == "Tell me another.")
    check(wakePhrase.accept("We were talking about Pip.", 44_000) == null)
    check(wakePhrase.accept("I like Pip", 45_000) == null)
    check(wakePhrase.accept("I said 'nice one Pip'", 46_000) == null)
    check(wakePhrase.accept("Good one, Piper", 47_000) == null)
    check(wakePhrase.accept("That was funny", 48_000) == null)
    check(wakePhrase.accept("Hey Pep, pause Spotify", 49_000) == "pause Spotify")
    check(wakePhrase.accept("Pep, pause Spotify", 49_100) == "pause Spotify") // Variant spelling as a direct address.
    check(wakePhrase.accept("Pip’s next move", 49_200) == null)
    check(wakePhrase.accept("Pip…", 50_000) == null && wakePhrase.awaiting)
    check(wakePhrase.accept("pause Spotify", 50_600) == "pause Spotify")
    check(wakePhrase.observePartial("Hey Pip pause Spotify"))
    check(wakePhrase.accept("pause Spotify", 60_000) == "pause Spotify") // A live name sighting is enough.
    check(wakePhrase.observePartial("Hey Pip pause Spotify"))
    check(wakePhrase.observePartial("Hey Pip pause Spotify now"))
    check(!wakePhrase.observePartial("pause Spotify now"))
    check(wakePhrase.accept("Pause Spotify now!", 61_000) == "Pause Spotify now!")
    check(wakePhrase.accept("Pause Spotify", 62_000) == null) // Never leak across decoder segments.
    check(wakePhrase.observePartial("Pip tell me"))
    check(wakePhrase.observePartial("Pip tell me a joke"))
    check(wakePhrase.accept("tell me a joke", 63_000) == "tell me a joke")
    wakePhrase.observePartial("Hey Pip turn left")
    check(wakePhrase.accept("turn right", 64_000) == "turn right") // The name was heard.
    wakePhrase.observePartial("Hey Pip turn left")
    wakePhrase.observePartial("Hey Pip turn left")
    wakePhrase.reset() // Recorder restart or playback pause.
    check(wakePhrase.accept("turn left", 65_000) == null)
    wakePhrase.observePartial("Hey Pip")
    check(wakePhrase.accept("turn left", 66_000) == "turn left") // One name sighting is enough.
    check(!wakePhrase.observePartial("I said hey Pip turn left"))
    check(!wakePhrase.observePartial("I said hey Pip turn left"))
    check(wakePhrase.accept("turn left", 67_000) == null)
    // The name is honored anywhere in the sentence, with or without commas or "hey".
    check(wakePhrase.accept("could you pip turn the music down", 70_000) == "could you turn the music down")
    check(wakePhrase.accept("OK Pip", 71_000) == null && wakePhrase.awaiting)
    check(wakePhrase.accept("pause the music", 71_500) == "pause the music")
    check(wakePhrase.accept("pipe the output through grep", 72_000) == null)
    check(!wakePhrase.hasAddress("Piper is a nice name"))
    check(wakePhrase.accept("Pip what time is it?", 73_000) == "what time is it?")
    check(wakePhrase.accept("Peep, pause Spotify", 75_000) == "pause Spotify")
    check(wakePhrase.accept("peep the window", 75_500) == null)
    check(wakePhrase.accept("pepperoni pizza please", 75_700) == null) // Not a name spelling.
    check(wakePhrase.accept("Pip, is it going to rain?", 76_000) == "is it going to rain?")
    check(wakePhrase.accept("Pip is on my phone", 76_500) == "is on my phone") // He still answers.
    check(wakePhrase.accept("Could you Pip turn it down?", 77_000) == "Could you turn it down?")
    check(wakePhrase.accept("pass me the pip", 74_000) == null) // "the pip" is an object, not his name.
    // A request with his name in it counts however the name is spelled; talk about him does not.
    check(wakePhrase.accept("we asked Pip what he thinks", 80_000) == null)
    check(wakePhrase.accept("please tell Pip a joke", 80_100) == "please tell a joke")
    check(wakePhrase.accept("can you ask Pip what he thinks", 80_200) == "can you ask what he thinks")
    check(wakePhrase.accept("Hey pipe what time is it?", 80_300) == "what time is it?")
    check(wakePhrase.accept("could you pep turn the music down", 80_400) == "could you turn the music down")
    check(wakePhrase.accept("some pep in my step", 80_500) == null) // A real word, not his name.
    check(wakePhrase.accept("P-I-P, pause Spotify", 80_600) == "pause Spotify")

    // ---- automatic and scheduled tasks ----
    fun atLocal(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    val now = atLocal(2026, 11, 23, 10, 0) // A Monday, ten in the morning.
    check(TaskClock.parse("at 5pm", now) == atLocal(2026, 11, 23, 17, 0))
    check(TaskClock.parse("at 5", now) == atLocal(2026, 11, 23, 17, 0))
    check(TaskClock.parse("at 9am", now) == atLocal(2026, 11, 24, 9, 0)) // Gone today means tomorrow.
    check(TaskClock.parse("at 17:30", now) == atLocal(2026, 11, 23, 17, 30))
    check(TaskClock.parse("at noon", now) == atLocal(2026, 11, 23, 12, 0))
    check(TaskClock.parse("at midnight", now) == atLocal(2026, 11, 24, 0, 0))
    check(TaskClock.parse("5pm", now) == atLocal(2026, 11, 23, 17, 0)) // The brain's bare clock words.
    check(TaskClock.parse("tomorrow at 9am", now) == atLocal(2026, 11, 24, 9, 0))
    check(TaskClock.parse("tonight at 8", now) == atLocal(2026, 11, 23, 20, 0))
    check(TaskClock.parse("in 20 minutes", now) == now + 20 * 60 * 1000)
    check(TaskClock.parse("on friday at 8pm", now) == atLocal(2026, 11, 27, 20, 0))
    check(TaskClock.parse("at 8pm", atLocal(2026, 11, 23, 21, 0)) == atLocal(2026, 11, 24, 20, 0))
    check(TaskClock.parse("no time in these words", now) == null)
    check(TaskClock.parse("buy 3 apples now", now) == null) // A number alone is not a time.

    check(TaskCommand.localRequest("Hey Pip, remind me at 5pm to stretch my back") ==
        TaskCommand.Request.Schedule("at 5pm", "stretch my back"))
    check(TaskCommand.localRequest("at 5pm check the oven") ==
        TaskCommand.Request.Schedule("at 5pm", "check the oven"))
    check(TaskCommand.localRequest("check the oven at 5pm") ==
        TaskCommand.Request.Schedule("at 5pm", "check the oven"))
    check(TaskCommand.localRequest("remind me in 20 minutes to take out the cake") ==
        TaskCommand.Request.Schedule("in 20 minutes", "take out the cake"))
    check(TaskCommand.localRequest("whenever an ad shows up, skip it") ==
        TaskCommand.Request.Automate("an ad shows up", "skip it", ""))
    check(TaskCommand.localRequest("when the kettle boils, do turn it off") ==
        TaskCommand.Request.Automate("the kettle boils", "turn it off", ""))
    check(TaskCommand.localRequest("every time an ad plays, click the skip button") ==
        TaskCommand.Request.Automate("an ad plays", "click the skip button", ""))
    check(TaskCommand.localRequest("skip all ads on youtube") == null) // The brain's shape to fill in.
    check(TaskCommand.localRequest("tell me a joke") == null)

    check(TaskCommand.fromReply("""{"action":"schedule","at":"5pm","task":"stretch my back"}""") ==
        TaskCommand.Request.Schedule("5pm", "stretch my back"))
    check(TaskCommand.fromReply("""{"action":"automate","trigger":"an ad is showing","task":"click the skip button","app":"youtube"}""") ==
        TaskCommand.Request.Automate("an ad is showing", "click the skip button", "youtube"))
    check(TaskCommand.fromReply("""{"action":"delegate","task":"open Spotify"}""") == null)
    check(TaskCommand.fromReply("sounds good") == null)

    val one = PipTask("t1", PipTask.Kind.SCHEDULED, "stretch", "", "", now + 1000, now)
    check(PipTask.decode(one.encode()) == one)
    check(PipTask.decode("junk line without tabs") == null)
    val scheduled = PipTask.from(TaskCommand.Request.Schedule("in 10 minutes", "stretch"), now, 3)
    check(scheduled != null && scheduled.kind == PipTask.Kind.SCHEDULED &&
        scheduled.fireAt == now + 10 * 60 * 1000 && !scheduled.announced)
    check(PipTask.from(TaskCommand.Request.Schedule("at 5pm", ""), now, 3) == null)
    val automatic = PipTask.from(TaskCommand.Request.Automate("an ad shows", "skip it", "youtube"), now, 4)
    check(automatic != null && automatic.kind == PipTask.Kind.AUTOMATIC &&
        automatic.trigger == "an ad shows" && automatic.fireAt == 0L)
    check(PipTask.from(TaskCommand.Request.Automate("", "skip it", ""), now, 4) == null)

    check(TaskGate.mayRunScheduled(interactive = true, unlocked = true, atHome = true, agentBusy = false))
    check(!TaskGate.mayRunScheduled(interactive = true, unlocked = true, atHome = false, agentBusy = false))
    check(!TaskGate.mayRunScheduled(interactive = true, unlocked = false, atHome = true, agentBusy = false))
    check(!TaskGate.mayRunScheduled(interactive = true, unlocked = true, atHome = true, agentBusy = true))
    check(TaskGate.mayRunAutomatic(interactive = true, unlocked = true, agentBusy = false))
    check(!TaskGate.mayRunAutomatic(interactive = true, unlocked = true, agentBusy = true))

    val captureRecovery = WakeCaptureRecovery()
    check(!captureRecovery.stalled(3999, lastPcmAt = 0) && captureRecovery.stalled(4000, lastPcmAt = 0))
    check(captureRecovery.tryRestart(4000) && captureRecovery.tryRestart(8000))
    check(!captureRecovery.tryRestart(12000) && !captureRecovery.tryRestart(33999))
    check(captureRecovery.tryRestart(34000))

    val voiceExchange = VoiceExchange()
    voiceExchange.remember("Tell me a joke", "A short joke.", 0)
    check(voiceExchange.takeIfRecent(50_000) == VoiceExchange.Turn("Tell me a joke", "A short joke."))
    check(voiceExchange.takeIfRecent(55_000) == null) // A new response must renew it.
    voiceExchange.remember("Tell me another", "A different joke.", 60_000)
    check(voiceExchange.takeIfRecent(150_000) == null)
    voiceExchange.remember("Tell me another", "A different joke.", 160_000)
    voiceExchange.clear()
    check(voiceExchange.takeIfRecent(160_001) == null)

    val petBox = OverlayBounds.Box(180, 260, 64, 86)
    check(OverlayBounds.anchoredPanel(petBox, 232, 62, 440, 24, 640, 6, 8) == OverlayBounds.Box(96, 190, 232, 62))
    val edgePet = OverlayBounds.Box(0, 5, 64, 86)
    check(OverlayBounds.anchoredPanel(edgePet, 64, 26, 320, 0, 640, 6, 8) == OverlayBounds.Box(6, 99, 64, 26))

    val targetSole = 290f
    val spriteHeight = 172
    val spriteTop = ToyFooting.topForFeet(targetSole, spriteHeight, 2f)
    val windowCentre = ToyFooting.centreForFeet(targetSole, spriteHeight, 2f)
    check(ToyFooting.feetY(spriteTop, spriteHeight, 2f) == targetSole && windowCentre - spriteHeight / 2f == spriteTop)
    check(ToyFooting.beside(8f, 172, 64, 360, 6f, preferLeft = true) == 218f)
    check(ToyFooting.beside(180f, 172, 64, 360, 6f, preferLeft = false) == 142f)

    val counter = OverlayBounds.Box(36, 180, 112, 148)
    val station = LemonadeLayout.station(counter.x.toFloat(), counter.y.toFloat(), counter.width, 86, 1f)
    check(station == DragMotion.Point(92f, 248f))
    val sprite = OverlayBounds.Box((station.x - 32).toInt(), (station.y - 43).toInt(), 64, 86)
    check(sprite.y + 65 == counter.y + 90) // Face clears the 96dp lip.
    check(sprite.bottom > counter.y + 96 && sprite.bottom <= counter.bottom) // Legs sit behind the cabinet.
    check(LemonadeLayout.isPipTap(56f, 55f, counter.width, 1f))
    check(!LemonadeLayout.isPipTap(56f, 96f, counter.width, 1f))
    check(!LemonadeLayout.isPipTap(14f, 55f, counter.width, 1f))
    val smallerCaption = OverlayBounds.anchoredPanel(counter, 90, 38, 320, 24, 600, 6, 8)
    val longerCaption = OverlayBounds.anchoredPanel(counter, 204, 114, 320, 24, 600, 6, 8)
    check(smallerCaption.bottom == counter.y - 8 && longerCaption.bottom == counter.y - 8)
    check(longerCaption.x == 6 && sprite == OverlayBounds.Box(60, 205, 64, 86))
    val topCounter = counter.copy(y = 38)
    check(OverlayBounds.anchoredPanel(topCounter, 200, 100, 320, 24, 600, 6, 8).y == topCounter.bottom + 8)
    check(OverlayBounds.keyboardEscapeY(420f, 410f, 24f) == 410f)
    check(OverlayBounds.keyboardEscapeY(24f, 4f, 24f) == null)

    val bubbleClock = SpeechBubbleTiming()
    bubbleClock.hold(null)
    repeat(500) { bubbleClock.advance(it * 33L, visible = true, animate = true) }
    check(bubbleClock.opacity == 1f) // Neither thinking nor active captions have a read deadline.
    bubbleClock.hold(200)
    bubbleClock.advance(50_000L, visible = false, animate = true) // Window isn't visible yet.
    bubbleClock.advance(60_000L, visible = true, animate = true)
    check(bubbleClock.opacity == 1f)
    bubbleClock.advance(60_100L, visible = true, animate = true)
    bubbleClock.advance(60_101L, visible = false, animate = true) // An automated gesture hides it.
    bubbleClock.advance(70_000L, visible = true, animate = true)
    bubbleClock.advance(70_190L, visible = true, animate = true)
    check(bubbleClock.fading && bubbleClock.opacity == .5f)
    bubbleClock.suspend() // Screen off during the fade, then back on.
    bubbleClock.advance(90_000L, visible = true, animate = true)
    check(bubbleClock.opacity == .5f)
    bubbleClock.advance(90_090L, visible = true, animate = true)
    check(bubbleClock.finished)
    bubbleClock.hold(100) // New reply replaces an old fade at full opacity.
    bubbleClock.advance(100_000L, visible = true, animate = false)
    check(bubbleClock.opacity == 1f)
    bubbleClock.advance(100_100L, visible = true, animate = false)
    check(bubbleClock.finished)

    val captionPause = CaptionIdlePause()
    val preset = SpeechBubbleTiming()
    check(captionPause.permitsIdleMotion(0L))
    preset.hold(3_500L)
    captionPause.visibilityChanged(true, 0L)
    for (tick in 0L..3_700L step 100L) {
        preset.advance(tick, visible = true, animate = true)
        captionPause.visibilityChanged(!preset.finished, tick)
        check(!captionPause.permitsIdleMotion(tick)) // Also covers expiry of the old 3.5s idle timer.
    }
    check(preset.finished)
    captionPause.visibilityChanged(false, 4_599L) // Layout can run repeatedly after removal.
    check(!captionPause.permitsIdleMotion(4_599L))
    check(captionPause.permitsIdleMotion(4_600L))
    captionPause.visibilityChanged(true, 4_601L)
    check(!captionPause.permitsIdleMotion(6_000L))
    captionPause.visibilityChanged(false, 6_000L) // Explicit dismissal gets the same short pause.
    check(!captionPause.permitsIdleMotion(6_899L) && captionPause.permitsIdleMotion(6_900L))

    val spokenReply = "One two three"
    val voiceCaption = checkNotNull(SpeechCaption.from(spokenReply, 420))
    val spokenBubble = ReplyBubbleState(spokenReply.length, spoken = true)
    // Repeated zero-progress ticks, even after a long wait, leave the animated dots up.
    repeat(250) { check(!spokenBubble.reveal(voiceCaption.displayEndForProgress(0f))) }
    check(spokenBubble.waiting)
    check(spokenBubble.reveal(voiceCaption.displayEndForProgress(.001f)))
    check(!spokenBubble.waiting && spokenReply.take(spokenBubble.visibleEnd) == "One")
    check(spokenBubble.reveal(voiceCaption.displayEndForProgress(.4f)))
    check(spokenReply.take(spokenBubble.visibleEnd) == "One two")
    check(!spokenBubble.reveal(voiceCaption.displayEndForProgress(.2f)))
    check(spokenBubble.reveal(null) && spokenBubble.visibleEnd == spokenReply.length)
    val noAudio = ReplyBubbleState(12, spoken = true)
    check(noAudio.waiting && noAudio.reveal(null) && !noAudio.waiting && noAudio.visibleEnd == 12)

    val captionSource = "  *waves* Hello,   world! *smiles*"
    val captions = checkNotNull(SpeechCaption.from(captionSource, 420))
    check(captions.text == "Hello, world!")
    check(captionSource.take(captions.displayEndForRange(0, 5)) == "  *waves* Hello,")
    check(captionSource.take(captions.displayEndForRange(7, 12)) == "  *waves* Hello,   world!")
    check(captions.displayEndForRange(6, 7) == 0) // Whitespace alone isn't a new word.
    check(captions.displayEndForProgress(-0.01f) == 0)
    check(captions.displayEndForProgress(0f) == 0)
    check(captions.displayEndForProgress(Float.NaN) == 0)
    check(captions.displayEndForProgress(.001f) == captions.displayEndForRange(0, 5))
    check(captions.displayEndForProgress(1f) == captions.displayEndForRange(7, 12))
    val reveal = (0..100).map { captions.displayEndForProgress(it / 100f) }
    check(reveal.zipWithNext().all { (a, b) -> a <= b })
    check(SpeechCaption.from("One two three", 8)?.text == "One two")
    check(SpeechCaption.from("[(no spoken reply)]", 420) == null)

    check(VoiceSource.restore(null) == VoiceSource.KOKORO)
    check(VoiceSource.restore("SERVER") == VoiceSource.KOKORO)
    check(VoiceSource.restore("WIRO") == VoiceSource.KOKORO)
    check(VoiceSource.restore("ANDROID") == VoiceSource.ANDROID)
    check(VoiceSource.values().size == 2)

    var failureCount = 0
    check(ChatRecovery.attempt("fallback", { failureCount++ }) { "Hello from Pip" } == "Hello from Pip")
    check(ChatRecovery.attempt("fallback", { failureCount++ }) { throw IllegalStateException() } == "fallback")
    check(failureCount == 1)
    check(KokoroPreset.restore(null).speakerId == 5)
    check(KokoroPreset.restore("MICHAEL").speakerId == 6)
    val voiceWorker = KokoroHealth()
    check(voiceWorker.automaticAllowed && voiceWorker.lastProblem == null)
    voiceWorker.completed(KokoroFailure.NO_AUDIO)
    check(voiceWorker.automaticAllowed)
    voiceWorker.completed(KokoroFailure.STOPPED)
    check(!voiceWorker.automaticAllowed && voiceWorker.lastProblem == KokoroFailure.STOPPED)
    voiceWorker.completed(KokoroFailure.PLAYBACK)
    check(!voiceWorker.automaticAllowed && voiceWorker.lastProblem == KokoroFailure.PLAYBACK)
    voiceWorker.completed(null)
    check(voiceWorker.automaticAllowed && voiceWorker.lastProblem == null)
    voiceWorker.completed(KokoroFailure.TIMEOUT)
    check(!voiceWorker.automaticAllowed)
    val kokoroWav = checkNotNull(pcmWav(floatArrayOf(0f, -5f, 5f), 24_000))
    val audio = java.nio.ByteBuffer.wrap(kokoroWav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    check(VoiceProtocol.isWav(kokoroWav) && audio.getInt(24) == 24_000 && audio.getInt(40) == 6)
    check(audio.getShort(44) == 0.toShort() && audio.getShort(46) == (-32767).toShort())
    check(audio.getShort(48) == 32767.toShort())
    val decodedPcm = checkNotNull(KokoroPcm.parse(kokoroWav))
    check(decodedPcm.frames == 3 && decodedPcm.sampleRate == 24_000)
    check(KokoroPcm.parse(kokoroWav.copyOf(44)) == null)
    check(KokoroPcm.parse(kokoroWav.copyOf().apply { this[22] = 2 }) == null) // stereo
    check(KokoroPcm.parse(kokoroWav.copyOf().apply { this[40] = 0 }) == null) // invalid length
    check(KokoroPcm.parse(kokoroWav.copyOf().apply { this[36] = 'l'.code.toByte() }) == null)
    check(pcmWav(floatArrayOf(), 24_000) == null)
    // Locking the screen is recognised on the phone, before the model is asked, so this matcher
    // is the whole safety story: a miss means the phone stays awake and the model is consulted
    // about something the phone already does; a false positive means the screen goes dark
    // during an unrelated request.
    val lockRequests = listOf(
        "lock my screen", "lock the screen", "Lock my phone", "lock my phone please",
        "can you lock the screen", "could you lock my screen?", "please lock the screen",
        "turn off the screen", "turn my screen off", "switch the screen off",
        "put my screen to sleep", "sleep the screen", "screen off",
        "Hey Pip, lock my screen", "lock my display", "lock this phone",
        "shut the screen off", "lock up my screen", "ok now lock my screen"
    )
    for (message in lockRequests) {
        check(ScreenLock.requested(message) == ScreenLock.Match.NAMED) { "lock request missed: $message" }
    }
    val notLockRequests = listOf(
        "lock the door", "lock my keys in the car", "unlock the screen", "block the screen",
        "how do I lock my screen", "what button locks the screen",
        "remind me to lock the screen", "don't lock the screen", "never lock the phone",
        "stop locking my screen", "lock it", "lock the car", "lock the folder",
        "the screen is locked", "my phone locked itself", "play locked",
        "how long until the screen locks", "tell me a joke", "lock",
        "lock screen wallpaper", "what's my lock screen", "lock the door and tell me",
        "how long until my screen locks", "lock my car", "is my screen locked",
        "can I lock my screen", "does my screen lock automatically",
        "lock screen settings", "turn on the screen"
    )
    for (message in notLockRequests) {
        check(ScreenLock.requested(message) == null) { "false lock request: $message" }
    }
    // A pronoun only means the phone when the conversation has just been about locking it.
    check(ScreenLock.requested("lock it") == null)
    check(ScreenLock.requested("lock it", afterLockRequest = true) == ScreenLock.Match.REFERRED)
    check(ScreenLock.requested("lock it up", afterLockRequest = true) == ScreenLock.Match.REFERRED)
    // Neither label ever quotes the user back into diagnostics.
    check(ScreenLock.label(ScreenLock.Match.NAMED) == "lock the screen")

    // The rolling chat window: how long a message may be and which turns are sent. The
    // rules live in ChatWindow so this harness can compile them without Android.
    check(ChatWindow.bounded("hello") == "hello")
    check(ChatWindow.bounded("a".repeat(ChatWindow.MAX_MESSAGE_CHARS)).length == ChatWindow.MAX_MESSAGE_CHARS)
    val over = "a".repeat(ChatWindow.MAX_MESSAGE_CHARS + 5)
    check(ChatWindow.bounded(over).length == ChatWindow.MAX_MESSAGE_CHARS)
    // A message cut through a surrogate pair must not end in a lone high surrogate.
    val cut = "a".repeat(ChatWindow.MAX_MESSAGE_CHARS - 1) + "\uD83D\uDE00"
    val trimmed = ChatWindow.bounded(cut)
    check(trimmed.length == ChatWindow.MAX_MESSAGE_CHARS - 1 && !trimmed.last().isHighSurrogate())
    val userTurn = Msg("user", "hi")
    val window = listOf(Msg("user", "a"), Msg("assistant", "b"), userTurn)
    check(ChatWindow.forReply(window, "hi") == window) // asking twice must not duplicate the turn
    check(ChatWindow.forReply(window.take(2), "hi") == window)
    check(ChatWindow.forReply(window, "next").takeLast(2) == listOf(userTurn, Msg("user", "next")))
    check(ChatWindow.forReply(emptyList(), "next") == listOf(Msg("user", "next")))
    val long = List(ChatWindow.MAX_MESSAGES + 3) { Msg("user", "$it") }
    check(ChatWindow.forReply(long, "end").size == ChatWindow.MAX_MESSAGES)
    check(ChatWindow.forReply(emptyList(), over).single().content.length == ChatWindow.MAX_MESSAGE_CHARS)

    // Saved logins match by host, however the site was entered (Vault.host delegates here).
    check(siteHost("https://www.Example.com:8080/login?x=1#frag") == "example.com")
    check(siteHost("example.com") == "example.com")
    check(siteHost("example.com.") == "example.com")
    check(siteHost("  WWW.Example.co.uk  ") == "example.co.uk")
    check(siteHost("www.example.com") == "example.com")
    check(siteHost("sub.example.com") == "sub.example.com")
    check(siteHost("example.com/path") == "example.com")
    check(siteHost("http://example.com?query=1") == "example.com")
    check(siteHost("HTTPS://WWW.Example.com") == "example.com")
    check(siteHost("") == "")
    println("Job progress tests passed.")
}
