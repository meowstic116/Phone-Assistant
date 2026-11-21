package com.phoneassistant

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/** Android uses the enabled notification-listener component as authorization for media sessions.
 * Don't request notification history or read notification titles, bodies, or extras.
 */
class PipMediaAccess : NotificationListenerService() {
    companion object {
        private fun component(ctx: Context) = ComponentName(ctx, PipMediaAccess::class.java)

        fun enabled(ctx: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 27) {
            ctx.getSystemService(android.app.NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(ctx)) == true
        } else {
            // API 26 does not expose isNotificationListenerAccessGranted.
            android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
        }

        /** Bounded to an existing local Spotify session; never fall back to media keys or taps. */
        internal fun controlSpotify(ctx: Context, action: CarCommand.Spotify): String {
            if (!enabled(ctx)) return "Enable Pip’s Now playing access when you're parked."
            return try {
                val manager = ctx.getSystemService(MediaSessionManager::class.java)
                    ?: return "I couldn’t reach Android’s media controls."
                val spotify = manager.getActiveSessions(component(ctx)).firstOrNull {
                    it.packageName == "com.spotify.music" &&
                        it.playbackInfo?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
                } ?: return "There’s no local Spotify session yet. Start Spotify when parked."
                val available = spotify.playbackState?.actions ?: return "Spotify isn’t offering playback controls."
                val required = when (action) {
                    CarCommand.Spotify.PLAY -> PlaybackState.ACTION_PLAY
                    CarCommand.Spotify.PAUSE -> PlaybackState.ACTION_PAUSE
                    CarCommand.Spotify.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
                    CarCommand.Spotify.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                }
                if (available and required == 0L) return "Spotify isn’t offering that control right now."
                val controls = spotify.transportControls
                when (action) {
                    CarCommand.Spotify.PLAY -> controls.play()
                    CarCommand.Spotify.PAUSE -> controls.pause()
                    CarCommand.Spotify.NEXT -> controls.skipToNext()
                    CarCommand.Spotify.PREVIOUS -> controls.skipToPrevious()
                }
                "I sent that to Spotify."
            } catch (_: RuntimeException) { "I couldn’t reach Spotify’s playback controls." }
        }

        fun answer(ctx: Context): String {
            if (ctx.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) {
                return "Unlock your phone so I can check what’s playing."
            }
            if (!enabled(ctx)) {
                return "To check what’s playing, allow Now playing access in Pip settings → Permissions, then ask again."
            }
            val sessions = try {
                val manager = ctx.getSystemService(MediaSessionManager::class.java)
                    ?: return "I can’t reach Android’s media controls right now."
                manager.getActiveSessions(component(ctx)).map { session ->
                    val playing = session.playbackState?.state == PlaybackState.STATE_PLAYING
                    val metadata = if (playing) session.metadata else null
                    fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                        metadata?.getText(key)?.toString()?.replace(Regex("\\s+"), " ")?.trim()
                            ?.take(140)?.takeIf { it.isNotBlank() }
                    }
                    NowPlaying.Track(playing,
                        field(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                        field(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
                }
            } catch (_: RuntimeException) {
                return "I couldn’t read Android’s media controls. Check Now playing access in Pip settings → Permissions and try again."
            }
            return NowPlaying.answer(sessions)
        }
    }
}
