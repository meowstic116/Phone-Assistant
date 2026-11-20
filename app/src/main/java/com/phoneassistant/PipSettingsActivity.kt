package com.phoneassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.phoneassistant.PipSettings.Option

class PipSettingsActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var bootHint: TextView
    private lateinit var accessHint: TextView
    private val toySwitches = mutableListOf<Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(content)
        })

        text("Pip settings", 30f, R.color.text)
        heading("Movement & startup")
        option("Wander around the screen", Option.WANDER)
        text("Pip can still move to clear his chat box or keyboard, or to use a toy.")
        toggle(getString(R.string.start_at_boot), PipStartup.enabled(this)) {
            PipStartup.setEnabled(this, it)
            refreshHints()
        }
        bootHint = text("")

        heading("Phone access")
        option("Let Pip use accessibility", Option.ACCESSIBILITY)
        accessHint = text("")
        button("Open Android accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        heading("Toys")
        option("Allow toys", Option.TOYS)
        toySwitches += option("Bring out toys on his own", Option.SPONTANEOUS)
        text("Turn this off to keep enabled toys available only when you ask.")
        for (toy in Toy.values()) {
            toySwitches += toggle(toy.label, PipSettings.toySelected(this, toy)) {
                PipSettings.setToy(this, toy, it)
                OverlayService.behaviorSettingsChanged()
            }
        }
        button("Painting gallery") { startActivity(Intent(this, PaintingGalleryActivity::class.java)) }
        button("Done") { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshHints()
    }

    private fun refreshHints() {
        bootHint.setText(if (PipStartup.enabled(this) && !Settings.canDrawOverlays(this))
            R.string.start_at_boot_needs_overlay else R.string.start_at_boot_hint)
        accessHint.text = when {
            !PipSettings.enabled(this, Option.ACCESSIBILITY) ->
                "Pip won't read other apps or use accessibility to keep clear of their keyboards. Android's separate permission is unchanged."
            !ImeWatch.enabled(this) -> "Also turn on Phone Assistant in Android accessibility settings to use this."
            else -> "Lets Pip work other apps when you ask and stay clear of their keyboards."
        }
        val toysAllowed = PipSettings.enabled(this, Option.TOYS)
        toySwitches.forEach { it.isEnabled = toysAllowed }
    }

    private fun option(label: String, option: Option): Switch = toggle(label, PipSettings.enabled(this, option)) {
        PipSettings.set(this, option, it)
        if (option == Option.ACCESSIBILITY) {
            if (!it) Agent.cancel()
            ImeWatch.probe()
        }
        OverlayService.behaviorSettingsChanged()
        refreshHints()
    }

    private fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label
        setTextColor(getColor(R.color.text))
        minHeight = dp(48)
        isChecked = checked
        setOnCheckedChangeListener { _, enabled -> change(enabled) }
        content.addView(this, -1, -2)
    }

    private fun heading(label: String) = text(label, 18f, R.color.text).apply { setPadding(0, dp(20), 0, 0) }

    private fun text(label: String, size: Float = 14f, color: Int = R.color.textDim): TextView = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(getColor(color))
        content.addView(this, -1, -2)
    }

    private fun button(label: String, click: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { _: View -> click() }
        }, -1, -2)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
