package com.phoneassistant

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shares are untrusted input. Show them locally and require a tap before speaking. */
class ReadMessageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!PipMediaAccess.canShowMessage(this)) { finish(); return }
        val shared = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
        } else null
        // Don't retain or reprocess a shared message on rotation or process recreation.
        intent = Intent()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val explanation = TextView(this).apply {
            text = "Read a message using your phone’s offline voice. Nothing is sent to the API or saved to chat history."
        }
        val status = TextView(this)
        fun read(value: CharSequence?) {
            if (!PipMediaAccess.canShowMessage(this)) return
            val text = value?.toString()?.takeIf { it.isNotBlank() }
            if (text == null) { status.text = "No message text was provided."; return }
            status.text = "Reading with Android’s offline voice…"
            PipVoice.readPrivate(this, text, { status.text = it }) {
                if (status.text == "Reading with Android’s offline voice…") status.text = "Read again or close this screen."
            }
        }
        rows.addView(explanation)
        if (!shared.isNullOrBlank()) rows.addView(Button(this).apply {
            text = "Read shared text aloud"
            setOnClickListener { read(shared) }
        })
        rows.addView(Button(this).apply {
            text = "Read copied text aloud"
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                read(if (clip?.itemCount == 1) clip.getItemAt(0).text else null)
            }
        })
        rows.addView(status)
        setContentView(ScrollView(this).apply { addView(rows) })
    }

    override fun onStop() {
        PipVoice.stop()
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}
