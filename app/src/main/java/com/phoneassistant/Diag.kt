package com.phoneassistant

import android.content.Context
import android.os.SystemClock

/**
 * What has gone wrong lately, kept so the diagnostics screen can show it.
 *
 * Two kinds of failure live here: a request the brain refused or never completed, which the
 * user already sees in a bubble, and an exception that was swallowed to keep the overlay
 * alive, which nobody saw. Recording the second kind is the point — a frame that throws every
 * 33 ms used to be invisible from the inside and from the outside.
 *
 * It also keeps the last phone job, step by step. That is not a failure in itself, but it is
 * what makes one readable: a job that goes wrong says one sentence in a bubble and stops, and
 * from the outside that is indistinguishable from a job that did nothing at all.
 *
 * Everything is in memory only; action traces retain a bounded recent window.
 */
object Diag {
    private const val MAX_STEPS = 120

    /** One failure: what it was, when it first happened, and how many times since. */
    data class Event(val text: String, val at: Long, val count: Int = 1)

    // These are fixed status messages, not recognition transcripts or reply contents.
    @Volatile var lastWakeInput: Event? = null
        private set
    @Volatile var lastWakeOutcome: Event? = null
        private set
    @Volatile var lastHandsFreeCommand: Event? = null
        private set
    /** What Android reported about the Android Auto connection at the last voice request. */
    @Volatile var lastCarConnection: Event? = null
        private set
    @Volatile var lastHandsFreeAudio: Event? = null
        private set
    /** The last "lock my screen" request and what happened to it. Never the user's words. */
    @Volatile var lastScreenLock: Event? = null
        private set

    internal fun wakeInput(status: WakeFeedback) { lastWakeInput = Event(status.message, now()) }
    internal fun wakeOutcome(status: WakeFeedback) { lastWakeOutcome = Event(status.message, now()) }
    internal fun handsFreeCommand(status: String) { lastHandsFreeCommand = Event(status, now()) }
    internal fun carConnection(status: String) { lastCarConnection = Event(status, now()) }
    internal fun handsFreeAudio(status: String) { lastHandsFreeAudio = Event(status, now()) }
    internal fun screenLock(status: String) { lastScreenLock = Event(status, now()) }

    /** The last refused or failed request. Null until the brain has been asked something. */
    @Volatile
    var lastApiError: Event? = null
        private set

    /** When a reply last came back successfully, on the uptime clock. */
    @Volatile
    var lastApiOkAt = 0L
        private set

    /**
     * A crash from a previous run, kept on disk because the process that saw it is gone.
     *
     * This exists because it is the only channel that survives: the phone is not plugged into
     * anything, nothing is logged where it can be read, and a screen that dies before its
     * first frame leaves no trace anyone can retrieve. Written from the uncaught handler, so
     * it catches even a failure during class loading that no try/catch in onCreate can.
     */
    @Volatile
    var lastCrash: Event? = null
        private set

    private const val CRASH_PREFS = "pip_diag"
    private const val CRASH_KEY = "last_crash"
    private const val CRASH_AT_KEY = "last_crash_wall"

    /** The last exception nobody reported, and a count if it keeps happening. */
    @Volatile
    var lastCaught: Event? = null
        private set

    /**
     * One thing a job tried: what it did, how that went, whether Android took it, and whether
     * it was pressed at all. [skipped] is the case worth being explicit about — the step was
     * abandoned rather than failed, because his own window would not get out of its way and a
     * press might have landed on it. [turn] marks the ones the model chose: everything else in
     * the record is the loop acting on that answer, not another question asked.
     */
    data class Step(
        val text: String,
        val outcome: String,
        val ok: Boolean,
        val skipped: Boolean = false,
        val turn: Boolean = false
    )

    /**
     * The last phone job from the words that asked for it to the line that ended it.
     *
     * [id] is the job's ticket. A job that has been replaced must not write into the record
     * of the one that replaced it, so every write names its own — a cancelled job can still
     * be finishing its last API call while the next one is already working.
     */
    data class Job(
        val id: Int,
        val request: String,
        val startedAt: Long,
        val steps: List<Step> = emptyList(),
        val reason: String? = null,
        val endedAt: Long = 0L,
        val actions: Int = 0,
        val turns: Int = 0,
        val modelCalls: Int = 0,
        val modelMs: Long = 0L,
        val longestModelMs: Long = 0L
    ) {
        val running: Boolean get() = reason == null
        val omitted: Int get() = actions - steps.size
    }

