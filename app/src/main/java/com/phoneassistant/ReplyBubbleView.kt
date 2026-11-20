package com.phoneassistant

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** Body and waiting indicator share one window and one fixed layout through the whole reply. */
internal class ReplyBubbleView(context: Context, private val reply: String, widthLimit: Int) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val body = label(reply).apply { maxWidth = widthLimit }
    private val waiting = label("…").apply { visibility = View.GONE }

    init {
        setBackgroundResource(R.drawable.overlay_panel)
        addView(body, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        addView(waiting, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    private fun label(value: String) = TextView(context).apply {
        text = value
        textSize = 14f
        setTextColor(0xFFE8ECF4.toInt())
        setLineSpacing(2 * density, 1f)
        setPadding(dp(12), dp(9), dp(12), dp(9))
    }

    fun reveal(end: Int) {
        waiting.visibility = if (end == 0 && reply.isNotEmpty()) View.VISIBLE else View.GONE
        body.text = if (end >= reply.length) reply else SpannableString(reply).apply {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), end.coerceAtLeast(0), length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
