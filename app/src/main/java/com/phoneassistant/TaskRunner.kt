package com.phoneassistant

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The clock and the watcher behind the two kinds of task. It runs while Pip's overlay
 * runs, and looks once a second:
 *
 * A scheduled task whose time has come goes the moment the phone is open and the user is
 * at the home screen doing nothing. While they are in the middle of something, Pip says
 * he is supposed to do the task and holds it until they are back at the home screen.
 *
 * An automatic task watches its app: every few seconds, and only when the visible screen
 * changed since the last look, its trigger is checked against what is on screen. A match
 * runs the task's action as an ordinary phone job, and the watch stands until the task is
 * deleted from the tasks menu.
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
            val ctx = app ?: return
            handler.postDelayed(this, TICK_MS)
            runCatching { step(ctx) }
        }
    }

    fun start(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        handler.post(tick)
    }

    fun stop() {
        app = null
        handler.removeCallbacks(tick)
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

    private fun step(ctx: Context) {
        val now = System.currentTimeMillis()
        val tasks = PipTasks.all(ctx)
        val open = interactive(ctx)
        val free = unlocked(ctx)
        val busy = Agent.running()
        for (task in tasks) {
            if (task.kind != PipTask.Kind.SCHEDULED || task.fireAt > now) continue
            val ready = TaskGate.mayRunScheduled(open, free, atHome = Screen.clearHomeScreen(ctx), agentBusy = busy)
            if (ready) {
                PipTasks.remove(ctx, task.id)
                OverlayService.runPhoneJob(task.detail, task.detail, ChatTurns.begin())
            } else if (!task.announced) {
                PipTasks.update(ctx, task.copy(announced = true))
                OverlayService.speak(ctx, TaskCommand.deferred(task))
            }
        }
        if (++sinceAuto < AUTO_EVERY) return
        sinceAuto = 0
        if (!TaskGate.mayRunAutomatic(open, free, busy) || !Screen.available()) return
        for (task in tasks) {
            if (task.kind != PipTask.Kind.AUTOMATIC) continue
            if (task.app.isNotEmpty() && Screen.inFront() != task.app) continue
            watch(ctx, task)
        }
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
