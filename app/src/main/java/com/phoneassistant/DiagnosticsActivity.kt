package com.phoneassistant

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What Pip actually sees, live, twice a second.
 *
 * It exists because some of this app cannot be checked by reading its code. How tall the
 * keyboard is, and whether the accessibility service is told about it at all, is a fact only
 * a real phone can supply — a Galaxy may or may not report its keyboard as an input-method
 * window, and the overlay's whole sideways step depends on the answer. The rest of the screen
 * exists for the same reason: a swallowed exception that keeps the overlay alive is invisible
 * everywhere else.
 *
 * Nothing here is remembered between runs, and nothing is sent anywhere.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var readout: TextView

    /** Keeps the numbers live while the screen is open, and stops when it is not. */
    private val ticker = object : Runnable {
        override fun run() {
            readout.text = report()
            readout.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readout = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(getColor(R.color.text))
            // Selectable so a value can be copied out rather than read aloud and mistyped.
            setTextIsSelectable(true)
            setLineSpacing(dp(3f).toFloat(), 1f)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "Diagnostics"
                textSize = 26f
                setTextColor(getColor(R.color.text))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "Live, twice a second. These are the numbers Android gives the app, " +
                    "not what it should give — that is the point of looking."
                textSize = 13f
                setTextColor(getColor(R.color.textDim))
                setPadding(0, dp(6f), 0, dp(18f))
            })
            addView(
                readout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        readout.post(ticker)
    }

    override fun onPause() {
        readout.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ the report

    private fun report(): String {
        val status = OverlayService.status()
        val density = resources.displayMetrics.density
        val out = StringBuilder()

        fun section(title: String) {
            if (out.isNotEmpty()) out.append('\n')
            out.append(title).append('\n')
        }

        fun line(label: String, value: String) {
            out.append("  ").append(label.padEnd(LABEL_WIDTH)).append(value).append('\n')
        }

        /** A long value on its own indented line, for the two things that have details. */
        fun detail(text: String) {
            out.append("    ").append(text).append('\n')
        }

        section("KEYBOARD — what Android reports")

        val watching = ImeWatch.enabled(this)
        line(
            "accessibility",
            if (watching) "on" else "off — another app's keyboard is invisible to him"
        )
        val ime = ImeWatch.heightPx
        line("IME reported", "${ime}px · ${asDp(ime, density)}dp")
        line(
            "last non-zero",
            if (ImeWatch.lastSeenPx == 0) "none seen yet" else
                "${ImeWatch.lastSeenPx}px · ${asDp(ImeWatch.lastSeenPx, density)}dp · " +
                    ago(ImeWatch.lastSeenAt)
        )

        if (status == null) {
            line("keyboardUp", "— Pip is not running")
        } else {
            line("keyboardUp", if (status.keyboardUp) "YES" else "no")
            line("remembered height", "${status.rememberedPx}px")
            line(
                "his ceiling",
                if (status.rememberedPx > 0) "${status.ceilingPx}px"
                // Before a keyboard has ever been measured this is an invented number, and
                // saying so is the difference between a reading and a guess.
                else "${status.ceilingPx}px — guess, never measured"
            )
            line("Pip y / height", "${status.petY} / ${status.petHeightPx}px")
            val centre = status.petY + status.petHeightPx / 2
            line("Pip centre y", "${centre}px")
            line(
                "clear of keyboard",
                when {
                    !status.keyboardUp -> "no keyboard up"
                    centre > status.ceilingPx -> "NO — ${centre - status.ceilingPx}px under it"
                    else -> "yes, ${status.ceilingPx - centre}px clear"
                }
            )
            line(
                "screen / inset",
                "${status.screenWidthPx}×${status.screenHeightPx}px · bottom ${status.bottomInsetPx}px"
            )
        }

        section("ON STAGE")
        if (status == null) {
            line("props", "— Pip is not running")
        } else if (status.props.isEmpty()) {
            line("props", "nothing")
        } else {
            status.props.distinct().forEach { name -> line("prop", name) }
        }

        section("BRAIN")
        val failure = Diag.lastApiError
        if (failure == null) {
            line("last API error", "none since the app started")
        } else {
            val halves = failure.text.split(" · ", limit = 2)
            line("last API error", "${halves[0]} · ${ago(failure.at)}")
            halves.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { detail(it) }
        }
        line("last good reply", ago(Diag.lastApiOkAt))

        section("LAST SWALLOWED EXCEPTION")
        val caught = Diag.lastCaught
        if (caught == null) {
            line("exception", "none — nothing has been hidden")
        } else {
            val times = if (caught.count > 1) " · ×${caught.count}" else ""
            line("exception", ago(caught.at) + times)
            detail(caught.text)
        }

        return out.toString()
    }

    /** "just now", "8s ago", "3m ago" — enough to tell a live fault from an old one. */
    private fun ago(at: Long): String {
        if (at <= 0L) return "never"
        val ms = SystemClock.elapsedRealtime() - at
        return when {
            ms < 1_500L -> "just now"
            ms < 60_000L -> "${ms / 1000}s ago"
            ms < 3_600_000L -> "${ms / 60_000}m ago"
            else -> "${ms / 3_600_000}h ago"
        }
    }

    private fun asDp(px: Int, density: Float): Int =
        if (density <= 0f) px else (px / density).toInt()

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 500L
        const val LABEL_WIDTH = 20
    }
}
