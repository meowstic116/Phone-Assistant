package com.phoneassistant

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The diagnostics readout in miniature, floating over whatever app the user is in.
 *
 * The Diagnostics screen can only be read while that screen is in front, and the keyboard it
 * is describing closes the moment you leave the app that owns it. This panel is one of the
 * overlay's windows instead, so the numbers stay live while you type somewhere else — which
 * is the only way to actually watch the keyboard work happen.
 *
 * It shows the few facts that decide whether he behaves: how tall Android says the keyboard
 * is, whether it says one is up at all, and where his middle is against the top of it. The
 * rest of the report stays on the Diagnostics screen.
 */
class DiagHud(ctx: Context) : LinearLayout(ctx) {

    /** The × was tapped. Closing is the service's job, since it owns the window. */
    var onClose: (() -> Unit)? = null

    private val body: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(12f), dp(9f), dp(12f), dp(11f))
        setBackgroundResource(R.drawable.overlay_panel)

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(ctx).apply {
                text = "DIAGNOSTICS"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(DIM)
                letterSpacing = 0.08f
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            // The only part of the panel that is a button, and padded out to a thumb's size.
            TextView(ctx).apply {
                text = "×"
                textSize = 17f
                setTextColor(DIM)
                setPadding(dp(10f), 0, dp(2f), dp(2f))
                setOnClickListener { onClose?.invoke() }
            }
        )

        body = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(TEXT)
            setLineSpacing(dp(1f).toFloat(), 1f)
            setPadding(0, dp(5f), 0, 0)
        }

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Repaints from a fresh reading. [status] is null only in the instant the service is
     * being torn down, but the panel says so rather than showing stale numbers.
     */
    fun render(
        status: OverlayService.Status?,
        accessibility: Boolean,
        lastSeenPx: Int,
        lastSeenAt: Long
    ) {
        val out = StringBuilder()

        fun line(label: String, value: String) {
            out.append(label.padEnd(LABEL)).append(value).append('\n')
        }

        // First line, because everything under it means nothing without this one.
        line("access", if (accessibility) "on" else "OFF")

        if (status == null) {
            out.append("Pip is not running")
            body.text = out.toString()
            return
        }

        line("IME", "${status.imeReportedPx}px · ${asDp(status.imeReportedPx)}dp")
        line(
            "last",
            if (lastSeenPx <= 0) "none yet" else "${lastSeenPx}px · ${Diag.ago(lastSeenAt)}"
        )
        line("up", if (status.keyboardUp) "YES" else "no")
        line("pip y", "${status.petY}px")
        // His middle against the keyboard's top is the comparison the overlay actually makes,
        // so it is the one shown — not where his feet are.
        val centre = status.petY + status.petHeightPx / 2
        line("centre", "${centre}px")
        line(
            "clear",
            when {
                !status.keyboardUp -> "no keyboard"
                centre > status.ceilingPx -> "NO · ${centre - status.ceilingPx}px under"
                else -> "yes · ${status.ceilingPx - centre}px"
            }
        )

        body.text = out.toString().trimEnd()
    }

    private fun asDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return if (density <= 0f) px else (px / density).toInt()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LABEL = 7
        const val TEXT = 0xFFE8ECF4.toInt()
        const val DIM = 0xFF98A2B6.toInt()
    }
}
