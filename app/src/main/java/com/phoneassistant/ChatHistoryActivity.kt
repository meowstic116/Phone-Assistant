package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The full conversation, and a place to keep chatting. Replies here also surface in
 * Pip's overlay bubble, which floats above this screen because overlay windows sit
 * over activities.
 */
class ChatHistoryActivity : Activity() {

    private lateinit var messageList: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private var pendingTurn: Long? = null
    private var messagePreview: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        messageList = findViewById(R.id.messageList)
        scroller = findViewById(R.id.scroller)
        input = findViewById(R.id.input)
        sendButton = findViewById(R.id.sendButton)

        findViewById<TextView>(R.id.titleLabel).text = Persona.NAME

        val history = Memory.history(this)
        history.forEach { addBubble(it.content, it.role == "user") }
        if (history.isEmpty()) {
            addBubble("Nothing here yet. Say something and it will show up.", false)
        }

        findViewById<TextView>(R.id.clearButton).setOnClickListener {
            ChatTurns.begin()
            OverlayService.clearConversationFeedback()
            sendButton.isEnabled = true
            Memory.clear(this)
            messageList.removeAllViews()
            addBubble("Clean slate.", false)
        }

        input.filters = arrayOf(android.text.InputFilter.LengthFilter(Memory.MAX_MESSAGE_CHARS))
        sendButton.setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        // He stands still and moves up out of the way while this screen is open, so
        // the bubble is readable and he never sits on the text field.
        OverlayService.beginHistoryAttention()
    }

    override fun onStop() {
        messagePreview?.dismiss()
        messagePreview = null
        super.onStop()
    }

    override fun onDestroy() {
        OverlayService.endHistoryAttention()
        super.onDestroy()
    }

    private fun send() {
        if (!sendButton.isEnabled) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        PipVoice.stop()
        val turn = ChatTurns.begin()
        pendingTurn = turn
        input.setText("")
        MessageNotifications.request(text)?.let { request ->
            OverlayService.clearConversationFeedback()
            PrivateMessageRead.present(this, PipMediaAccess.messageAnswer(this, request), turn) { reply, quote ->
                if (quote) {
                    messagePreview = AlertDialog.Builder(this)
                        .setTitle("Message notification")
                        .setMessage(reply)
                        .setPositiveButton(android.R.string.ok, null)
                        .create().apply {
                            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            setOnDismissListener { messagePreview = null; PipVoice.stop() }
                            show()
                        }
                } else Toast.makeText(this, reply, Toast.LENGTH_LONG).show()
            }
            return
        }
        addBubble(text, true)
        if (NowPlaying.requested(text)) {
            Agent.cancel()
            val reply = ChatRecovery.attempt(ChatRecovery.REPLY_FAILED, { Diag.chatFailure("now playing reply", it) }) {
                PipMediaAccess.answer(this)
            }
            addBubble(reply, false)
            OverlayService.speak(this, reply)
            return
        }
        ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
            Memory.append(this, "user", text)
        }

        sendButton.isEnabled = false
        Agent.cancel()
        ChatRecovery.attempt<String?>(null, { Diag.chatFailure("chat preferred name", it) }) {
            Facts.saveNameRequest(this, text)
        }?.let { reply ->
            ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
                Memory.append(this, "assistant", reply)
            }
            addBubble(reply, false)
            sendButton.isEnabled = true
            OverlayService.speak(this, reply)
            return
        }
        OverlayService.beginThinking()
        if (JobProgress.routePhoneLocally(text)) {
            showJobStarted(OverlayService.runPhoneJob(text, text, turn))
            return
        }
        // A time or a condition plus what to do makes a task, no round trip needed.
        TaskCommand.localRequest(text)?.takeIf { !PurchasePolicy.asksToPay(it.detail) }?.let { request ->
            val created = PipTask.from(request, System.currentTimeMillis(), System.nanoTime().toInt())
            val line = if (created == null) {
                "I couldn’t work out when or what for that one. Try again with a time, like “at 5pm”."
            } else {
                PipTasks.add(this, created)
                TaskCommand.confirmed(created, System.currentTimeMillis())
            }
            ChatRecovery.attempt(Unit, { Diag.chatFailure("chat task", it) }) {
                Memory.append(this, "assistant", line)
            }
            addBubble(line, false)
            sendButton.isEnabled = true
            OverlayService.speak(this, line)
            return
        }
        val event = ChatRecovery.attempt<String?>(null, { Diag.chatFailure("chat toy reaction", it) }) {
            OverlayService.reactTo(text)
        }
        val placeholder = addBubble("…", false)

        AssistantBrain.reply(
            this,
            text,
            OverlayService.toysOnStage(),
            event,
            turn
        ) { ok, reply ->
            runOnUiThread {
                val visible = !isDestroyed && !isFinishing
                if (visible) messageList.removeView(placeholder)
                if (!ChatTurns.isCurrent(turn)) {
                    if (visible && pendingTurn == turn) sendButton.isEnabled = true
                    return@runOnUiThread
                }
                // A delegated job is a JSON object, not a line: it belongs to his hands, and
                // the overlay is where it is watched happening. Printed here it would show
                // the user the protocol instead of an answer.
                val job = if (ok) Agent.delegated(reply) else null
                if (job != null) {
                    if (visible) showJobStarted(OverlayService.runPhoneJob(job, text, turn))
                    return@runOnUiThread
                }
                // The same words can make a standing task instead of a one-time job.
                val created = if (ok) AssistantBrain.taskRequest(MemorySuggestion.splitReply(reply).line) else null
                if (created != null && !PurchasePolicy.asksToPay(created.detail)) {
                    PipTasks.add(this, created)
                    val line = TaskCommand.confirmed(created, System.currentTimeMillis())
                    ChatRecovery.attempt(Unit, { Diag.chatFailure("chat task", it) }) {
                        Memory.append(this, "assistant", line)
                    }
                    if (!visible) return@runOnUiThread
                    addBubble(line, false)
                    sendButton.isEnabled = true
                    OverlayService.speak(this, line)
                    return@runOnUiThread
                }
                // A plain reply still belongs in history if Android recreated or closed this screen.
                if (ok) ChatRecovery.attempt(Unit, { Diag.chatFailure("chat history", it) }) {
                    Memory.append(this, "assistant", reply)
                }
                if (!visible) return@runOnUiThread
                addBubble(reply, false)
                sendButton.isEnabled = true
                OverlayService.speak(this, reply)
            }
        }
    }

    private fun showJobStarted(started: Boolean) {
        addBubble(
            when {
                started -> "On it — watch the bubble."
                !OverlayService.running -> "Start Pip first, then try again."
                !PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY) ->
                    "Accessibility use is turned off in Pip settings."
                else -> "I couldn't start that job. Check that accessibility is enabled."
            },
            false
        )
        sendButton.isEnabled = true
    }

    private fun addBubble(text: String, fromUser: Boolean): View {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(if (fromUser) R.drawable.bubble_user else R.drawable.bubble_assistant)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = if (fromUser) Gravity.END else Gravity.START
        params.topMargin = dp(8)

        messageList.addView(bubble, params)
        // Scroll without trying to focus a child; fullScroll(FOCUS_DOWN) can steal the
        // editor's focus and dismiss the keyboard as a reply arrives.
        scroller.post { scroller.smoothScrollTo(0, messageList.height) }
        return bubble
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
