package com.phoneassistant

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors

/** Reads the screen, takes one action, and checks whether the task is making progress. */
object Agent {

    /** Time for the screen to settle after an action before it is read again. */
    private const val SETTLE_MS = 650L

    /** Time for an app to come up before its screen is read. Slower than a tap, by design. */
    private const val OPEN_MS = 1_500L

    /** Time for a page a search was handed to to have something on it worth reading. */
    private const val SEARCH_MS = 1_200L

    /** Time a "wait" step actually waits, so the screen it waits for is not the same one. */
    private const val WAIT_MS = 1_500L

    /** Time before the one retry of a brain call that did not come back. */
    private const val RETRY_MS = 800L

    /**
     * How long a screen that is not there yet is given to turn up, and how long apart. Three
     * seconds in all: an app coming up from cold can be that slow, and a window that is not
     * there yet is not a job that cannot see the screen.
     */
    private const val READ_TRIES = 6
    private const val READ_WAIT_MS = 500L

    private val worker = Executors.newSingleThreadExecutor()

    private val lifetime = JobLifetime()

    fun running(): Boolean = lifetime.running()
    fun isCurrent(id: Int): Boolean = lifetime.current(id)

    /** Stop before the next action; an already-dispatched action can't be undone. */
    @Synchronized
    fun cancel() {
        lifetime.cancel()?.let { Diag.jobEnded(it, STOP_CANCELLED) }
    }

    @Synchronized
    private fun complete(id: Int, reason: String): Boolean {
        if (!lifetime.complete(id)) return false
        Diag.jobEnded(id, reason)
        return true
    }

    /**
     * Reads a "delegate" reply — the model handing a phone job to its own hands — or null
     * when it was just talking, which is most of the time.
     */
    fun delegated(reply: String): String? {
        val json = jsonIn(reply) ?: return null
        if (!json.optString("action").equals("delegate", ignoreCase = true)) return null
        return json.optString("task").trim().take(300).takeIf { it.isNotEmpty() }
    }

    /** Callbacks carry the job ID so a queued UI delivery can reject a replaced job. */
    @Synchronized
    fun start(
        ctx: Context,
        request: String,
        onSay: (Int, String) -> Unit,
        onDone: (Int, String) -> Unit,
        onGesture: (Rect?) -> Boolean,
        originalRequest: String = request
    ): Boolean {
        if (!Screen.available() && JobProgress.explicitUrl(originalRequest) == null) return false
        val mine = lifetime.start() ?: return false
        Diag.jobStarted(mine, originalRequest)
        val appContext = ctx.applicationContext
        worker.execute {
            Screen.withGestureGuard({ lifetime.active(mine) }, { bounds ->
                val clear = onGesture(bounds)
                if (bounds != null && !clear) {
                    Diag.jobStep(mine, "prepare touch", "an overlay couldn't move out of the touch path", false, skipped = true)
                }
                clear
            }) {
                loop(appContext, request, originalRequest, mine, { onSay(mine, it) }, { onDone(mine, it) })
            }
        }
        return true
    }

    // ------------------------------------------------------------------- the loop

