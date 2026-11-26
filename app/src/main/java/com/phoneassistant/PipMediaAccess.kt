package com.phoneassistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
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

        internal fun messageAnswer(ctx: Context, request: MessageNotifications.Request,
            handsFree: Boolean = false): PrivateMessageRead.Result {
            val locked = PrivateMessageRead.Result("Unlock your phone to check message notifications.")
            // A check asked out loud is a check the user wanted read: answered in the car or
            // with the phone in a pocket just like the other driving commands. The unlocked
            // screens only ever show the text (never speak it unless asked) and that gate
            // stays on the screen side.
            if (!canShowMessage(ctx) && !handsFree) return locked
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
            if (!canShowMessage(ctx) && !handsFree) return locked
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

        /** A spoken digest of the shade: read-only, bounded, and never a model input. */
        internal fun notificationDigest(ctx: Context): String {
            if (!enabled(ctx)) return "Allow Pip’s Notification access when you're parked, then ask again."
            val active = try { connected?.activeNotifications }
                catch (_: RuntimeException) { null }
                ?: return "Android hasn’t made your notifications available to me."
            val rows = active.asSequence().filter { it.packageName != ctx.packageName }
                .mapNotNull { note -> runCatching { previews(note) }.getOrDefault(emptyList()).firstOrNull() }
                .sortedByDescending { maxOf(it.sentAt, it.postedAt) }
                .toList()
            if (rows.isEmpty()) return "I can’t read any of your current notifications."
            val shown = rows.take(5)
            val lines = shown.joinToString(" ") { preview ->
                val body = MessageNotifications.excerpt(preview.body, 90)
                if (body == null) "From ${preview.sender}." else "From ${preview.sender}: $body"
            }
            val count = if (rows.size == shown.size) {
                "${rows.size} notification${if (rows.size == 1) "" else "s"}"
            } else {
                "the latest ${shown.size} of ${rows.size} notifications"
            }
            return "You have $count. $lines"
        }

        @Suppress("DEPRECATION") // Legacy MessagingStyle uses sender name instead of Person.
        /** ownPerson is only non-null on API 28+, so Person#getName is never reached below it. */
        @SuppressLint("NewApi")
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

        /** Current notifications, or null when Android has not granted Pip access. */
        internal fun snapshot(): Array<StatusBarNotification>? = try { connected?.activeNotifications }
        catch (_: RuntimeException) { null }

        fun enabled(ctx: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 27) {
            ctx.getSystemService(android.app.NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(component(ctx)) == true
        } else {
            // API 26 does not expose isNotificationListenerAccessGranted.
            android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
        }

        /**
         * Bounded to sessions Android already shows: a transport call rides on whatever is
         * playing, in whatever app has it, and starting content stays Spotify's search — no
         * fallback to media keys or taps. A call is only reported as done once the session
         * shows the new state, so "playing the playlist" is never said when nothing started.
         */
        internal fun controlSpotify(ctx: Context, action: CarCommand.Media,
                                    done: (DrivingVoice.Result) -> Unit) {
            val app = ctx.applicationContext
            // Session lookups and the short verification wait must never run on the UI thread.
            Thread {
                val result = runCatching { sendSpotify(app, action) }.getOrElse {
                    DrivingVoice.Result("I couldn’t reach Spotify’s playback controls.")
                }
                Handler(Looper.getMainLooper()).post { done(result) }
            }.start()
        }

        private fun sendSpotify(ctx: Context, action: CarCommand.Media): DrivingVoice.Result {
            fun unavailable(message: String) = DrivingVoice.Result(message)
            return try {
                if (!enabled(ctx)) return unavailable("Allow Pip’s Notification access when you're parked.")
                val manager = ctx.getSystemService(MediaSessionManager::class.java)
                    ?: return unavailable("I couldn’t reach Android’s media controls.")
                // During a call the car may be using the phone's audio path; an empty Spotify
                // list is worth saying plainly instead of a generic failure.
                fun localSessions() = runCatching {
                    manager.getActiveSessions(component(ctx)).filter {
                        it.playbackInfo?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
                    }
                }.getOrDefault(emptyList())
                var sessions = localSessions()
                var spotify = sessions.firstOrNull { it.packageName == SpotifyQuickPlan.PACKAGE }
                // "Play something" from cold is the normal ask. Starting content opens
                // Spotify and waits for its session instead of refusing a request the
                // phone can still do; a pause or skip with nothing running still says so.
                if (spotify == null && (action is CarCommand.SpotifyPlaylist || action == CarCommand.Spotify.PLAY)) {
                    val launch = runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(SpotifyQuickPlan.PACKAGE)
                    }.getOrNull()
                    if (launch != null) {
                        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        var waits = 0
                        while (spotify == null && waits < 15) {
                            android.os.SystemClock.sleep(200)
                            waits++
                            sessions = localSessions()
                            spotify = sessions.firstOrNull { it.packageName == SpotifyQuickPlan.PACKAGE }
                        }
                    }
                }
                // A transport request rides on whatever is playing: in the car it may well be
                // another app. Starting content is different — that stays Spotify's search.
                val target = when (action) {
                    is CarCommand.SpotifyPlaylist -> spotify
                        ?: return unavailable("I can’t see a Spotify session. Open Spotify on the phone once when parked, then try again.")
                    else -> sessions.firstOrNull { playing(it) } ?: spotify ?: sessions.firstOrNull()
                        ?: return unavailable("I can’t see anything playing. Start music on the phone once when parked, then try again.")
                }
                val label = Screen.appLabel(ctx, target.packageName) ?: "That app"
                val state = runCatching { target.playbackState?.state }.getOrNull()
                // Pause while paused and play while playing are answers, not failed calls —
                // and a session may not advertise a control for the state it is already in.
                val arrived = when (action) {
                    CarCommand.Spotify.PLAY -> state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    CarCommand.Spotify.PAUSE -> state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED
                    else -> false
                }
                if (arrived) return DrivingVoice.Result(
                    if (action == CarCommand.Spotify.PLAY) "$label is playing." else "$label is paused.",
                    handedOff = true, verified = true)
                val available = target.playbackState?.actions ?: 0L
                val required = when (action) {
                    CarCommand.Spotify.PLAY -> PlaybackState.ACTION_PLAY
                    CarCommand.Spotify.PAUSE -> PlaybackState.ACTION_PAUSE
                    CarCommand.Spotify.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
                    CarCommand.Spotify.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    is CarCommand.SpotifyPlaylist -> PlaybackState.ACTION_PLAY_FROM_SEARCH
                }
                // Spotify rarely advertises PLAY_FROM_SEARCH even while it answers the call,
                // so starting content is sent and believed only when the music says so.
                if (action !is CarCommand.SpotifyPlaylist && available and required == 0L)
                    return unavailable("$label isn’t offering that control right now.")
                val before = target.playbackState
                val titleBefore = mediaTitle(target)
                val controls = target.transportControls
                when (action) {
                    CarCommand.Spotify.PLAY -> controls.play()
                    CarCommand.Spotify.PAUSE -> controls.pause()
                    CarCommand.Spotify.NEXT -> controls.skipToNext()
                    CarCommand.Spotify.PREVIOUS -> controls.skipToPrevious()
                    is CarCommand.SpotifyPlaylist -> controls.playFromSearch(action.title,
                        if (action.namedPlaylist) Bundle().apply {
                            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                            putString(MediaStore.EXTRA_MEDIA_PLAYLIST, action.title)
                        } else Bundle())
                }
                val confirmed = awaitChange(target, before, action, titleBefore)
                when {
                    confirmed -> DrivingVoice.Result(when {
                        action is CarCommand.SpotifyPlaylist -> "$label is playing ${action.title}."
                        action == CarCommand.Spotify.PLAY -> "$label is playing."
                        action == CarCommand.Spotify.PAUSE -> "$label is paused."
                        else -> "$label changed track."
                    }, handedOff = true, verified = true)
                    action is CarCommand.SpotifyPlaylist -> DrivingVoice.Result(
                        "I asked $label for ${action.title}, but it isn’t playing that. It may not be on Spotify, or the car blocked it.",
                        handedOff = true)
                    else -> DrivingVoice.Result("I sent that to $label, but its playback didn’t change.", handedOff = true)
                }
            } catch (_: RuntimeException) { unavailable("I couldn’t reach the playback controls.") }
        }

        private fun playing(session: android.media.session.MediaController): Boolean =
            session.playbackState?.state == PlaybackState.STATE_PLAYING ||
                session.playbackState?.state == PlaybackState.STATE_BUFFERING
        private fun mediaTitle(session: android.media.session.MediaController): String? = runCatching {
            session.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        /**
         * Wait briefly for the session to report the effect of a transport call. A playlist
         * request also has to change the title: Spotify can already be playing something else,
         * and "playing" alone would wrongly confirm the wrong track.
         */
        private fun awaitChange(session: android.media.session.MediaController, before: PlaybackState?,
                                action: CarCommand.Media, titleBefore: String?): Boolean {
            val deadline = android.os.SystemClock.elapsedRealtime() + 4_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val now = runCatching { session.playbackState }.getOrNull()
                if (now != null) {
                    val playing = now.state == PlaybackState.STATE_PLAYING
                    val titleNow = mediaTitle(session)
                    val changed = when (action) {
                        CarCommand.Spotify.PLAY ->
                            // Already playing the same thing is a success, not a no-op.
                            playing || now.state == PlaybackState.STATE_BUFFERING
                        is CarCommand.SpotifyPlaylist ->
                            playing && titleNow != null && (titleBefore == null || titleNow != titleBefore ||
                                titleNow.contains(action.title, ignoreCase = true) ||
                                action.title.contains(titleNow, ignoreCase = true))
                        CarCommand.Spotify.PAUSE -> now.state == PlaybackState.STATE_PAUSED
                        CarCommand.Spotify.NEXT, CarCommand.Spotify.PREVIOUS ->
                            // A changed title is the clearest evidence; a skip state is the fallback.
                            playing && titleNow != null && titleNow != titleBefore ||
                                now.state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                                now.state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                    }
                    if (changed) return true
                    if (now.state == PlaybackState.STATE_ERROR) return false
                }
                android.os.SystemClock.sleep(150L)
            }
            return false
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