    /** The last job, live while it runs and left in place afterwards. Null until one starts. */
    @Volatile
    var lastJob: Job? = null
        private set

    /** A job is beginning; the previous record is replaced rather than added to. */
    @Synchronized
    fun jobStarted(id: Int, request: String) {
        lastJob = Job(id, oneLine(request, 200), now())
    }

    @Volatile
    var lastChatFailure: Event? = null
        private set

    /**
     * One action, as it happened, with what it was aimed at and what came back. [turn] marks
     * the ones the model chose; a press or a scroll the loop made on its own is not one.
     */
    @Synchronized
    fun jobStep(
        id: Int,
        text: String,
        outcome: String,
        ok: Boolean,
        skipped: Boolean = false,
        turn: Boolean = false
    ) {
        val current = lastJob ?: return
        if (current.id != id || !current.running) return
        val recent = if (current.steps.size >= MAX_STEPS) current.steps.drop(1) else current.steps
        lastJob = current.copy(
            actions = current.actions + 1,
            turns = current.turns + if (turn) 1 else 0,
            steps = recent + Step(
                oneLine(text, 120),
                oneLine(outcome, 120),
                ok,
                skipped,
                turn
            )
        )
    }

    /** Time waiting for the configured API, including retried calls and the final answer. */
    @Synchronized
    fun jobModelWait(id: Int, ms: Long) {
        val current = lastJob ?: return
        if (current.id != id || !current.running) return
        lastJob = current.copy(modelCalls = current.modelCalls + 1,
            modelMs = current.modelMs + ms, longestModelMs = maxOf(current.longestModelMs, ms))
    }

    /** Why the job stopped. The first reason wins: only one thing ends a job. */
    @Synchronized
    fun jobEnded(id: Int, reason: String) {
        val current = lastJob ?: return
        if (current.id != id || !current.running) return
        lastJob = current.copy(reason = oneLine(reason, 200), endedAt = now())
    }

    /** The brain refused, or could not be reached at all. [detail] is cut to fit one screen. */
    fun apiFailed(what: String, detail: String) {
        val clean = detail.replace("\n", " ").trim().take(200)
        lastApiError = Event(if (clean.isEmpty()) what else "$what · $clean", now())
    }

    fun apiOk() {
        lastApiOkAt = now()
    }

    /**
     * An exception nothing is going to show the user. A repeat of the same one keeps the first
     * time — that is when it started — and counts up, so a per-frame failure reads as ×900
     * rather than as one event that keeps changing hands.
     */
    fun caught(what: String, t: Throwable) {
        val text = describe(what, t)
        val previous = lastCaught
        lastCaught = if (previous != null && previous.text == text) {
            previous.copy(count = previous.count + 1)
        } else {
            Event(text, now())
        }
    }

    /**
     * "just now", "8s ago", "3m ago" — enough to tell a live fault from an old one. Shared
     * with the floating readout, which has to show the same age for the same event.
     */
    fun ago(at: Long): String {
        if (at <= 0L) return "never"
        val ms = SystemClock.elapsedRealtime() - at
        return when {
            ms < 1_500L -> "just now"
            ms < 60_000L -> "${ms / 1000}s ago"
            ms < 3_600_000L -> "${ms / 60_000}m ago"
            else -> "${ms / 3_600_000}h ago"
        }
    }

    // Exception messages can echo prompts, URLs or phone data. Never include them for chat.
    fun chatFailure(stage: String, error: Exception) {
        lastChatFailure = privateFailure(stage, error)
    }

    fun vaultFailure(error: Exception) {
        lastCaught = privateFailure("vault", error)
    }

    private fun privateFailure(stage: String, error: Exception): Event {
        val origin = error.stackTrace.firstOrNull { it.className.startsWith("com.phoneassistant.") }
        val where = origin?.let { " · ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return Event("$stage · ${error.javaClass.simpleName}$where", now())
    }

    private fun describe(what: String, t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name.substringAfterLast('.') }
        val message = t.message?.replace("\n", " ")?.trim()?.take(140).orEmpty()
        // The frame that threw, not the whole trace: enough to find the line without making
        // the readout unreadable, and the only clue available when nothing else is logged.
        val frame = writableFrame(t)
        val head = if (message.isEmpty()) "$what · $name" else "$what · $name: $message"
        return if (frame == null) head else "$head @ $frame"
    }