    private fun loop(
        ctx: Context,
        request: String,
        originalRequest: String,
        mine: Int,
        onSay: (String) -> Unit,
        onDone: (String) -> Unit
    ) {
        // Still the job the user is waiting on? A cancelled or replaced job is silent.
        fun alive(): Boolean = lifetime.active(mine)

        // The bubble gets one sentence; the record keeps the reason, which is a different
        // thing — "I can't see the screen" does not tell the user what to switch on, and
        // "the accessibility service is not connected" does.
        val finish: (String, String) -> Unit = { line, why ->
            if (complete(mine, why)) onDone(line)
        }
        val history = StringBuilder()
        var steps = 0
        val web = JobProgress.WebProgress(JobProgress.needsWebDestination(originalRequest))
        val failures = JobProgress.FailureStreak()
        val transitions = JobProgress.RecentVisits()
        var madeProgress = false
        var searched = false
        var correctedEarlyFinish = false
        try {
            JobProgress.explicitUrl(originalRequest)?.let { url ->
                if (!alive()) return
                val opened = Screen.openUrl(ctx, url)
                Diag.jobStep(mine, "open link", if (opened) "handed to Android" else "no app opened it", opened)
                finish(if (opened) "I handed the link to your browser." else "I couldn't open that link.",
                    if (opened) "explicit link handed to Android" else "link launch failed")
                return
            }
            val doomscroll = JobProgress.isDoomscrollRequest(originalRequest)
            val bulkKudos = JobProgress.isBulkKudosRequest(originalRequest)
            fun clarifyFeed(fallback: String, reason: String) {
                val configured = AssistantBrain.config(ctx).apiKey.isNotBlank()
                if (configured && alive()) onSay("I'm asking for a clearer way to phrase that.")
                val suggestion = if (configured) FeedHelp.suggestion(ctx, originalRequest, ::alive) else null
                if (!alive()) return
                if (configured) Diag.jobStep(mine, "ask API for clarification",
                    if (suggestion != null) "supported example; waiting for user confirmation" else "no usable suggestion",
                    suggestion != null, turn = true)
                if (suggestion != null) finish("If this matches what you want, say: ‘$suggestion’",
                    "API suggested a rephrasing; no actions taken")
                else finish(fallback, reason)
            }
            JobProgress.feedRequest(originalRequest)?.let { plan ->
                val result = FeedRunner.run(ctx, mine, plan, ::alive, onSay)
                if (!alive()) return
                if (result.second == "feed app not resolved" && (doomscroll || bulkKudos)) clarifyFeed(result.first, result.second)
                else finish(result.first, result.second)
                return
            }
            if (doomscroll || bulkKudos) {
                val example = if (doomscroll) "Open Instagram and doomscroll for 1 minute."
                    else "Give kudos on any Strava activity I haven't yet."
                clarifyFeed("I couldn't read that feed request. For example, try ‘$example’",
                    "unsupported feed wording; no actions taken")
                return
            }
            // The apps on this phone, read once: searched so a job that names an app starts
            // in that app, and named in every step so the model can only ask for something
            // that is really installed.
            val apps = Screen.apps(ctx)
            val named = Screen.appInRequest(apps, request)
            // A job that happens in another app should be watched happening there. The app
            // the request names is opened before the first step, so the user is looking at
            // the screen his hands are working on instead of at whatever was in front. It is
            // skipped when that app is already in front: relaunching would throw away a
            // screen the user had reached themselves.
            // A browser named by the request is not opened here: a web page goes to the
            // phone's default browser instead, and putting a different one in front first is
            // how a request for "the internet" ended up in an app the user never uses.
            val isBrowser = named != null && Screen.opensWebPages(ctx, named.packageName)
            if (named != null && !isBrowser && !web.destinationRequested &&
                alive() && Screen.inFront() != named.packageName
            ) {
                val opened = Screen.open(ctx, named)
                Diag.jobStep(
                    mine,
                    "open ${named.label}",
                    if (opened) "opened it" else "$FAILED ${named.label} would not open",
                    opened
                )
                if (opened) SystemClock.sleep(OPEN_MS)
            }

            while (alive()) {
                val read = readScreen(web = web.followUp)
                val screen = read.screen
                if (screen == null) {
                    val hands = Screen.available()
                    val looks = "${read.looks} look" + if (read.looks == 1) "" else "s"
                    finish(
                        if (hands) "I can't see the screen, so I've stopped." else NO_HANDS,
                        if (hands) {
                            "$STOP_NO_SCREEN after $looks — in front: ${Screen.frontWindow()}"
                        } else {
                            STOP_NO_HANDS
                        }
                    )
                    return
                }
                val decision =
                    decide(ctx, request, originalRequest, history.takeLast(7000).toString(), screen, steps, apps, web, ::alive) ?: run {
                        finish("My brain stopped answering halfway through.", STOP_BRAIN)
                        return
                    }

                if (!alive()) return
                if (decision.action == "sign_in" || decision.action == "type" &&
                    screen.items.any { it.password }
                ) {
                    finish("Please sign in yourself or use Android Autofill. I can't verify who owns that login form.",
                        "paused for manual sign-in; no credentials entered")
                    return
                }
                val search = decision.action == "search"
                val repeated = search && searched
                // A repeat does not say the model's line: "searching for it again" is not an
                // answer to a search that is already open, and the line that ends the job
                // says what is true instead.
                if (!repeated && decision.action != DONE && decision.say.isNotBlank()) onSay(decision.say)
                if (decision.action == DONE) {
                    if (web.unverified) {
                        if (searched && !correctedEarlyFinish) {
                            correctedEarlyFinish = true
                            history.append("Search results are open, but no result opened. Continue or report that it failed.\n")
                            continue
                        }
                        finish(
                            if (searched) "I opened results, but couldn't verify the requested page."
                            else "I couldn't open the requested page.",
                            "destination not verified"
                        )
                        return
                    }
                    if (!madeProgress && failures.failures > 0) {
                        finish("I couldn't verify that this worked.", "model finished without observable progress")
                        return
                    }
                    finish(
                        decision.say.ifBlank { "Done." },
                        if (decision.say.isBlank()) STOP_DONE else "finished — ${decision.say}"
                    )
                    return
                }

                val target = screen.items.firstOrNull { it.index == decision.target }
                if (!alive()) return
                val browserTarget = target?.takeIf {
                    web.followUp && decision.action == "tap" && !it.editable && it.label.isNotBlank()
                }
                val outcome = when {
                    repeated -> "$FAILED the search is already open; continue from the current page"
                    browserTarget != null -> pressBrowserTarget(mine, screen, browserTarget, web, ::alive)
                    decision.action in WINDOW_ACTIONS && !Screen.sameWindow(screen) ->
                        "$FAILED the foreground window changed; read it again"
                    decision.action in TARGET_ACTIONS && target != null && !Screen.targetStillVisible(screen, target) ->
                        "$FAILED the control changed; read the screen again"
                    !alive() -> return
                    else -> simple(ctx, decision, screen, apps)
                }
                if (!alive()) return
                val accepted = !outcome.startsWith(FAILED)
                var progress = accepted
                var detail = outcome
                if (accepted && browserTarget == null && decision.action in OBSERVE_ACTIONS) {
                    if (decision.action != "wait") SystemClock.sleep(SETTLE_MS)
                    if (!alive()) return
                    val next = Screen.snapshot(web = web.followUp)
                    val before = screen.app + '\n' + screen.listing
                    val after = next?.let { it.app + '\n' + it.listing }
                    progress = after != null && after != before &&
                        transitions.add("$before\u0000${decision.action}:${target?.label}\u0000$after")
                    if (!progress) detail += "; no new screen progress observed"
                }
                history.append(steps + 1).append(". ").append(decision.summary(screen))
                    .append(" → ").append(detail).append('\n')
                // The prompt only reads the tail; don't keep the full transcript in memory.
                if (history.length > 10_000) history.delete(0, history.length - 7_000)
                Diag.jobStep(mine, decision.summary(screen), detail, progress, turn = true)
                steps++
                madeProgress = madeProgress || progress
                if (search && accepted) {
                    searched = true
                    if (web.searchFinishes(decision.more)) {
                        val query = decision.text.trim()
                        finish(
                            if (decision.images) "Pictures of \"$query\" are up in your browser."
                            else "\"$query\" is up in your browser.",
                            "the search was the whole task — it is open in the browser"
                        )
                        return
                    }
                }
                if (web.attemptsExhausted) {
                    finish("I couldn't open the requested page. I stopped rather than repeat the same taps.",
                        "two distinct browser targets didn't navigate")
                    return
                }
                if (failures.record(progress)) {
                    finish("I stopped after several attempts without progress.",
                        "${failures.failures} consecutive failed or unproductive actions")
                    return
                }
                if (accepted && browserTarget == null && decision.action !in OBSERVE_ACTIONS) {
                    SystemClock.sleep(SETTLE_MS)
                }
            }
            // Only a cancelled job reaches here: every other ending returns from inside the
            // loop, and a cancelled job says nothing.
        } catch (t: Throwable) {
            // A job that throws must not leave the overlay convinced it is still working.
            Diag.caught("agent", t)
            finish(
                "Something went wrong while I was working. *dusts itself off*",
                "threw ${t.javaClass.simpleName}" + (t.message?.let { ": $it" } ?: "")
            )
        } finally {
            complete(mine, "job ended without a result")
        }
    }

