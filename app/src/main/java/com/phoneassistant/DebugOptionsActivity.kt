package com.phoneassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Manual troubleshooting tools, kept off the app's main screen. */
class DebugOptionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(options)
        })
        options.addView(TextView(this).apply {
            setText(R.string.debug_options)
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, row())

        addOption(options, R.string.debug_diagnostics, R.string.debug_diagnostics_hint) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        addOption(options, R.string.debug_setup, R.string.debug_setup_hint) {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REPLAY, true))
        }
        options.addView(button(R.string.debug_back) { finish() }, row(20))
    }

    private fun addOption(parent: LinearLayout, title: Int, hint: Int, launch: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundResource(R.drawable.settings_card)
            addView(button(title, launch), row())
            addView(TextView(this@DebugOptionsActivity).apply {
                setText(hint)
                textSize = 14f
                setTextColor(getColor(R.color.textDim))
            }, row(6))
        }
        parent.addView(card, row(14))
    }

    private fun button(label: Int, click: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = getColorStateList(R.color.surfaceAlt)
        setTextColor(getColor(R.color.text))
        setOnClickListener { click() }
    }

    private fun row(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
