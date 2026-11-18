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
 * A normal chat screen with a real keyboard — the overlay stays out of the way of
 * the IME, and the character's mood mirrors what is happening here.
 */
class ChatActivity : Activity() {

    private lateinit var messageList: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var contextLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        messageList = findViewById(R.id.messageList)
        scroller = findViewById(R.id.scroller)
        input = findViewById(R.id.input)
        sendButton = findViewById(R.id.sendButton)
        contextLabel = findViewById(R.id.contextLabel)

        findViewById<TextView>(R.id.titleLabel).text = Persona.NAME

        val history = Memory.history(this)
        history.forEach { addBubble(it.content, it.role == "user") }
        if (history.isEmpty()) {
            addBubble("Hey. I'm " + Persona.NAME + ". Tap me on your screen any time.", false)
        }

        findViewById<TextView>(R.id.clearButton).setOnClickListener {
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
    }

    override fun onResume() {
        super.onResume()
        val app = OverlayService.currentAppLabel
        contextLabel.text = if (app != null) "you're in " + app else "no app context"
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        input.setText("")
        addBubble(text, true)
        Memory.append(this, "user", text)

        sendButton.isEnabled = false
        OverlayService.setMood(PetView.Mood.THINKING)
        val placeholder = addBubble("…", false)

        AssistantBrain.reply(this, OverlayService.currentAppLabel) { ok, reply ->
            runOnUiThread {
                messageList.removeView(placeholder)
                addBubble(reply, false)
                if (ok) Memory.append(this, "assistant", reply)
                sendButton.isEnabled = true
                OverlayService.flashMood(PetView.Mood.HAPPY, 2600L)
            }
        }
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