    private fun pressBrowserTarget(
        mine: Int, before: Screen.Snapshot, target: Screen.Item,
        web: JobProgress.WebProgress, active: () -> Boolean
    ): String {
        if (web.triedWithoutNavigation(target.label)) return "$FAILED this title already failed to navigate"
        // IDs and screen positions can change during a rerender; titles and page text are
        // better evidence. Never read the browser's editable address field to compare pages.
        fun labels(screen: Screen.Snapshot) = screen.items.asSequence()
            .filter { !it.editable }.map { JobProgress.normalize(it.label) }
            .filter { it.isNotEmpty() }.toSet()
        val originalLabels = labels(before)
        var current: Screen.Snapshot? = null
        fun navigated(): Boolean {
            repeat(3) {
                if (!active()) return false
                SystemClock.sleep(if (it == 0) SETTLE_MS else READ_WAIT_MS)
                val after = Screen.snapshot(web = true)
                current = after
                if (after == null || after.app != before.app) return false
                val observed = labels(after)
                // Require new page content, not a different layout of the same results.
                if (JobProgress.pageChanged(originalLabels, observed)) {
                    web.navigated()
                    return true
                }
            }
            return false
        }
        fun freshTarget(screen: Screen.Snapshot?, requireSamePage: Boolean): Screen.Item? = screen
            ?.takeIf { it.app == before.app && it.windowId == before.windowId && (!requireSamePage || labels(it) == originalLabels) }
            ?.items?.singleOrNull {
                it.label == target.label && it.bounds == target.bounds && it.role == target.role &&
                    !it.editable && !it.password && it.node.isEnabled
            }

        val first = freshTarget(Screen.snapshot(web = true), requireSamePage = false)
            ?: return "$FAILED screen changed before I could tap this title"
        if (!active() || !Screen.targetStillVisible(before, first)) return "$FAILED browser target changed"
        val accepted = Screen.accessibilityTap(first)
        Diag.jobStep(mine, "accessibility click", if (accepted) "accepted; checking the page" else "not accepted", accepted)
        if (navigated()) return "page changed after accessibility click; check the destination"
        // Android can accept ACTION_CLICK without the browser opening a link. A touch is
        // allowed once, and only on the same title at the same location on the original page.
        val fresh = freshTarget(current, requireSamePage = true)
        if (fresh != null && active() && Screen.targetStillVisible(before, fresh)) {
            val touched = Screen.touch(fresh)
            Diag.jobStep(mine, "touch fallback", if (touched) "sent one touch to the same title" else "not accepted", touched)
            if (navigated()) return "page changed after touch fallback; check the destination"
        }
        web.failed(target.label)
        return "$FAILED no navigation verified for this title; don't retry it"
    }

