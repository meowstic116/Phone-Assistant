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

    /** One step is one API call: enough to open an app, find a chat, type and send. */
    private const val MAX_STEPS = 8

    /** Time for the screen to settle after an action before it is read again. */
    private const val SETTLE_MS = 650L

    /** Time for an app to come up before its screen is read. Slower than a tap, by design. */
    private const val OPEN_MS = 1_500L

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
    fun cancel() {
        if (job == null) return
        // The number the job is working under, taken before the bump that retires it.
        val mine = ticket.getAndIncrement()
        job = null
        Diag.jobEnded(mine, STOP_CANCELLED)
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
            if (named != null && alive() && Screen.inFront() != named.packageName) {
                val opened = Screen.open(ctx, named)
                Diag.jobStep(
                    mine,
                    "open ${named.label}",
                    if (opened) "opened it" else "$FAILED ${named.label} would not open",
                    opened
                )
                if (opened) SystemClock.sleep(OPEN_MS)
            }

            while (steps < MAX_STEPS && alive()) {
                val screen = Screen.snapshot()
                if (screen == null) {
                    val hands = Screen.available()
                    finish(
                        if (hands) "I can't see the screen, so I've stopped." else NO_HANDS,
                        if (hands) STOP_NO_SCREEN else STOP_NO_HANDS
                    )
                    return
                }
                val decision = decide(ctx, request, history.toString(), screen, steps, apps)
                    ?: run {
                        finish("My brain stopped answering halfway through.", STOP_BRAIN)
                        return
                    }

                if (decision.say.isNotBlank() && alive()) onSay(decision.say)
                if (decision.action == DONE) {
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
                        perform(ctx, decision, screen)
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
                    Diag.jobStep(mine, decision.summary(screen), outcome, landed, skipped)
                    steps++
                    if (landed) SystemClock.sleep(SETTLE_MS)
                    // A sign-in he could not do himself: the record is written first, then
                    // the user is asked, so the bubble and the record tell the same story.
                    val ask = act.askLogin
                    if (ask != null) onNeedLogin(ask)
                } finally {
                    onAct(false)
                }
            }
            finish("That's as far as I got — I stop after $MAX_STEPS steps.", STOP_STEPS)
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

    /** Asks for the next step, with what has happened so far and the screen as it is now. */
    private fun decide(
        ctx: Context,
        request: String,
        history: String,
        screen: Screen.Snapshot,
        step: Int,
        apps: List<Screen.AppRef>
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
            if (step == MAX_STEPS - 1) {
                append("\nNo steps left after this one: get as close as you can, then finish.\n")
            }
        }
        val (ok, raw) = AssistantBrain.complete(ctx, SYSTEM, user)
        if (!ok) return null
        return Decision.parse(raw)
    }

    // ---------------------------------------------------------------- the actions

    /**
     * Does one step. A sign-in is answered from the phone's own vault rather than from the
     * model, so it is handled apart from the actions that only need the screen.
     */
    private fun perform(ctx: Context, decision: Decision, screen: Screen.Snapshot): Act =
        if (decision.action == "sign_in") {
            signIn(ctx, decision, screen)
        } else {
            Act(simple(ctx, decision, screen))
        }

    /** Every action that needs nothing but what is on the screen. */
    private fun simple(ctx: Context, decision: Decision, screen: Screen.Snapshot): String {
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
                    else -> {
                        val opened = Screen.openApp(ctx, name)
                        if (opened == null) "$FAILED no app called $name" else "opened $opened"
                    }
                }
            }

            "wait" -> "waited"

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
     */
    private fun signIn(ctx: Context, decision: Decision, screen: Screen.Snapshot): Act {
        val site = decision.site.ifBlank { decision.app }
        val login = Vault.find(ctx, site)
        val missing = "$FAILED no saved login for ${site.ifBlank { "this site" }}"
        val password = screen.items.firstOrNull { it.password }
        // A login page in a browser is a web page, not a form of native fields: the fields
        // inside a web view are not editable views the accessibility service can type into,
        // so there is nothing here for him to fill and no saved login that would go in. The
        // user is told, and the caller opens the form — a bare "there is no password field"
        // gave them nothing they could act on, and read as a fault in the app instead.
        if (password == null) {
            return Act(
                if (login == null) missing
                else "$FAILED this is a web page, so the login cannot be typed into it",
                NeedLogin(site, saved = login != null)
            )
        }
        if (login == null) return Act(missing, NeedLogin(site, saved = false))
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
        return Act("filled in the saved login for ${login.site}")
    }

    /**
     * A login the user has to sort out for him: the [site], and whether he already holds one
     * for it. A saved login that would not go in is a page he cannot type into, which is a
     * different thing to say than never having been given one at all.
     */
    class NeedLogin(val site: String, val saved: Boolean)

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
        val site: String
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
                        site = ""
                    )
                val action = normalise(
                    json.optString("action").ifBlank { json.optString("do") }
                )
                return Decision(
                    say = json.optString("say").trim().take(200),
                    action = action,
                    target = json.optInt("target", json.optInt("index", 0)),
                    text = textOf(json),
                    app = json.optString("app").trim().take(60),
                    site = json.optString("site").trim().take(80)
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
                "signin", "log_in", "login", "log_into", "sign_into", "authenticate" ->
                    "sign_in"
                "finish", "stop", "complete" -> DONE
                else -> if (clean.isEmpty()) DONE else clean
            }
        }
    }

    // ---------------------------------------------------------------- the protocol

    private const val FAILED = "failed:"

    /** A step that was never pressed, because his own windows would not move off the screen. */
    private const val OVERLAY_BUSY =
        "$FAILED his own window would not get out of the way in time"
    private const val DONE = "done"

    // Why a job stopped, in the words the diagnostics record uses. Separate from what he
    // says, because the two answer different questions: the bubble is for the user, the
    // reason is for working out what to change.
    private const val STOP_CANCELLED = "cancelled — a new message, the chat, or the service"
    private const val STOP_NO_SCREEN = "could not read the screen in front"
    private const val STOP_NO_HANDS = "the accessibility service is not connected"
    private const val STOP_BRAIN = "the brain stopped answering"
    private const val STOP_DONE = "the job said it was finished"
    private const val STOP_STEPS = "stopped at the $MAX_STEPS step limit"

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
        - If an action failed according to WHAT HAS HAPPENED SO FAR, do something different.
          Never repeat a failing action.
        - Never type into an element marked password, and never put a password in "text": a
          password field is only ever filled from a saved login. Never pay, buy, confirm a
          purchase or delete something the user did not name — if the task needs that, stop
          with done and say why, briefly.
        - A sign-in screen is what sign_in is for. If it answers that there is no saved login,
          or that the page is a web page it cannot type into, stop with done and say so in one
          line: the user will be asked instead. Do not ask for a password in the chat, do not
          guess at one, and do not try to type it yourself.
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
