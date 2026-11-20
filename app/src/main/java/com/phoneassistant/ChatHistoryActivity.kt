package com.phoneassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    override fun onDestroy() {
        OverlayService.endHistoryAttention()
        super.onDestroy()
    }

    private fun send() {
        if (!sendButton.isEnabled) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val turn = ChatTurns.begin()
        pendingTurn = turn
        input.setText("")
        addBubble(text, true)
        Memory.append(this, "user", text)

        sendButton.isEnabled = false
        Agent.cancel()
        OverlayService.beginThinking()
        if (JobProgress.routePhoneLocally(text)) {
            showJobStarted(OverlayService.runPhoneJob(text, text, turn))
            return
        }
        val event = OverlayService.reactTo(text)
        val placeholder = addBubble("…", false)

        AssistantBrain.reply(
            this,
            OverlayService.currentAppLabel,
            OverlayService.toysOnStage(),
            event,
            turn
        ) { ok, reply ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                messageList.removeView(placeholder)
                if (!ChatTurns.isCurrent(turn)) {
                    if (pendingTurn == turn) sendButton.isEnabled = true
                    return@runOnUiThread
                }
                // A delegated job is a JSON object, not a line: it belongs to his hands, and
                // the overlay is where it is watched happening. Printed here it would show
                // the user the protocol instead of an answer.
                val job = if (ok) Agent.delegated(reply) else null
                if (job != null) {
                    showJobStarted(OverlayService.runPhoneJob(job, text, turn))
                    return@runOnUiThread
                }
                addBubble(reply, false)
                if (ok) Memory.append(this, "assistant", reply)
                sendButton.isEnabled = true
                OverlayService.speak(reply)
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
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