    /**
     * Reads the screen, giving one that is not there yet a moment to arrive.
     *
     * An app that has just been opened from cold has no window to read while it comes up: the
     * launcher has gone and the app has not drawn, so a read in that gap finds nothing. One
     * read used to be the only one a step got, and a job that opened an app and looked a
     * moment too early ended there with "I can't see the screen". A few more looks cost no API
     * request and nothing but a second of patience.
     */
    private fun readScreen(web: Boolean = false): Read {
        var looks = 0
        while (looks < READ_TRIES) {
            looks++
            Screen.snapshot(web = web)?.let { return Read(it, looks) }
            // No hands at all is not a screen that is slow to arrive.
            if (!Screen.available()) return Read(null, looks)
            if (looks < READ_TRIES) SystemClock.sleep(READ_WAIT_MS)
        }
        return Read(null, looks)
    }

    /**
     * A look at the screen, and how many looks it took to get one. The count is for the record:
     * a job that ends unable to see the screen has to say whether it looked once or six times,
     * because "opened the app and looked too early" and "the service stopped answering" are
     * different faults and only one of them is worth waiting longer for.
     */
    private class Read(val screen: Screen.Snapshot?, val looks: Int)

    /** Asks for the next step, with what has happened so far and the screen as it is now. */
    private fun decide(
        ctx: Context,
        request: String,
        originalRequest: String,
        history: String,
        screen: Screen.Snapshot,
        step: Int,
        apps: List<Screen.AppRef>,
        web: JobProgress.WebProgress,
        alive: () -> Boolean
    ): Decision? {
        val app = Screen.appLabel(ctx, screen.app) ?: screen.app
        val user = buildString {
            append("TASK: ").append(request).append('\n')
            if (web.destinationRequested && request != originalRequest) {
                append("ORIGINAL USER REQUEST: ").append(originalRequest.take(400)).append('\n')
            }
            append("APP: ").append(app.ifBlank { "unknown" }).append('\n')
            val installed = Screen.appNames(apps, request)
            if (installed.isNotEmpty()) append("APPS ON THIS PHONE: ").append(installed).append('\n')
            if (history.isBlank()) {
                append("WHAT HAS HAPPENED SO FAR: nothing yet\n")
            } else {
                append("WHAT HAS HAPPENED SO FAR:\n").append(history)
            }
            append("SCREEN NOW:\n").append(screen.listing).append('\n')
            if (web.followUp) {
                append("\nSearch is already open. Use the result title matching the requested site, ")
                append("not its snippet, a toolbar, or an ad. Titles are listed in screen order. ")
                append("Do not retry a title reported as unchanged. If asked to open a download page, ")
                append("open the page but do not start a download. Only report the destination you can see.\n")
            } else if (web.destinationRequested) {
                append("\nSearch results alone aren't the user's destination. Search with more:true first.\n")
            }
            if (step == 0) {
                append("\nThis is the first step: say what you are doing in one short line, ")
                append("then act.\n")
            }

        }
        // A call that throws is caught here, not left to the loop below: a dropped connection
        // arrives as an exception rather than as a failed reply, and that is exactly the case
        // the retry is for — the loop's own catch would end the whole job on it.
        fun ask(): Pair<Boolean, String> = runCatching {
            AssistantBrain.complete(ctx, SYSTEM, user)
        }.getOrElse { t ->
            Diag.apiFailed(
                "did not finish",
                t.javaClass.simpleName
            )
            false to ""
        }
        // One retry. A call that never comes back is usually a dropped connection or a hiccup
        // in the free tier's queue rather than a refusal, and a job the user has watched for
        // half a minute should not be thrown away on one of them. Only the call is repeated;
        // nothing on the phone has been touched at this point. A job the user has stopped in
        // the meantime is not retried at all: the answer would be thrown away unread.
        if (!alive()) return null
        val first = ask()
        if (first.first) return Decision.parse(first.second)
        if (!alive()) return null
        SystemClock.sleep(RETRY_MS)
        if (!alive()) return null
        val second = ask()
        return if (second.first) Decision.parse(second.second) else null
    }

