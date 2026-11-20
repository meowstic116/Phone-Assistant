package com.phoneassistant

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/** Invisible words still occupy their final space, so revealing them does not resize the bubble. */
internal class ReplyBubbleView(context: Context, private val reply: String, widthLimit: Int) : TextView(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    init {
        textSize = 14f
        setTextColor(0xFFE8ECF4.toInt())
        setLineSpacing(2 * density, 1f)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        maxWidth = widthLimit
        setBackgroundResource(R.drawable.overlay_panel)
        text = reply
    }

    fun reveal(end: Int) {
        text = if (end >= reply.length) reply else SpannableString(reply).apply {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), end.coerceAtLeast(0), length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
