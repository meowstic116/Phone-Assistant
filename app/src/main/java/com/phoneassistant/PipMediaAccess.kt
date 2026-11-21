package com.phoneassistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keep the existing component so an Android media-access grant survives app updates. */
class PipMediaAccess : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        private var connected: PipMediaAccess? = null

        internal fun canShowMessage(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

        internal fun messageAnswer(ctx: Context, request: MessageNotifications.Request): PrivateMessageRead.Result {
            val locked = PrivateMessageRead.Result("Unlock your phone to check message notifications.")
            if (!canShowMessage(ctx)) return locked
            if (!PipSettings.enabled(ctx, PipSettings.Option.MESSAGE_NOTIFICATIONS)) {
                return PrivateMessageRead.Result("Turn on Read message notifications in Pip settings → Permissions, then ask again.")
            }
            val explicitApp = request.app?.let { PrivateMessageRead.appFor(ctx, it) ?: return PrivateMessageRead.Result(
                "I couldn’t identify that messaging app. Open the conversation and share the message to Read with Pip.") }
            val defaultApp = explicitApp ?: PrivateMessageRead.defaultApp(ctx)
            if (!enabled(ctx)) {
                return PrivateMessageRead.Result("I don’t have Notification access, so I can’t check the preview.", app = defaultApp)
            }
            val active = try { connected?.activeNotifications }
                catch (_: RuntimeException) { null }
                ?: return PrivateMessageRead.Result("Android hasn’t made your notifications available to me.", app = defaultApp)
            val candidates = active.asSequence().filter { it.packageName != ctx.packageName &&
                (explicitApp == null || it.packageName == explicitApp) }
                .flatMap { runCatching { previews(it) }.getOrDefault(emptyList()).asSequence() }.toList()
            if (!canShowMessage(ctx)) return locked
            return when (val match = MessageNotifications.match(request.sender, candidates)) {
                MessageNotifications.Match.Ambiguous -> PrivateMessageRead.Result(
                    "More than one sender matches that name. Ask again using their full name.")
                MessageNotifications.Match.Missing -> PrivateMessageRead.Result(
                    "I couldn’t find a readable notification from that sender.", app = defaultApp)
                is MessageNotifications.Match.Found -> {
                    val preview = match.preview
                    if (!preview.body.isNullOrBlank()) {
                        PrivateMessageRead.Result("From ${preview.sender}: “${preview.body}”", quote = preview.body)
                    } else {
                        val source = active.firstOrNull { it.key == preview.sourceKey }
                        PrivateMessageRead.Result("I found the notification, but its text isn’t readable.",
                            app = source?.packageName ?: defaultApp, conversation = source?.notification?.contentIntent)
                    }
                }
            }
        }

        @Suppress("DEPRECATION") // Legacy MessagingStyle uses sender name instead of Person.
        private fun previews(entry: StatusBarNotification): List<MessageNotifications.Preview> {
            val notification = entry.notification
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return emptyList()
            // Use per-message metadata, not potentially misleading conversation titles. Never
            // read the historic or inline-reply extras.
            val extras = notification.extras ?: return emptyList()
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
            PrivateMessageRead.noteMessageSource(entry.packageName)
            val ownPerson = if (Build.VERSION.SDK_INT >= 28) {
                extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
            } else null
            val ownName = MessageNotifications.excerpt(
                ownPerson?.name ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME), 100)
            fun incoming(person: Person?, legacyName: CharSequence?, text: CharSequence?, sentAt: Long): MessageNotifications.Preview? {
                val sender = MessageNotifications.excerpt(
                    (if (Build.VERSION.SDK_INT >= 28) person?.name else null) ?: legacyName, 100) ?: return null
                val self = if (Build.VERSION.SDK_INT >= 28 && person != null && ownPerson != null) {
                    when {
                        person.key != null && ownPerson.key != null -> person.key == ownPerson.key
                        person.uri != null && ownPerson.uri != null -> person.uri == ownPerson.uri
                        else -> sender == ownName
                    }
                } else sender == ownName
                if (self) return null
                return MessageNotifications.Preview(sender, text?.toString(), sentAt, entry.postTime, entry.key)
            }
            return if (Build.VERSION.SDK_INT >= 30) {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages).mapNotNull {
                    incoming(it.senderPerson, it.sender, it.text, it.timestamp)
                }
            } else {
                // The framework didn't expose a public decoder before API 30. Use the fields
                // written by Android's MessagingStyle.Message, and skip extras of other types.
                messages.filterIsInstance<Bundle>().mapNotNull {
                    val person = if (Build.VERSION.SDK_INT >= 28) it.getParcelable<Person>("sender_person") else null
                    incoming(person, it.getCharSequence("sender"), it.getCharSequence("text"), it.getLong("time"))
                }
            }
        }

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
            if (!enabled(ctx)) return "Allow Pip’s Notification access when you're parked."
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
                return "To check what’s playing, allow Notification access in Pip settings → Permissions, then ask again."
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
                return "I couldn’t read Android’s media controls. Check Notification access in Pip settings → Permissions and try again."
            }
            return NowPlaying.answer(sessions)
        }
    }
}