    // ---------------------------------------------------------------- the actions

    /** Every action that needs nothing but what is on the screen. */
    private fun simple(
        ctx: Context,
        decision: Decision,
        screen: Screen.Snapshot,
        apps: List<Screen.AppRef>
    ): String {
        val item = screen.items.firstOrNull { it.index == decision.target }

        fun missing(): String = "$FAILED there is no element ${decision.target}"

        return when (decision.action) {
            "tap" -> when {
                item == null -> missing()
                Screen.tap(item) -> "tapped it"
                else -> "$FAILED the tap did not land"
            }

            "long_press" -> when {
                item == null -> missing()
                Screen.longPress(item) -> "held it down"
                else -> "$FAILED the long press did not land"
            }

            "type" -> when {
                item == null -> missing()
                !item.editable -> "$FAILED that is not a text field"
                decision.text.isBlank() -> "$FAILED there was nothing to type"
                Screen.type(item, decision.text) -> "typed it"
                else -> "$FAILED the field would not take text"
            }

            "scroll_down", "scroll_up" -> {
                val forward = decision.action == "scroll_down"
                val scroller = item ?: screen.items.filter { it.scrollable }
                    .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
                when {
                    item == null && decision.target > 0 -> missing()
                    Screen.scroll(forward, scroller) -> if (forward) "scrolled down" else "scrolled up"
                    else -> "$FAILED nothing would scroll"
                }
            }

            "back" -> if (Screen.back()) "went back" else "$FAILED back was refused"

            "home" -> if (Screen.home()) "went home" else "$FAILED home was refused"

            "recents" -> if (Screen.recents()) "opened recents" else "$FAILED recents was refused"

            "notifications" ->
                if (Screen.notifications()) "opened the shade"
                else "$FAILED the shade would not open"

            "open_app" -> {
                val name = decision.app.ifBlank { decision.text }
                when {
                    name.isBlank() -> "$FAILED no app was named"
                    // The list read at the start of the job, rather than asking the package
                    // manager again for one more step.
                    else -> {
                        val app = Screen.findApp(apps, name)
                        when {
                            app == null -> "$FAILED no app called $name"
                            Screen.open(ctx, app) -> "opened ${app.label}"
                            else -> "$FAILED $name would not open"
                        }
                    }
                }
            }

            // A wait that returns at once is not a wait: the next step would read the very
            // same screen, spend another request, and be told to wait again.
            "wait" -> {
                SystemClock.sleep(WAIT_MS)
                "waited"
            }

            "search" -> {
                val query = decision.text.trim()
                when {
                    query.isBlank() -> "$FAILED there was nothing to search for"
                    Screen.search(ctx, query, decision.images) -> {
                        // The browser needs longer than a tap's settle to show results, and a
                        // step spent reading a loading page is a step wasted.
                        SystemClock.sleep(SEARCH_MS)
                        val kind = if (decision.images) "images" else "the web"
                        "searched $kind for \"$query\" in the default browser"
                    }
                    else -> "$FAILED no browser took the search"
                }
            }

            else -> "$FAILED I don't know how to ${decision.action}"
        }
    }

