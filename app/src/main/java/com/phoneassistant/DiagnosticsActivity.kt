package com.phoneassistant

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
 * The last phone job is here for the same reason, step by step. A job that fails halfway says
 * one dry sentence in a bubble and stops, and from outside that is indistinguishable from a
 * job that did nothing at all; the record is where "the tap did not land" can be read.
 *
 * Nothing here is remembered between runs, and nothing is sent anywhere.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var readout: TextView
    private lateinit var floatButton: Button

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

        // The panel has to be asked for from somewhere, and this is the screen about the
        // numbers it shows. It lives in the overlay, so it outlives this activity.
        floatButton = Button(this).apply {
            text = floatLabel()
            setPadding(0, dp(6f), 0, dp(12f))
            setOnClickListener {
                if (!OverlayService.running) {
                    // One of his windows: with no overlay running there is nothing to hang
                    // it on, and saying so beats a button that silently does nothing.
                    Toast.makeText(
                        this@DiagnosticsActivity,
                        "Start Pip first — the panel lives in his overlay.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                if (OverlayService.diagnosticsHudShowing()) {
                    OverlayService.hideDiagnosticsHud()
                } else {
                    OverlayService.showDiagnosticsHud()
                }
                text = floatLabel()
            }
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
                floatButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
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
        // Came back from a trip to another app: he may have been stopped, and the panel with
        // him, while this screen was hidden.
        if (::floatButton.isInitialized) floatButton.text = floatLabel()
        readout.post(ticker)
    }

    private fun floatLabel(): String =
        if (OverlayService.diagnosticsHudShowing()) "Hide floating panel"
        else "Float over other apps"

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
            if (watching) "on" else "off — no other app's keyboard, and no hands"
        )
        // Bound is a different fact from switched on: a service that has been killed reads
        // as off here even though Settings says otherwise, and that is worth seeing.
        line(
            "phone hands",
            if (Screen.available()) "connected" else "not connected — toggle it in Settings"
        )
        val ime = ImeWatch.heightPx
        line("IME reported", "${ime}px · ${asDp(ime, density)}dp")
        line(
            "last non-zero",
            if (ImeWatch.lastSeenPx == 0) "none seen yet" else
                "${ImeWatch.lastSeenPx}px · ${asDp(ImeWatch.lastSeenPx, density)}dp · " +
                    Diag.ago(ImeWatch.lastSeenAt)
        )

        if (status == null) {
            line("keyboardUp", "— Pip is not running")
        } else {
            line("keyboardUp", if (status.keyboardUp) "YES" else "no")
            // What the placement is worked out from, beside what the service is saying right
            // now. They are two readings of one thing, and a line that shows only one of them
            // is how a stale height went unnoticed in the first place.
            line(
                "height used",
                if (status.keyboardUsedPx <= 0) "none measured"
                else "${status.keyboardUsedPx}px · service reports ${status.imeReportedPx}px"
            )
            line(
                "keyboard top",
                if (status.keyboardUsedPx <= 0) "unknown — ${status.ceilingPx}px is a guess"
                else "${status.keyboardTopPx}px"
            )
            // His windows are placed from under the status bar, the keyboard is measured from
            // the top of the screen. This is that difference, measured rather than assumed.
            line("window offset", "${status.windowOffsetPx}px")
            line("remembered height", "${status.rememberedPx}px")
            line(
                "his ceiling",
                if (status.keyboardUsedPx > 0) "${status.ceilingPx}px"
                // Before a keyboard has ever been measured this is an invented number, and
                // saying so is the difference between a reading and a guess.
                else "${status.ceilingPx}px — guess, never measured"
            )
            // The box's own edges in the display's coordinates, against that top: the height
            // can be right and the placement wrong, and this line tells the two apart.
            line(
                "box (screen)",
                when {
                    status.boxTopPx < 0 -> "not open"
                    status.keyboardUsedPx > 0 && status.boxBottomPx > status.keyboardTopPx ->
                        "${status.boxTopPx}–${status.boxBottomPx}px — UNDER the keyboard"
                    else -> "${status.boxTopPx}–${status.boxBottomPx}px"
                }
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

        section("DRAWING — the character's own window")
        if (status == null) {
            line("drawing", "— Pip is not running")
        } else {
            line(
                "drawing",
                when {
                    !status.drawing -> "paused — the screen is off, so nothing is drawn"
                    // No measurement yet: the first second of a service, or of a fresh start.
                    // Saying "0 a second" here would read as stopped, which is the opposite.
                    status.drawsPerSecond <= 0 -> "yes — counting the first second"
                    else -> "yes — about ${status.drawsPerSecond} frames a second"
                }
            )
            // The rate he aims for, so the measured number has something to be checked
            // against rather than being taken on trust. Read from the same gaps the view
            // actually keeps, so this line cannot describe a rate the code does not use.
            line("target", PetView.ratesText())
        }

        section("ON STAGE")
        if (status == null) {
            line("props", "— Pip is not running")
        } else if (status.props.isEmpty()) {
            line("props", "nothing")
        } else {
            status.props.distinct().forEach { name -> line("prop", name) }
        }

        section("LAST PHONE JOB")
        val job = Diag.lastJob
        if (job == null) {
            line("job", "none yet — ask Pip to do something")
        } else {
            line(if (job.running) "running" else "asked", "\"${job.request}\"")
            val took = if (job.endedAt > 0L) (job.endedAt - job.startedAt) / 1000 else 0
            line(
                "state",
                if (job.running) {
                    "started ${Diag.ago(job.startedAt)} · ${job.steps.size} steps so far"
                } else {
                    "${Diag.ago(job.startedAt)} · ${job.steps.size} steps · ${took}s"
                }
            )
            line("why it stopped", job.reason ?: "still working — no ending yet")
            if (job.steps.isEmpty()) {
                detail("no step was taken")
            } else {
                job.steps.forEachIndexed { index, step ->
                    out.append("  ").append(index + 1).append(". ")
                        .append(
                            when {
                                step.skipped -> "SKIP"
                                step.ok -> "ok"
                                else -> "NO"
                            }
                        ).append("  ")
                        .append(step.text)
                    if (step.outcome.isNotBlank()) out.append(" → ").append(step.outcome)
                    out.append('\n')
                }
                // Honest about what ok means: the framework accepting an action is not the
                // same as the screen having changed, and a job can look healthy here and
                // still have gone nowhere.
                detail("ok = Android accepted it; the screen was read again after every step")
                if (job.steps.any { it.skipped }) {
                    detail("SKIP = nothing was pressed: his own window would not get out of " +
                        "the way in time")
                }
            }
        }

        section("BRAIN")
        val failure = Diag.lastApiError
        if (failure == null) {
            line("last API error", "none since the app started")
        } else {
            val halves = failure.text.split(" · ", limit = 2)
            line("last API error", "${halves[0]} · ${Diag.ago(failure.at)}")
            halves.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { detail(it) }
        }
        line("last good reply", Diag.ago(Diag.lastApiOkAt))

        section("LAST SWALLOWED EXCEPTION")
        val caught = Diag.lastCaught
        if (caught == null) {
            line("exception", "none — nothing has been hidden")
        } else {
            val times = if (caught.count > 1) " · ×${caught.count}" else ""
            line("exception", Diag.ago(caught.at) + times)
            detail(caught.text)
        }

        return out.toString()
    }

    private fun asDp(px: Int, density: Float): Int =
        if (density <= 0f) px else (px / density).toInt()

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 500L
        const val LABEL_WIDTH = 20
    }
}
