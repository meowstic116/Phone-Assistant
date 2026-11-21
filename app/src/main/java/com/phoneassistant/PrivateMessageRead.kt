package com.phoneassistant

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

/** This route never invokes the model, an accessibility job, or persistent chat history. */
internal object PrivateMessageRead {
    private val packages = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.messaging", "com.android.mms", "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms",
        "org.telegram.messenger", "org.telegram.messenger.web", "com.facebook.orca", "com.discord", "com.viber.voip",
        "jp.naver.line.android", "com.tencent.mm", "com.snapchat.android", "com.instagram.android", "com.Slack",
        "com.microsoft.teams", "com.google.android.gm", "com.microsoft.office.outlook")

    private val observed = ConcurrentHashMap.newKeySet<String>()

    fun noteMessageSource(pkg: String) { observed.add(pkg) }

    fun messagingPackage(ctx: Context, pkg: String): Boolean = pkg in packages || pkg in observed || pkg == defaultApp(ctx)

    fun defaultApp(ctx: Context): String? = Screen.defaultCommunicationPackage(ctx, JobProgress.CommunicationKind.MESSAGE)

    fun appFor(ctx: Context, name: String): String? = if (name.equals("Messages", true)) defaultApp(ctx) else
        Screen.apps(ctx).singleOrNull {
            messagingPackage(ctx, it.packageName) && (it.label.equals(name, true) || it.packageName.equals(name, true))
        }?.packageName

    data class Result(val text: String, val quote: String? = null, val app: String? = null,
                      val conversation: PendingIntent? = null)

    fun present(ctx: Context, result: Result, turn: Long, show: (String, Boolean) -> Unit) {
        fun current() = ChatTurns.isCurrent(turn) && PipMediaAccess.canShowMessage(ctx)
        if (!current()) return
        val app = result.app
        val label = app?.let { Screen.appLabel(ctx, it) ?: "your messaging app" }
        val announcement = if (label == null) result.text else "${result.text} I’ll open $label to check."
        show(announcement, result.quote != null)
        fun voiceProblem(text: String) {
            if (current()) Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
        // Spoken in order: explain the missing preview, then hand off locally to the app.
        if (app != null) {
            PipVoice.readPrivate(ctx, announcement, ::voiceProblem) open@{
                if (!current()) return@open
                val conversation = result.conversation?.takeIf { Build.VERSION.SDK_INT >= 31 && it.creatorPackage == app && it.isActivity }
                val tapped = conversation != null && runCatching { conversation.send() }.isSuccess
                val launched = tapped || Screen.apps(ctx).firstOrNull { it.packageName == app }?.let { Screen.open(ctx, it) } == true
                if (launched) {
                    Toast.makeText(ctx, "Open the conversation if needed, then share the message to Read with Pip.", Toast.LENGTH_LONG).show()
                } else voiceProblem("Android couldn’t open $label.")
            }
        } else {
            PipVoice.readPrivate(ctx, result.quote ?: announcement, ::voiceProblem)
        }
    }
}
