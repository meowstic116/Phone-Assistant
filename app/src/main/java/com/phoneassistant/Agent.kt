package com.phoneassistant

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pip's hands, working: a short loop that reads the screen, asks the brain for one action,
 * does it, and looks again.
 *
 * It is deliberately small. Each step is one request, so a job cannot quietly spend an
 * afternoon of the user's API quota, and every step is shown on the screen as it happens —
 * the app in front changes, which is the only progress report that means anything. Any
 * failure and any refusal ends the job with a line instead of retrying forever.
 *
 * The model is never given the whole screen, only a numbered list of the things that can be
 * pressed, typed into or scrolled, plus their labels. It answers with the number of the
 * element it wants, so no coordinates are guessed and no element is invented.
 */
object Agent {

    /**
     * How long a job may run. There is no step count any more: a task that works down a feed
     * needs as many presses as the feed has rows, and a ceiling on steps stopped those jobs
     * half done — which is what "gave kudos to two and stopped" was. Time is the fence now,
     * and it is the quota fence too: the free tier allows a few requests a minute, so six
     * minutes is about as much as one job can usefully spend.
     */
    private const val JOB_MS = 6 * 60_000L

    /** Time left at which the model is told to bring the job to a close. */
    private const val WRAP_UP_MS = 90_000L

    /** Time for the screen to settle after an action before it is read again. */
    private const val SETTLE_MS = 650L

    /** Time for an app to come up before its screen is read. Slower than a tap, by design. */
    private const val OPEN_MS = 1_500L

    /** Time for a page to show its login form after a log-in link is tapped or a scroll. */
    private const val FORM_WAIT_MS = 1_000L

    /** How many screens down a login form is looked for before he gives up on finding it. */
    private const val HUNT_TRIES = 2

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

    /** The job being worked, or null when there is none. */
    @Volatile
    private var job: String? = null

    /**
     * Bumped by every start and every cancel. A loop only speaks and only clears [job]
     * while its own number is still the current one, so a job the user has moved on from
     * cannot talk over the one that replaced it — a shared flag would be cleared by the
     * new job and let the old one finish loudly.
     *
     * Atomic because the two writers sit on different threads: a message starts a job from
     * the main looper, while the accessibility service cancels one when it unbinds. A plain
     * Int could lose one of those two increments — the same value read twice — and leave a
     * loop the user abandoned convinced it was still the current job.
     */
    private val ticket = AtomicInteger(0)

    fun running(): Boolean = job != null

    /**
     * Stops the job before its next action. A step already in flight cannot be undone, but the
     * answer the model is part-way through choosing is thrown away rather than acted on.
     *
     * A cancelled job says nothing at the end, but the diagnostics record still has to say why
     * it went quiet — otherwise a job that was talked over reads as one that simply gave up
     * after two steps.
     */
    fun cancel() = stop(STOP_CANCELLED)

    private fun stop(reason: String) {
        if (job == null) return
        // The number the job is working under, taken before the bump that retires it.
        val mine = ticket.getAndIncrement()
        job = null
        Diag.jobEnded(mine, reason)
    }

