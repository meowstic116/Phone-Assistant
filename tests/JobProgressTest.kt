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

    println("Job progress tests passed.")
}