    /** The first stack frame inside Pip, which is the code that actually failed. */
    private fun writableFrame(t: Throwable): String? = runCatching {
        t.stackTrace.firstOrNull { it.className.startsWith("com.phoneassistant") }
            ?.let { "${it.fileName}:${it.lineNumber} ${it.methodName}" }
    }.getOrNull()

    // --------------------------------------------------------------- crash record

    /**
     * Records a crash that is about to kill the process, then returns so the caller can pass
     * it on to whoever handled it before. Never throws: a failure while recording is worse
     * than the failure being recorded.
     */
    fun recordCrash(t: Throwable, thread: Thread? = null) {
        runCatching {
            // t's own trace is the throw site. The dying thread's stack is not: by the time
            // the handler runs it holds the handler's frames, not the frames that failed.
            val frames = t.stackTrace.takeIf { it.isNotEmpty() } ?: thread?.stackTrace.orEmpty()
            val origin = frames.firstOrNull { it.className.startsWith("com.phoneassistant") }
            val where = origin?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val reason = "${t.javaClass.name}: ${t.message?.replace("\n", " ")?.trim().orEmpty()}".trim()
            val text = (if (where == null) reason else "$where · $reason").take(400)
            lastCrash = Event(text, now())
            // commit, not apply: apply writes to disk on another thread, and this process is
            // about to be killed before that thread gets a turn. It blocks, which is fine —
            // the process is dying anyway and nothing else is waiting on this.
            runCatching {
                crashPrefs(crashContext)?.edit()
                    ?.putString(CRASH_KEY, text)
                    ?.putLong(CRASH_AT_KEY, System.currentTimeMillis())
                    ?.commit()
            }
        }
    }

    /**
     * The context kept for the crash handler. The handler is handed a thread and a throwable
     * and nothing else, so the one thing it cannot supply for itself is saved up front.
     */
    @Volatile
    private var crashContext: Context? = null

    fun crashSink(context: Context) {
        crashContext = context.applicationContext
        runCatching { lastCrash = load(context) }
    }

    private fun crashPrefs(context: Context?) =
        context?.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): Event? = runCatching {
        val prefs = crashPrefs(context) ?: return null
        val text = prefs.getString(CRASH_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        Event(text, 0L)
    }.getOrNull()

    /** Wall-clock time of the recorded crash, so "yesterday" is not shown as "just now". */
    fun crashWhen(context: Context): Long =
        crashPrefs(context)?.getLong(CRASH_AT_KEY, 0L) ?: 0L

    /**
     * Installs the process-wide crash handler. Called once from the application object, before
     * anything else can fail, and passes every crash to whatever handler was there first so
     * ordinary crash reporting keeps working.
     */
    fun watchForCrashes(context: Context) {
        crashSink(context)
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                recordCrash(error, thread)
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** A one-line summary for the main screen, or null when nothing has crashed. */
    fun crashSummary(context: Context): String? {
        val text = lastCrash?.text ?: load(context)?.text ?: return null
        val when_ = crashWhen(context)
        val age = if (when_ > 0L) {
            val minutes = (System.currentTimeMillis() - when_) / 60_000L
            when {
                minutes < 1L -> "just now"
                minutes < 60L -> "${minutes}m ago"
                minutes < 1_440L -> "${minutes / 60}h ago"
                else -> "${minutes / 1_440}d ago"
            }
        } else "earlier"
        return "$text\n\n$age"
    }

    /** Forgets the record. Writes on a worker: clearing happens from a tap, not from a crash. */
    fun clearCrash(context: Context) {
        lastCrash = null
        val prefs = crashPrefs(context) ?: return
        Thread({ runCatching { prefs.edit().clear().apply() } }, "PipCrashClear").start()
    }

    private fun oneLine(text: String, max: Int): String =
        text.replace("\n", " ").trim().take(max)

    private fun now() = SystemClock.elapsedRealtime()
}