    /**
     * Ends the job on its own account, because it cannot go on without a login the user has to
     * sort out. It gets its own reason in the record: a job that stopped to ask is not one that
     * was talked over by a new message, and from the outside the two look alike — one is the
     * design working, the other is something to go and fix.
     *
     * Called from the loop, so the ticket must still be the loop's own: a job the user has
     * already cancelled keeps the reason it was cancelled with.
     */
    private fun stopForLogin(mine: Int, need: NeedLogin) {
        if (!ticket.compareAndSet(mine, mine + 1)) return
        job = null
        val site = need.site.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()
        Diag.jobEnded(
            mine,
            when {
                !need.saved -> "stopped to ask for a login — none saved$site"
                need.looked ->
                    "stopped to ask for a login — looked for the form, no password field$site"
                else -> "stopped to ask for a login — no password field to fill$site"
            }
        )
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

    /**
     * Starts a job on its own thread. [onSay], [onAct] and [onDone] fire on that thread, so a
     * caller with views to touch must hop to the main looper. Returns false when he has no
     * hands or is already busy.
     *
     * [onAct] brackets every moment the phone is being touched: true just before the action,
     * false once the gesture has finished and the screen has settled. Anything of the
     * overlay's own that sits over the screen — the cancel chip, the character — uses it to
     * get out of the way, so it cannot be the thing a press lands on. It returns only once
     * that has actually happened; false means the overlay could not get out of the way, and
     * no press is made.
     *
     * [onNeedLogin] fires when a step wanted a login it could not supply for itself — there is
     * none saved for that site, or the page will not take one — and asking the user is the
     * caller's business.
     */
    fun start(
        ctx: Context,
        request: String,
        onSay: (String) -> Unit,
        onDone: (String) -> Unit,
        onAct: (Boolean) -> Boolean,
        onNeedLogin: (NeedLogin) -> Unit
    ): Boolean {
        if (job != null) return false
        if (!Screen.available()) return false
        val mine = ticket.incrementAndGet()
        job = request
        Diag.jobStarted(mine, request)
        val appContext = ctx.applicationContext
        worker.execute { loop(appContext, request, mine, onSay, onDone, onAct, onNeedLogin) }
        return true
    }

    // ------------------------------------------------------------------- the loop

    private fun loop(
        ctx: Context,
        request: String,
        mine: Int,
        onSay: (String) -> Unit,
        onDone: (String) -> Unit,
        onAct: (Boolean) -> Boolean,
        onNeedLogin: (NeedLogin) -> Unit
    ) {
        // Still the job the user is waiting on? A cancelled or replaced job is silent.
        fun alive(): Boolean = ticket.get() == mine

        // The bubble gets one sentence; the record keeps the reason, which is a different
        // thing — "I can't see the screen" does not tell the user what to switch on, and
        // "the accessibility service is not connected" does.
        val finish: (String, String) -> Unit = { line, why ->
            if (alive()) {
                Diag.jobEnded(mine, why)
                onDone(line)
            }
        }
        val history = StringBuilder()
        var steps = 0
        // Whether this job has already searched. A second one is never needed: the browser
        // has the page, and asking again is the model going round rather than the job going
        // on — each repeat opening another tab while the user reads the first results.
        var searched = false
        // What the last answer was working from, for telling a job that is going round from
        // one that is working down a list.
        var lastScene = ""
        var lastAnswer = ""
        var sameAnswer = 0
        // The label he pressed when it was one of several on the screen: a feed's button rather
        // than a single button on one page. Kept because a job that answers done goes back to
        // it — a button that repeated down a feed is still repeating further down.
        var repeatLabel: String? = null
        try {
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
            val web = named != null && Screen.opensWebPages(ctx, named.packageName)
            if (named != null && !web && alive() && Screen.inFront() != named.packageName) {
                val opened = Screen.open(ctx, named)
                Diag.jobStep(
                    mine,
                    "open ${named.label}",
                    if (opened) "opened it" else "$FAILED ${named.label} would not open",
                    opened
                )
                if (opened) SystemClock.sleep(OPEN_MS)
            }

            val jobStartedAt = SystemClock.uptimeMillis()
            while (alive()) {
                val leftMs = JOB_MS - (SystemClock.uptimeMillis() - jobStartedAt)
                if (leftMs <= 0L) {
                    finish(
                        "I've been at this for $JOB_MINUTES minutes, so I'm stopping here.",
                        STOP_TIME
                    )
                    return
                }
                val read = readScreen()
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
                    decide(ctx, request, history.toString(), screen, steps, leftMs, apps) {
                        alive()
                    } ?: run {
                        finish("My brain stopped answering halfway through.", STOP_BRAIN)
                        return
                    }

                val search = decision.action == "search"
                val repeated = search && searched
                // A repeat does not say the model's line: "searching for it again" is not an
                // answer to a search that is already open, and the line that ends the job
                // says what is true instead.
                if (!repeated && decision.say.isNotBlank() && alive()) onSay(decision.say)
                if (decision.action == DONE) {
                    // He has read this one screen and decided from it, and a feed is longer than
                    // one screen. So the answer is checked before it is taken: if the button he
                    // was pressing is still down there, or the request names what the screen is
                    // full of, the sweep looks down the feed first and the ending says what it
                    // found rather than what he assumed. A screen with nothing left to press is
                    // done as he said, in his words.
                    val look = feedLeft(screen, request, repeatLabel)
                    if (look.label != null) {
                        val swept = sweepFeed(mine, look.label, "", onAct)
                        recordSweep(mine, look.label, swept)
                        if (swept.complete) {
                            finish(swept.line(), swept.reason(look.label))
                            return
                        }
                    } else {
                        // Nothing to sweep, and the record says which label was looked at and
                        // why it was passed over. A record of nothing but ASK lines cannot be
                        // told from one whose sweep ran and gave up at once, and that is the
                        // difference worth knowing.
                        Diag.jobStep(
                            mine,
                            "check the screen",
                            "nothing pressed — ${look.why}",
                            true,
                            skipped = true
                        )
                    }
                    finish(
                        decision.say.ifBlank { "Done." },
                        if (decision.say.isBlank()) STOP_DONE else "finished — ${decision.say}"
                    )
                    return
                }

                // Cancelled while the model was thinking: its answer must not be acted on. The
                // check at the top of the loop is not enough, because deciding is the slow
                // part — Stop, a new message or the service going away all land in the middle
                // of that call, and the answer that follows would otherwise press a button on
                // a job the user has already ended. This is the last moment before the phone
                // is touched, so this is where it has to be checked.
                if (!alive()) return
                // The screen has not changed and this is the same press as last time: the
                // last step did nothing he can see. Twice is a slow app; three times is a job
                // that will spend every step it has on it.
                //
                // A different element is progress even when the screen reads the same — the
                // next kudos down a feed is not the same press as the last one — so the
                // answer is compared with the element it names, where it sits, not only with
                // the numbers in the listing.
                val target = screen.items.firstOrNull { it.index == decision.target }
                val scene = screen.app + '\u0000' + screen.listing
                val answer = decision.action + ':' + decision.target + ':' + decision.text +
                    ':' + (target?.label ?: "") + '@' + (target?.bounds?.flattenToString() ?: "")
                if (scene == lastScene && answer == lastAnswer) sameAnswer++ else sameAnswer = 0
                lastScene = scene
                lastAnswer = answer
                if (sameAnswer >= 2) {
                    finish(
                        "I'm stuck on this screen — pressing it again isn't changing anything.",
                        STOP_STUCK
                    )
                    return
                }
                if (repeated) {
                    finish(
                        "I stopped at one search — it's open in your browser.",
                        "asked for a second search in the same job — the first one is open"
                    )
                    return
                }
                // The overlay's own windows get out of the way of the press. This is the one
                // window in which the phone is being touched, so it is the one window in
                // which they must not be touchable: a press aimed at a row that happens to
                // sit exactly where the user left the chip, or under Pip himself, a toy or
                // the floating panel, would otherwise land on his window instead of the row.
                // An
                // injected gesture keeps running after perform returns — a long press is
                // 550ms — so they stay away through the settle too, and come back while the
                // model is thinking, which is most of the time a job runs.
                val clear = onAct(true)
                // Not a failure of the step but a step that was never taken: nothing was
                // pressed, so the record says so instead of blaming the app he was in.
                val skipped = !clear
                try {
                    val act = if (clear) {
                        perform(ctx, decision, screen, apps)
                    } else {
                        // The overlay could not get out of the way in time. Pressing anyway
                        // risks the press landing on one of his own windows, which is the bug
                        // being fixed, so nothing is pressed and the record says why.
                        Act(OVERLAY_BUSY)
                    }
                    val outcome = act.outcome
                    val landed = !outcome.startsWith(FAILED)
                    history.append(steps + 1).append(". ").append(decision.summary(screen))
                        .append(" → ").append(outcome).append('\n')
                    // The only ones marked as turns: everything else in the record — the
                    // repeat pass, its scrolls, the app opened before the first step — was
                    // the loop acting on this answer, not another question asked.
                    Diag.jobStep(
                        mine,
                        decision.summary(screen),
                        outcome,
                        landed,
                        skipped,
                        turn = true
                    )
                    steps++
                    // A search that went through is the job. Waiting to be told "done"
                    // spends a request on an answer already known, and leaves the chip up
                    // over results the user can already read. Only a search the model says
                    // it will follow up, one that failed, or the task's own later steps
                    // keep it going.
                    if (search && landed) {
                        searched = true
                        if (!decision.more) {
                            val query = decision.text.trim()
                            finish(
                                if (decision.images) "Pictures of \"$query\" are up in your browser."
                                else "\"$query\" is up in your browser.",
                                "the search was the whole task — it is open in the browser"
                            )
                            return
                        }
                    }
                    if (landed) SystemClock.sleep(SETTLE_MS)
                    // A button that repeats down a list — "Give Kudos" on every activity — is
                    // one answer happening many times, and a request per item does not fit in
                    // any budget. The rest of them are pressed here instead, down the list.
                    if (landed && decision.action == "tap") {
                        val label = target?.label.orEmpty()
                        // One of several carrying the same label: a feed's button rather than a
                        // page's single one. Remembered so a done answer can be sent back to it.
                        val inFeed =
                            screen.items.count { it.label.equals(label, ignoreCase = true) } >= 2
                        if (inFeed && !STATED.containsMatchIn(label.lowercase())) repeatLabel = label
                        val swept = sweepFeed(
                            mine,
                            label,
                            target?.bounds?.flattenToString().orEmpty(),
                            onAct
                        )
                        // Every tap gets its sweep line, including one that never started: a
                        // record of only ASK lines cannot be told from one whose sweep ran and
                        // gave up at once, and that is the difference worth knowing. A tap on a
                        // menu item pays one short line for it.
                        recordSweep(mine, label, swept)
                        if (swept.screens > 0) {
                            history.append("   then a sweep of \"").append(label).append("\": ")
                                .append(swept.pressed).append(" pressed over ")
                                .append(swept.screens).append(" screens — ")
                                .append(swept.why).append('\n')
                        } else {
                            history.append("   no sweep of \"").append(label)
                                .append("\": ").append(swept.why).append('\n')
                        }
                        // The feed has been worked down and checked: that was the task, and
                        // another answer would only be told what it already knows. "more":true
                        // is for a task with something left in it afterwards.
                        if (swept.complete && !decision.more) {
                            finish(swept.line(), swept.reason(label))
                            return
                        }
                    }
                    // A sign-in he could not do himself. The job ends here, on this thread,
                    // before the ask goes out: the form is about to be the screen in front, and
                    // a next step would read Pip's own login form and start pressing about in
                    // it. The record is written first, then the user is asked, so the bubble,
                    // the record and the chip going away all say the same thing.
                    val ask = act.askLogin
                    if (ask != null) {
                        stopForLogin(mine, ask)
                        onNeedLogin(ask)
                        return
                    }
                } finally {
                    onAct(false)
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
            // Only the current job may clear the mark: a replaced one must not stand in the
            // way of the job that took its place.
            if (alive()) job = null
        }
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
    private fun readScreen(): Read {
        var looks = 0
        while (looks < READ_TRIES) {
            looks++
            Screen.snapshot()?.let { return Read(it, looks) }
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

    /**
     * Presses a repeated label down a feed, screen by screen, until the feed stops giving him
     * anything to press.
     *
     * A feed where one button repeats — "Give Kudos", "Like", "Follow" — is the same answer
     * happening many times, and a request per row does not fit in any budget. Once the model has
     * pressed one, the rest are pressed here: every one on this screen, then scroll and do the
     * next screen, for as long as the feed keeps giving them.
     *
     * It does not stop at the first screen with nothing left to press. Rows that already have
     * their kudos look exactly like the end of a feed, so both are answered the same way: keep
     * scrolling and looking. [SWEEP_BARREN] screens in a row with nothing to press is what
     * "there was nothing missed" looks like, [SWEEP_PAGES] is how far he will look for it, and a
     * scroll that does not move the feed is the bottom of it.
     *
     * It is deliberately narrow: a plain tap only, never while the app changes underneath it,
     * and never a label that reads like money or deletion, because a repeated "Buy" is not the
     * sort of help anyone asked for.
     */
    private fun sweepFeed(
        mine: Int,
        label: String,
        alreadyPressed: String,
        onAct: (Boolean) -> Boolean
    ): Sweep {
        if (label.isBlank()) return Sweep(why = STOP_SWEEP_NO_LABEL)
        val plain = label.lowercase()
        // Money, deletion, and labels that are already the state after the press: a sweep of a
        // "Buy" spends, and a sweep of a "Given" takes back what was given.
        if (UNSAFE_REPEAT.containsMatchIn(plain) || STATED.containsMatchIn(plain)) {
            return Sweep(why = STOP_SWEEP_REFUSED)
        }
        var screen = Screen.snapshot() ?: return Sweep(why = STOP_SWEEP_NO_SCREEN)
        // Nothing carrying the label is a button that was pressed and has stopped reading like
        // one. One left is still a sweep: the model's own press has usually changed its label
        // by now, so a feed that showed three can be down to one on the screen he is looking at.
        if (screen.items.none { it.label.equals(label, ignoreCase = true) }) {
            return Sweep(why = STOP_SWEEP_ABSENT)
        }
        val app = screen.app
        var pressed = 0
        var screens = 1
        var barren = 0
        var complete = true
        var why = STOP_SWEEP_PAGES
        while (screens <= SWEEP_PAGES && ticket.get() == mine) {
            // Which rows have been pressed on this screen, so one that ignores a press is not
            // pressed again. Bounds rather than labels, because every row carries the same
            // label — and bounds mean nothing across a scroll: the next screen's rows come
            // back at the same places, so a set carried over would skip the whole screen below.
            // The row the model pressed is only there to be skipped on the screen it is on.
            val done = if (screens == 1) hashSetOf(alreadyPressed) else hashSetOf()
            var onThisScreen = 0
            while (ticket.get() == mine) {
                val item = screen.items.firstOrNull { item ->
                    item.label.equals(label, ignoreCase = true) &&
                        item.bounds.flattenToString() !in done
                } ?: break
                done += item.bounds.flattenToString()
                if (!onAct(true)) return Sweep(pressed, screens, false, STOP_SWEEP_BUSY)
                val landed = try {
                    Screen.tap(item)
                } finally {
                    onAct(false)
                }
                // Every press is in the record; none of them is a step, because none of them
                // asked the model anything.
                Diag.jobStep(
                    mine,
                    "tap \"$label\"",
                    if (landed) "tapped it" else "$FAILED the tap did not land",
                    landed
                )
                if (!landed) break
                pressed++
                onThisScreen++
                SystemClock.sleep(SETTLE_MS)
                val next = Screen.snapshot() ?: break
                // Somewhere else entirely: the feed is not what is in front any more.
                if (next.app != app) return Sweep(pressed, screens, false, STOP_SWEEP_LEFT)
                screen = next
            }
            // Nothing pressable here. That is what rows which already have their kudos look
            // like, so it is not the end of the feed — it is one screen of it.
            barren = if (onThisScreen == 0) barren + 1 else 0
            if (barren >= SWEEP_BARREN) {
                why = STOP_SWEEP_BARREN
                break
            }
            if (screens >= SWEEP_PAGES) break
            // Scroll on. A scroll that does not move the feed is the bottom of it.
            if (!onAct(true)) return Sweep(pressed, screens, false, STOP_SWEEP_BUSY)
            val moved = try {
                Screen.scroll(true, null)
            } finally {
                onAct(false)
            }
            if (!moved) {
                why = STOP_SWEEP_BOTTOM
                break
            }
            Diag.jobStep(mine, "scroll down", "scrolled down for more \"$label\"", true)
            SystemClock.sleep(SETTLE_MS)
            val below = Screen.snapshot()
            if (below == null || below.app != app) {
                complete = false
                why = STOP_SWEEP_LEFT
                break
            }
            // The same rows in the same places: the scroll moved nothing, which is the bottom
            // of a feed. The words alone do not say that — two screens of rows that already
            // have their kudos read identically — so where they sit is compared too.
            if (fingerprint(below) == fingerprint(screen)) {
                why = STOP_SWEEP_BOTTOM
                break
            }
            screen = below
            screens++
        }
        // A sweep the user stopped is not one that finished looking.
        return Sweep(pressed, screens, complete && ticket.get() == mine, why)
    }

    /**
     * The label on this screen that the job still has work in, if there is one.
     *
     * Two ways a label counts. It is the one he was pressing when it was one of several on the
     * screen — a feed's button is still a feed's button further down — or the request itself
     * names what the label says. The second is what catches a job that never found the button:
     * the request says kudos, the screen is full of kudos, and answering done there is the job
     * ending on a screen it has not worked.
     *
     * Money and deletion never count, for the same reason a sweep refuses them, and a long
     * label is a row of text rather than a button, so it is left alone.
     */
    private fun feedLeft(
        screen: Screen.Snapshot,
        request: String,
        repeatLabel: String?
    ): Look {
        val groups = screen.items
            .map { it.label }
            .filter { it.isNotBlank() && it.length <= FEED_LABEL_MAX }
            .groupBy { it }
            .entries
            .filter { it.value.size >= 2 }
            .sortedByDescending { it.value.size }
        if (groups.isEmpty()) return Look(null, "no label repeats on the screen")
        val left = groups.firstOrNull { (label, _) ->
            val plain = label.lowercase()
            !UNSAFE_REPEAT.containsMatchIn(plain) && !STATED.containsMatchIn(plain) &&
                (label == repeatLabel || namesIt(request, label))
        }
        if (left != null) return Look(left.key, "work left in it")
        // Nothing qualifies. The label that repeats most is the one worth naming, with the
        // first reason it was passed over: it is the difference between a feed that is finished
        // and one whose button is spelt differently.
        val label = groups.first().key
        val plain = label.lowercase()
        return Look(
            null,
            "\"$label\" repeats but " + when {
                UNSAFE_REPEAT.containsMatchIn(plain) -> "reads as money or deletion"
                STATED.containsMatchIn(plain) -> "reads as already done"
                else -> "is not named by the request"
            }
        )
    }

    /**
     * Puts a sweep in the record, whether it ran or refused to start.
     *
     * A refusal is recorded as SKIP because nothing was pressed — the same mark a press that
     * never happened gets — so a record can always be read for whether a sweep was reached at
     * all, and if it was not, which label it was looking at and why it stopped there.
     */
    private fun recordSweep(mine: Int, label: String, swept: Sweep) {
        val text = if (label.isBlank()) "sweep" else "sweep \"$label\""
        if (swept.screens > 0) {
            Diag.jobStep(
                mine,
                text,
                "${swept.pressed} pressed over ${swept.screens} screens — ${swept.why}",
                true
            )
        } else {
            Diag.jobStep(mine, text, "nothing pressed — ${swept.why}", true, skipped = true)
        }
    }

    /**
     * Whether the request asks for a button everywhere, and names what the button says in words
     * of four letters or more. Both halves matter: the scope word is what makes it a sweep, and
     * the shared word is what makes this label the one it is talking about.
     */
    private fun namesIt(request: String, label: String): Boolean {
        val words = request.lowercase()
        if (!SCOPE.containsMatchIn(words)) return false
        val name = label.lowercase()
        return WORD.findAll(words).any { it.value.length >= 4 && name.contains(it.value) }
    }

    /** What is on a screen and where it sits: equal on both sides of a scroll means no movement. */
    private fun fingerprint(screen: Screen.Snapshot): String = buildString {
        screen.items.forEach { item ->
            append(item.bounds.flattenToString()).append(item.label).append(';')
        }
    }

    /**
     * What the look before finishing found: the label with work left in it, or — with no label —
     * why the one it looked at was passed over.
     */
    private class Look(val label: String?, val why: String)

    /**
     * What a sweep did: presses made, screens of feed looked at, whether it finished looking,
     * and — for the record — why it stopped.
     */
    private class Sweep(
        val pressed: Int = 0,
        val screens: Int = 0,
        val complete: Boolean = false,
        val why: String = STOP_SWEEP_NOT_A_FEED
    ) {

        /** What he says when the sweep was the whole task. */
        fun line(): String = if (pressed == 0) {
            "Nothing left to press — I checked $screens screens."
        } else {
            "$pressed more, and $screens screens checked."
        }

        /** The record's version, which can afford the label and the detail. */
        fun reason(label: String): String =
            "swept \"$label\": $pressed pressed over $screens screens — $why"
    }

    /** Asks for the next step, with what has happened so far and the screen as it is now. */
    private fun decide(
        ctx: Context,
        request: String,
        history: String,
        screen: Screen.Snapshot,
        step: Int,
        leftMs: Long,
        apps: List<Screen.AppRef>,
        alive: () -> Boolean
    ): Decision? {
        val app = Screen.appLabel(ctx, screen.app) ?: screen.app
        val user = buildString {
            append("TASK: ").append(request).append('\n')
            append("APP: ").append(app.ifBlank { "unknown" }).append('\n')
            val installed = Screen.appNames(apps, request)
            if (installed.isNotEmpty()) append("APPS ON THIS PHONE: ").append(installed).append('\n')
            if (history.isBlank()) {
                append("WHAT HAS HAPPENED SO FAR: nothing yet\n")
            } else {
                append("WHAT HAS HAPPENED SO FAR:\n").append(history)
            }
            append("SCREEN NOW:\n").append(screen.listing).append('\n')
            if (step == 0) {
                append("\nThis is the first step: say what you are doing in one short line, ")
                append("then act.\n")
            }
            if (leftMs < WRAP_UP_MS) {
                append("\nThis job has about a minute and a half left in it: do what matters ")
                append("most now, then finish.\n")
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
                "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            )
            false to ""
        }
        // One retry. A call that never comes back is usually a dropped connection or a hiccup
        // in the free tier's queue rather than a refusal, and a job the user has watched for
        // half a minute should not be thrown away on one of them. Only the call is repeated;
        // nothing on the phone has been touched at this point. A job the user has stopped in
        // the meantime is not retried at all: the answer would be thrown away unread.
        val first = ask()
        if (first.first) return Decision.parse(first.second)
        if (!alive()) return null
        SystemClock.sleep(RETRY_MS)
        if (!alive()) return null
        val second = ask()
        return if (second.first) Decision.parse(second.second) else null
    }

    // ---------------------------------------------------------------- the actions

    /**
     * Does one step. A sign-in is answered from the phone's own vault rather than from the
     * model, so it is handled apart from the actions that only need the screen.
     */
    private fun perform(
        ctx: Context,
        decision: Decision,
        screen: Screen.Snapshot,
        apps: List<Screen.AppRef>
    ): Act =
        if (decision.action == "sign_in") {
            signIn(ctx, decision, screen)
        } else {
            Act(simple(ctx, decision, screen, apps))
        }

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

            "scroll_down" ->
                if (Screen.scroll(true, item)) "scrolled down"
                else "$FAILED nothing would scroll"

            "scroll_up" ->
                if (Screen.scroll(false, item)) "scrolled up"
                else "$FAILED nothing would scroll"

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

    /**
     * Signs in with a login saved on this phone.
     *
     * The username and password are read here, on the device, and typed into the form in front
     * of the user. They are never part of a prompt, never in the chat, never in the step record
     * and never in the history the next step is given — the record names the site, not the
     * account. Nothing on this path can reach the network: [Vault] has no network code, and
     * neither does anything it hands back into.
     *
     * With no password box in front he does not give up: the form may be behind the page's own
     * log-in link, or a screen further down, so he taps and scrolls for it first — on the
     * device, inside this one step, at no cost to the model. Only when that finds nothing is
     * the user asked, and then he says he looked.
     */
    private fun signIn(ctx: Context, decision: Decision, screen: Screen.Snapshot): Act {
        val site = decision.site.ifBlank { decision.app }
        val login = Vault.find(ctx, site)
        val missing = "$FAILED no saved login for ${site.ifBlank { "this site" }}"
        // Nothing saved: there is no form to look for, because there would be nothing to put
        // in it. The ask opens the Logins form with the site already in it, and the job can be
        // picked up from there.
        if (login == null) return Act(missing, NeedLogin(site, saved = false))
        val password = screen.items.firstOrNull { it.password }
        if (password != null) return fill(login, screen, password, hunted = false)
        // No password field in front. A form that has not loaded yet, a login link further up
        // the page, a page that is not the login page at all and a web page whose fields the
        // service cannot type into all look the same from here — so he looks before he says
        // the login could not go in, and then says only what he found.
        lookForForm(login, screen)?.let { return it }
        return Act(
            "$FAILED no password field here, or behind a log-in link or a scroll",
            NeedLogin(site, saved = true, looked = true)
        )
    }

    /**
     * Looks for the form when there is no password box in front: the page's own log-in link
     * first, then a couple of screens of scrolling, reading the screen again after each. The
     * finished step comes back when a form turns up, or null when the screen has nothing more
     * to show.
     *
     * This runs inside a step, so his own windows are already ducked for it — every press here
     * is protected the same way the step's own press is. A link is only ever tapped once, so a
     * page whose "Log in" does nothing cannot be tapped round in circles.
     */
    private fun lookForForm(login: Vault.Login, first: Screen.Snapshot): Act? {
        var screen = first
        var tapped = false
        repeat(HUNT_TRIES) {
            val link = loginLink(screen)
            if (link != null && !tapped && Screen.tap(link)) {
                tapped = true
                SystemClock.sleep(FORM_WAIT_MS)
                screen = Screen.snapshot() ?: return null
                screen.items.firstOrNull { it.password }
                    ?.let { return fill(login, screen, it, hunted = true) }
            }
            // Further down, and further down once more. Something that will not scroll has
            // nothing below it to look at.
            if (!Screen.scroll(true, null)) return null
            SystemClock.sleep(FORM_WAIT_MS)
            screen = Screen.snapshot() ?: return null
            screen.items.firstOrNull { it.password }
                ?.let { return fill(login, screen, it, hunted = true) }
        }
        return null
    }

    /** Something on the screen that reads like the way into a login form. */
    private fun loginLink(screen: Screen.Snapshot): Screen.Item? =
        screen.items.firstOrNull { item ->
            !item.password && !item.editable &&
                LOGIN_WORDS.containsMatchIn(item.label.lowercase())
        }

    /**
     * Fills the form in front with the saved login. [hunted] is for the step's record alone:
     * a form he went looking for is worth saying he found.
     */
    private fun fill(
        login: Vault.Login,
        screen: Screen.Snapshot,
        password: Screen.Item,
        hunted: Boolean
    ): Act {
        // The nearest field above the password box is the account name on every login form
        // worth the name; a page that only wants a password gets no username typed at all.
        val username = screen.items
            .filter { it.editable && !it.password && it.bounds.bottom <= password.bounds.top }
            .maxByOrNull { it.bounds.bottom }
            ?: screen.items.firstOrNull { it.editable && !it.password }
        if (username != null && !Screen.type(username, login.username, secret = true)) {
            return Act("$FAILED the sign-in form would not take the username")
        }
        if (!Screen.type(password, login.password, secret = true)) {
            return Act("$FAILED the sign-in form would not take the password")
        }
        return Act(
            if (hunted) "found the login form and filled in the saved login for ${login.site}"
            else "filled in the saved login for ${login.site}"
        )
    }

    /**
     * A login the user has to sort out for him: the [site], whether he already holds one for
     * it, and whether he looked for the form before saying so. A saved login with no field on
     * screen is a different thing to say than never having been given one at all — and one he
     * looked for and could not find is a different thing again.
     */
    class NeedLogin(val site: String, val saved: Boolean, val looked: Boolean = false)

    /** One step's outcome, and the login to ask the user for when a sign-in could not be done. */
    private class Act(val outcome: String, val askLogin: NeedLogin? = null)

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

    /**
     * How far down a feed one sweep may look. A look budget rather than a press budget: every
     * row that needs the press gets it, and this is how many screens of rows there may be
     * before he says he has looked far enough.
     */
    private const val SWEEP_PAGES = 12

    /**
     * Screens in a row with nothing left to press, after which the feed is called done. One is
     * not enough — a screen whose rows already have their kudos looks exactly like the end of
     * the feed — and a feed that has really run out shows that on every scroll.
     */
    private const val SWEEP_BARREN = 3

    /**
     * Labels whose press cannot be taken back, or costs money. Only these are refused.
     *
     * The list is short on purpose: it is here to stop a replay pressing "Buy" ten times, not
     * to pass judgement on every verb. "Send", "Share", "Post", "Subscribe", "Follow" and
     * "Report" all belong to buttons that are perfectly reasonable to press down a list — a
     * row of "Subscribe" on channels is not ten purchases — and refusing them only made the
     * replay skip work the model had already decided to do.
     */
    private val UNSAFE_REPEAT = Regex(
        "\\b(buy|pay|payment|purchase|order|checkout|check out|donate|tip|upgrade|" +
            "delete|remove|uninstall|erase|wipe|reset)\\b"
    )

    /** A label longer than this is a row of text rather than a button, and is never a feed. */
    private const val FEED_LABEL_MAX = 40

    /** Words, for finding what a request calls the button it wants pressed. */
    private val WORD = Regex("[a-z]+")

    /**
     * A job that wants a button pressed everywhere says so: every, all, each. Without one of
     * these the request names a thing rather than a sweep — "open the chat with Mum" is not
     * "open every chat" — so a screen full of that word is not work left over.
     */
    private val SCOPE = Regex("\\b(every|everyone|everything|all|each)\\b")

    /**
     * Labels that read as the state after the press rather than the press itself: a given kudos
     * is not a kudos waiting to be given, and pressing it again takes it back.
     */
    private val STATED = Regex(
        "\\b(given|added|done|liked|followed|following|subscribed|joined|saved|sent|pressed)\\b"
    )

    /** How long a job is, in the words the bubble uses. */
    private val JOB_MINUTES = JOB_MS / 60_000L

    /** A step that was never pressed, because his own windows would not move off the screen. */
    private const val OVERLAY_BUSY =
        "$FAILED his own window would not get out of the way in time"
    private const val DONE = "done"

    /** The words a page or an app puts on the way into its login form. */
    private val LOGIN_WORDS = Regex("\\b(log ?in|login|log ?on|sign ?in|signin)\\b")

    // Why a job stopped, in the words the diagnostics record uses. Separate from what he
    // says, because the two answer different questions: the bubble is for the user, the
    // reason is for working out what to change.
    private const val STOP_CANCELLED = "cancelled — a new message, the chat, or the service"
    private const val STOP_NO_SCREEN = "could not read the screen"
    private const val STOP_NO_HANDS = "the accessibility service is not connected"
    private const val STOP_BRAIN = "the brain stopped answering"
    private const val STOP_DONE = "the job said it was finished"
    private const val STOP_STUCK = "the same press kept landing on the same screen"
    private const val STOP_TIME = "stopped at the time limit"

    // Why a sweep stopped looking, in the words the record uses.
    private const val STOP_SWEEP_BARREN = "the rest of the feed had nothing left to press"
    private const val STOP_SWEEP_BOTTOM = "reached the bottom of the feed"
    private const val STOP_SWEEP_PAGES = "looked $SWEEP_PAGES screens and stopped"
    private const val STOP_SWEEP_LEFT = "the app changed underneath it"
    private const val STOP_SWEEP_BUSY = "his own window would not get out of the way"
    private const val STOP_SWEEP_NOT_A_FEED = "not a feed"
    private const val STOP_SWEEP_NO_LABEL = "the press had no label to follow"
    private const val STOP_SWEEP_REFUSED = "the label reads as money, deletion or already done"
    private const val STOP_SWEEP_NO_SCREEN = "could not read the screen"
    private const val STOP_SWEEP_ABSENT = "nothing on the screen carries that label"

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
        - sign_in: use a login saved on the user's phone. site is the site it is for, e.g.
          "example.com"; leave site empty to use the app in front. Pip fills the fields
          himself — the username and password are never shown to you and never will be.
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
        - If an action failed according to WHAT HAS HAPPENED SO FAR, do something different.
          Never repeat a failing action.
        - An action can land and still take you where you did not mean to go — a profile
          instead of a feed, settings instead of a list. Use back and choose a different
          element; never carry on pressing about in a screen you did not mean to be in.
        - When the same button repeats down a list — kudos, likes, follows — tap one of them
          and nothing else: every other one is pressed for you, down the whole feed, and screens
          that already have it are checked on the way in case one was missed. That is usually the
          entire task and the job ends there; add "more":true only when something still has to
          happen afterwards. Press it in the row itself rather than opening the row first: an
          activity opened and backed out of gave no kudos, and the button is still in the row.
          Do not answer done while the screen still shows that button repeated — it is swept
          first, and an answer given without looking past one screen is not believed.
        - Never type into an element marked password, and never put a password in "text": a
          password field is only ever filled from a saved login. Never pay, buy, confirm a
          purchase or delete something the user did not name — if the task needs that, stop
          with done and say why, briefly.
        - A sign-in screen is what sign_in is for. It looks behind the page's own log-in link
          and a scroll or two by itself, so do not hunt for a form before using it. If it
          answers that there is no saved login, or that there is no password field anywhere it
          could look, stop with done and say so in one line: the user will be asked instead.
          Do not ask for a password in the chat, do not guess at one, and do not try to type it
          yourself.
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
