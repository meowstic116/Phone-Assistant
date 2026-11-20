package com.phoneassistant

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Android owns permission grants and the foreground-service notification. */
internal object PipPermissions {
    // Keep the established ID so a user's existing channel settings carry over.
    const val STATUS_CHANNEL = "assistant"

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
