package com.phoneassistant

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.phoneassistant.SetupPolicy.Permission

/** Android owns permission grants and the foreground-service notification. */
internal object PipPermissions {
    // Keep the established ID so a user's existing channel settings carry over.
    const val STATUS_CHANNEL = "assistant"

    data class Row(val title: Int, val description: Int, val manage: Int = R.string.permissions_manage)

    fun row(permission: Permission): Row = when (permission) {
        Permission.NOTIFICATIONS -> Row(R.string.permissions_notifications, R.string.permissions_notifications_info,
            R.string.permissions_notifications_manage)
        Permission.NOTIFICATION_ACCESS -> Row(R.string.permissions_now_playing, R.string.permissions_now_playing_info,
            R.string.permissions_now_playing_manage)
        Permission.OVERLAY -> Row(R.string.permissions_overlay, R.string.permissions_overlay_info)
        Permission.MICROPHONE -> Row(R.string.permissions_microphone, R.string.permissions_microphone_info,
            R.string.permissions_microphone_manage)
        Permission.USAGE -> Row(R.string.permissions_usage, R.string.permissions_usage_info)
        Permission.ACCESSIBILITY -> Row(R.string.permissions_access_android, R.string.setup_access_optional)
    }

    fun granted(ctx: Context, permission: Permission): Boolean = when (permission) {
        Permission.NOTIFICATIONS -> notificationsEnabled(ctx)
        Permission.NOTIFICATION_ACCESS -> PipMediaAccess.enabled(ctx)
        Permission.OVERLAY -> Settings.canDrawOverlays(ctx)
        Permission.MICROPHONE -> WakeListenService.hasMicPermission(ctx)
        Permission.USAGE -> AppWatcher.hasPermission(ctx)
        Permission.ACCESSIBILITY -> ImeWatch.enabled(ctx)
    }

    fun manage(ctx: Context, permission: Permission) {
        when (permission) {
            Permission.NOTIFICATIONS -> openNotifications(ctx)
            Permission.NOTIFICATION_ACCESS -> openMediaAccess(ctx)
            Permission.OVERLAY -> openOverlay(ctx)
            Permission.MICROPHONE -> openAppSettings(ctx)
            Permission.USAGE -> openUsage(ctx)
            Permission.ACCESSIBILITY -> openAccessibility(ctx)
        }
    }

    fun requestFromSetup(activity: Activity, permission: Permission) {
        val runtime = when (permission) {
            Permission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
            Permission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
        if (runtime == null || activity.checkSelfPermission(runtime) == PackageManager.PERMISSION_GRANTED) {
            manage(activity, permission)
            return
        }
        // If Android no longer shows the runtime dialog after denial, leave a route to the grant.
        val asked = activity.getSharedPreferences("setup_permission_requests", Context.MODE_PRIVATE)
        if (asked.getBoolean(runtime, false) && !activity.shouldShowRequestPermissionRationale(runtime)) {
            manage(activity, permission)
        } else {
            asked.edit().putBoolean(runtime, true).apply()
            activity.requestPermissions(arrayOf(runtime), 700 + permission.ordinal)
        }
    }

    private fun openAppSettings(ctx: Context) = open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(ctx)))

    fun openOverlay(ctx: Context) = open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(ctx)))
    fun openUsage(ctx: Context) = open(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openAccessibility(ctx: Context) = open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openMediaAccess(ctx: Context) = open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openNotifications(ctx: Context) = open(ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))

    fun notificationsEnabled(ctx: Context): Boolean {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(STATUS_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun packageUri(ctx: Context) = Uri.fromParts("package", ctx.packageName, null)

    private fun open(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some Android builds don't expose the individual settings panel.
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(ctx)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, R.string.permissions_settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