    // --------------------------------------------------------------- the decision

    /** One JSON answer from the model, in the shape the protocol asks for. */
    private class Decision(
        val say: String,
        val action: String,
        val target: Int,
        val text: String,
        val app: String,
        /** The site a sign-in is for. A name, never a credential. */
        val site: String,
        /** Whether a search should land on the pictures. */
        val images: Boolean,
        /**
         * Whether the task goes on after this step. Only a search reads it: a search ends the
         * job unless this says otherwise, so a task of one search does not spend another
         * request being told what it already did.
         */
        val more: Boolean
    ) {

        /** What the model tried, in the words the next step will be given it. */
        fun summary(screen: Screen.Snapshot): String {
            val what = screen.items.firstOrNull { it.index == target }?.label
            return buildString {
                append(action.replace('_', ' '))
                if (target > 0) append(" #").append(target)
                if (what != null && what.isNotEmpty()) append(" \"").append(what).append('"')
                if (text.isNotBlank()) append(" \"").append(text.take(60)).append('"')
                if (app.isNotBlank()) append(" ").append(app)
                if (site.isNotBlank() && site != app) append(" ").append(site)
            }
        }

        companion object {

            /**
             * Reads an answer. Anything that is not JSON is taken as Pip simply talking —
             * which is what happens when the model ignores the protocol — so a stray
             * sentence still reaches the user instead of becoming an error.
             */
            fun parse(raw: String): Decision {
                val json = jsonIn(raw)
                    ?: return Decision(
                        say = raw.trim().take(200),
                        action = DONE,
                        target = 0,
                        text = "",
                        app = "",
                        site = "",
                        images = false,
                        more = false
                    )
                val rawAction = json.optString("action").ifBlank { json.optString("do") }
                return Decision(
                    say = json.optString("say").trim().take(200),
                    action = normalise(rawAction),
                    target = json.optInt("target", json.optInt("index", 0)),
                    text = textOf(json),
                    app = json.optString("app").trim().take(60),
                    site = json.optString("site").trim().take(80),
                    // "images":true is the documented way; an action that names pictures is
                    // taken as meaning it too, since that is what it was asked for.
                    images = json.optBoolean("images", false) ||
                        rawAction.contains("image", ignoreCase = true),
                    more = json.optBoolean("more", false)
                )
            }

            private fun textOf(json: JSONObject): String {
                val direct = json.optString("text")
                if (direct.isNotEmpty()) return direct.take(1000)
                return json.optString("value").take(1000)
            }

            /** The names a model reaches for, mapped onto the ones that are implemented. */
            private fun normalise(action: String): String = when (val clean = action.trim().lowercase()) {
                "click", "press", "select" -> "tap"
                "set_text", "enter_text", "write", "input", "fill" -> "type"
                "scroll", "swipe_up", "page_down" -> "scroll_down"
                "swipe_down", "page_up" -> "scroll_up"
                "home_screen", "go_home" -> "home"
                "shade", "pull_down", "notification_shade" -> "notifications"
                "launch", "open", "start_app" -> "open_app"
                "google", "web_search", "look_up", "search_web", "image_search",
                "search_images", "images" -> "search"
                "signin", "log_in", "login", "log_into", "sign_into", "authenticate" ->
                    "sign_in"
                "finish", "stop", "complete" -> DONE
                else -> if (clean.isEmpty()) DONE else clean
            }
        }
    }

    // ---------------------------------------------------------------- the protocol

