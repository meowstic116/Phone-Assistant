package com.phoneassistant

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The way out of a phone job: a small chip with one button, for as long as Pip's hands are
 * busy.
 *
 * It is a window of the overlay rather than part of the bubble, because the app being worked
 * on is the whole point of a job — a button on Pip's own app would be no use from inside
 * Messages. It says only what is true (he is working) and offers only what is useful (stop).
 *
 * Nothing inside it is a separate touch target: the whole chip is one, so the service can tell
 * a tap (stop) from a drag (put it somewhere the job is not pressing) without half of a 32dp
 * pill behaving differently from the other half.
 */
class JobBar(ctx: Context, onStop: () -> Unit) : LinearLayout(ctx) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13f), dp(6f), dp(5f), dp(6f))
        setBackgroundResource(R.drawable.overlay_panel)
        // Clickable for accessibility only: a touch is handled by the service, which needs the
        // same gesture to be a drag when the finger moves. TalkBack activates the chip through
        // this listener rather than through a gesture.
        isClickable = true
        contentDescription = "Pip is working. Tap to stop, drag to move."
        setOnClickListener { onStop() }

        val label = TextView(ctx).apply {
            text = "Pip is working"
            textSize = 12f
            setTextColor(0xFF98A2B6.toInt())
        }

        val stop = TextView(ctx).apply {
            text = "Stop"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFB4AB.toInt())
            setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
            setBackgroundResource(R.drawable.stop_chip)
        }

        addView(label)
        addView(
            stop,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(9f)
            }
        )
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
