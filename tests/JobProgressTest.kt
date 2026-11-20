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

    check(UpdatePolicy.isNewer("v0.175", "0.174"))
    check(!UpdatePolicy.isNewer("v0.175.0", "0.175"))
    check(!UpdatePolicy.isNewer("v0.176-rc.1", "0.175"))
    check(!UpdatePolicy.isNewer("v0.175..7", "0.175"))
    check(UpdatePolicy.releasePage("v0.175") == "https://github.com/meowstic116/Phone-Assistant/releases/tag/v0.175")
    check(UpdatePolicy.releasePage("v0.175/../unsafe") == null)
    val asset = "https://github.com/meowstic116/Phone-Assistant/releases/download/v0.175/phone-assistant-v0.175.apk"
    check(UpdatePolicy.apkUrl("v0.175", "phone-assistant-v0.175.apk", asset) == asset)
    check(UpdatePolicy.apkUrl("v0.175", "phone-assistant-v0.175.apk", asset.replace("meowstic116", "elsewhere")) == null)
    check(UpdatePolicy.apkUrl("v0.175", "phone-assistant-v0.175.apk", "$asset?override=1") == null)
    check(java.io.StringReader("hello").readBounded(5) == "hello")
    check(java.io.StringReader("").readBounded(0).isEmpty())
    check(runCatching { java.io.StringReader("a".repeat(8192)).readBounded(6000) }.exceptionOrNull() is java.io.IOException)

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
    check(leftStance.y == rightStance.y && leftStance.y == potTop + 5f)
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
    val wakePhrase = WakePhrase()
    check(wakePhrase.accept("Ask Pip what he thinks", 1) == null && !wakePhrase.awaiting)
    check(wakePhrase.accept("Piper, how are you?", 2) == null && !wakePhrase.awaiting)
    check(wakePhrase.accept("Hey, PIP, tell me a joke!", 3) == "tell me a joke!")
    check(wakePhrase.accept("Pip.", 100) == null && wakePhrase.awaiting)
    check(wakePhrase.accept("How are you?", 5_000) == "How are you?" && !wakePhrase.awaiting)
    check(wakePhrase.accept("Pip", 10_000) == null && wakePhrase.awaiting)
    check(wakePhrase.expire(20_001) && !wakePhrase.awaiting)
    check(wakePhrase.accept("This must not send after expiry", 21_000) == null)
    check(wakePhrase.hasAddress("“HEY — P. I. P, tell me a joke”"))
    check(wakePhrase.accept("Hey Peep — what time is it?", 23_000) == "what time is it?")
    check(wakePhrase.accept("Peep the window", 24_000) == null)
    check(!wakePhrase.hasAddress("I said Hey Pip yesterday"))
    wakePhrase.arm(25_000) // The explicit Speak now tap only arms one message.
    check(wakePhrase.accept("Tell me a joke", 26_000) == "Tell me a joke")
    check(wakePhrase.accept("Not part of the same request", 26_500) == null)
    wakePhrase.arm(27_000)
    check(wakePhrase.accept("Too late", 38_000) == null)

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
    println("Job progress tests passed.")
}
