package com.phoneassistant

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Answers "which app is the user in right now" using Usage Access.
 * This reads app identities only — never screen contents — which is why it needs no
 * accessibility service: [Screen] is the only thing that reads a screen, and only when the
 * user has given Pip a job.
 */
object AppWatcher {

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentAppLabel(ctx: Context): String? {
        val packageName = currentPackage(ctx) ?: return null
        return labelFor(ctx, packageName) ?: packageName
    }

    private fun currentPackage(ctx: Context): String? {
        if (!hasPermission(ctx)) return null
        val usage = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 90_000L, now) ?: return null

        var latestPackage: String? = null
        var latestStamp = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.packageName == ctx.packageName) continue
                if (event.timeStamp >= latestStamp) {
                    latestStamp = event.timeStamp
                    latestPackage = event.packageName
                }
            }
        }
        return latestPackage
    }

    private fun labelFor(ctx: Context, packageName: String): String? = try {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (t: Throwable) {
        null
    }
}
