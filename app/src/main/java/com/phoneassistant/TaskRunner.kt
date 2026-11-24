package com.phoneassistant

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The clock and the watcher behind the two kinds of task. It runs while Pip's overlay
 * runs, and looks at the tasks:
 *
 * A scheduled task whose time has come goes the moment the phone is open and the user is
 * at the home screen doing nothing. While they are in the middle of something, Pip says
 * he is supposed to do the task and holds it until they are back at the home screen.
 *
 * An automatic task watches its app: every few seconds, and only when the visible screen
 * changed since the last look, its trigger is checked against what is on screen. A match
 * runs the task's action as an ordinary phone job, and the watch stands until the task is
 * deleted from the tasks menu.
 *
 * The looking is paced by what could actually happen (TaskClock.tickDelay): a second
 * while the screen is on, and while it is off, sleep to the next deadline instead of
 * ticking over a phone that cannot run or announce anything yet. A change to the task
 * store — a reminder said to Pip in a pocket counts — wakes the runner at once, so the
 * sleeping can never make a deadline late.
 */
internal object TaskRunner {

    private const val TICK_MS = 1_000L
    private const val AUTO_EVERY = 4
    private const val COOLDOWN_MS = 12_000L

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var sinceAuto = 0
    private var checking = false
    private val seenListing = mutableMapOf<String, Int>()
    private val lastFired = mutableMapOf<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            // A store change during the last step can queue a twin; one look is enough.
            handler.removeCallbacks(this)
            val ctx = app ?: return
            val delay = runCatching { step(ctx) }.getOrDefault(TICK_MS)
            handler.postDelayed(this, delay)
        }
    }

    /** A task just changed, or the phone just woke: look now, not at the old schedule. */
    fun poke() {
        if (app == null) return
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val rearm: () -> Unit = { poke() }

    fun start(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        PipTasks.addStoreListener(rearm)
        handler.post(tick)
    }

    fun stop() {
        app = null
        handler.removeCallbacks(tick)
        PipTasks.removeStoreListener(rearm)
        seenListing.clear()
        lastFired.clear()
        sinceAuto = 0
        checking = false
    }

    private fun interactive(ctx: Context) =
        runCatching { ctx.getSystemService(PowerManager::class.java)?.isInteractive == true }
            .getOrDefault(false)

    private fun unlocked(ctx: Context) =
        runCatching { ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true }
            .getOrDefault(false)

    private fun step(ctx: Context): Long {
        val now = System.currentTimeMillis()
        val tasks = PipTasks.all(ctx)
        val open = interactive(ctx)
        val free = unlocked(ctx)
        val busy = Agent.running()
        // The home check walks the accessibility windows, so it is asked once per look
        // and only when it can matter: a locked phone cannot be at the home screen any
        // more than it can run the task — and the announce below must still be reached.
        val atHome = tasks.any { it.kind == PipTask.Kind.SCHEDULED && it.fireAt <= now } &&
            open && free && !busy && Screen.clearHomeScreen(ctx)
        for (task in tasks) {
            if (task.kind != PipTask.Kind.SCHEDULED || task.fireAt > now) continue
            val ready = TaskGate.mayRunScheduled(open, free, atHome, busy)
            if (ready) {
                // A recurring time comes back for its next occurrence; a one-shot is done.
                if (task.repeat == PipTask.Repeat.NONE) PipTasks.remove(ctx, task.id)
                else PipTasks.update(ctx, task.copy(
                    fireAt = TaskClock.next(task.fireAt, task.repeat, now), announced = false))
                OverlayService.runPhoneJob(task.detail, task.detail, ChatTurns.begin())
            } else if (!task.announced) {
                PipTasks.update(ctx, task.copy(announced = true))
                OverlayService.speak(ctx, TaskCommand.deferred(task))
            }
        }
        if (++sinceAuto >= AUTO_EVERY) {
            sinceAuto = 0
            if (TaskGate.mayRunAutomatic(open, free, busy) && Screen.available()) {
                for (task in tasks) {
                    if (task.kind != PipTask.Kind.AUTOMATIC) continue
                    if (task.app.isNotEmpty() && Screen.inFront() != task.app) continue
                    watch(ctx, task)
                }
            }
        }
        // Upcoming deadlines are all the next look needs to know about; a task already
        // due has just been announced (or run) above and only waits for the gate, which
        // comes from a wake, not from another look.
        val nextFire = tasks.asSequence()
            .filter { it.kind == PipTask.Kind.SCHEDULED && it.fireAt > now }
            .minOfOrNull { it.fireAt }
        return TaskClock.tickDelay(now, nextFire, open)
    }

    private fun watch(ctx: Context, task: PipTask) {
        if (checking) return
        val now = System.currentTimeMillis()
        if (now - (lastFired[task.id] ?: 0L) < COOLDOWN_MS) return
        // A screen that did not change cannot have gained a trigger. This keeps the
        // standing watch free: a quiet screen costs nothing.
        val snapshot = Screen.snapshot() ?: return
        val seen = snapshot.listing.hashCode()
        if (seenListing[task.id] == seen) return
        seenListing[task.id] = seen
        checking = true
        AssistantBrain.screenCheck(ctx, "Does this screen show: ${task.trigger}?") { yes ->
            handler.post {
                checking = false
                val alive = app ?: return@post
                if (yes == true && TaskGate.mayRunAutomatic(interactive(alive), unlocked(alive), Agent.running())) {
                    lastFired[task.id] = System.currentTimeMillis()
                    OverlayService.runPhoneJob(task.detail, task.detail, ChatTurns.begin())
                }
            }
        }
    }
}