    private const val FAILED = "failed:"
    private val TARGET_ACTIONS = setOf("tap", "long_press", "type", "scroll_down", "scroll_up")
    private val WINDOW_ACTIONS = TARGET_ACTIONS + setOf("back", "home", "recents", "notifications")

    private val OBSERVE_ACTIONS = WINDOW_ACTIONS - "type" + setOf("wait", "open_app")
    private const val DONE = "done"

    // Why a job stopped, in the words the diagnostics record uses. Separate from what he
    // says, because the two answer different questions: the bubble is for the user, the
    // reason is for working out what to change.
    private const val STOP_CANCELLED = "cancelled — a new message, the chat, or the service"
    private const val STOP_NO_SCREEN = "could not read the screen"
    private const val STOP_NO_HANDS = "the accessibility service is not connected"
    private const val STOP_BRAIN = "the brain stopped answering"
    private const val STOP_DONE = "the job said it was finished"

    const val NO_HANDS =
        "Switch me on in accessibility first — Settings › Accessibility › Pip — and then " +
            "I can work your phone."

    /**
     * The whole instruction set. It is separate from Pip's personality on purpose: the
     * persona is the user's to rewrite, and a rewritten prompt must not be able to break the
     * machine half of the job.
     */
    private val SYSTEM = """
        You are Pip's hands on an Android phone. Each turn you are given a task and the
        phone's current screen as a numbered list of its usable elements. Choose exactly one
        next action, or say the job is done.

        Reply with a single JSON object and nothing else. No markdown, no explanation.
        {"say":"", "action":"tap", "target":0, "text":"", "app":""}

        ACTIONS
        - tap / long_press: target is the number of the element to press.
        - type: target is the number of a text field, text is what to put in it. It replaces
          the whole field.
        - sign_in: pause and hand the phone back to the user for manual sign-in. Do not enter
          credentials or ask for them in chat.
        - scroll_down / scroll_up: scrolls the screen, or the element in target.
        - back / home / recents / notifications: no target.
        - open_app: app is the name of an app from APPS ON THIS PHONE, e.g. "Messages". The
          app is opened in front of the user first and read on the next turn.
        - search: text is what to look up, e.g. "cats". Add "images":true to land on the
          pictures instead of the results. It opens the phone's own default browser on that
          search in one action, and that search then ends the job: add "more":true only when
          the task has something to do after it. Never search for the same thing twice.
        - wait: when a screen is still loading.
        - done: the job is finished, or cannot be done at all.

        RULES
        - One action per reply. The screen is read again after every action, so never plan
          two steps in one answer.
        - "say" is what Pip says in a bubble: one short, dry sentence, under fifteen words.
          Use it on the first turn to say what you are starting, and on done to report.
        - The labels in the list are the only truth about the screen. Use them exactly as
          written; do not invent a button that is not listed.
        - The text of a text field is never shown to you, only whether it has anything in it:
          assume a field the user filled in is right and leave it alone.
        - If the task belongs in an app that is not the one in APP, make open_app your first
          action instead of going home and hunting for an icon. The app opening on screen is
          how the user watches the job happen.
        - Anything found on the web is a search: a lookup, a question, "show me", "take me to
          the images". Do it with search on the first turn — never by opening a browser and
          typing in its search box. That box cannot be submitted by typing, so it leaves the
          text sitting there and nothing searched; the browser is also then whichever one the
          app list offered rather than the one the user actually uses. A search is normally
          the whole job and ends it; "more":true is only for a task that goes on afterwards.
        - If an action failed or the screen did not make progress according to WHAT HAS
          HAPPENED SO FAR, try a different approach. Say done when you have evidence the task
          is complete, or report what you could not verify.
        - An action can land and still take you where you did not mean to go — a profile
          instead of a feed, settings instead of a list. Use back and choose a different
          element; never carry on pressing about in a screen you did not mean to be in.
        - Repeat actions only within the scope the user actually asked for. Do not assume a
          one-item or selective request applies to the entire feed. Do not claim any reaction
          was given just because you scrolled or opened an item.
        - Never type into a password field or enter a credential for the user. Use sign_in
          when a sign-in form must be completed. Do not buy, pay, or delete without a clear
          request from the user.
        - If the request is not a phone job at all, answer it in "say" with done.
    """.trimIndent()

    /**
     * The JSON in a model's answer, wherever it put it. Models wrap it in prose or fences
     * often enough that finding the outermost braces is worth more than being strict.
     */
    private fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}
