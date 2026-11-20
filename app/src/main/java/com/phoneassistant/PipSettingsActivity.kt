package com.phoneassistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.phoneassistant.PipSettings.Option

class PipSettingsActivity : Activity() {
    companion object {
        internal const val EXTRA_SHOW_TOYS = "com.phoneassistant.settings.SHOW_TOYS"
    }

    private lateinit var content: LinearLayout
    private lateinit var bootHint: TextView
    private lateinit var accessHint: TextView
    private var refreshApiIfChanged: () -> Unit = {}
    private val permissionStatuses = mutableListOf<() -> Unit>()
    private val toySwitches = mutableListOf<Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply {
            id = R.id.pipSettingsScroll
            setBackgroundColor(getColor(R.color.bg))
            addView(content)
        }
        setContentView(scroll)
        content.requestFocus()

        text("Pip settings", 30f, R.color.text)
        addPermissions()
        heading(getString(R.string.settings_personal_heading))
        button(getString(R.string.settings_personality)) { startActivity(Intent(this, PersonaActivity::class.java)) }
        button(getString(R.string.settings_memory)) { startActivity(Intent(this, MemoryActivity::class.java)) }
        button(getString(R.string.settings_logins)) { startActivity(Intent(this, VaultActivity::class.java)) }
        addApiSettings()

        heading("Movement & startup")
        option("Wander around the screen", Option.WANDER)
        text("Pip can still move to clear his chat box or keyboard, or to use a toy.")
        toggle(getString(R.string.start_at_boot), PipStartup.enabled(this)) {
            PipStartup.setEnabled(this, it)
            refreshHints()
        }
        bootHint = text("")

        val toyHeading = heading("Toys")
        option("Allow toys", Option.TOYS)
        toySwitches += option("Bring out toys on his own", Option.SPONTANEOUS)
        text("Turn this off to keep enabled toys available only when you ask.")
        for (toy in Toy.values()) {
            toySwitches += toggle(toy.label, PipSettings.toySelected(this, toy)) {
                PipSettings.setToy(this, toy, it)
                OverlayService.behaviorSettingsChanged()
            }
        }
        text("Pip can pick Doomscroll on his own only when your Home screen is clear. Turn off “Bring out toys on his own” to make it manual-only.")
        button("Painting gallery") { startActivity(Intent(this, PaintingGalleryActivity::class.java)) }
        button(getString(R.string.debug_options)) { startActivity(Intent(this, DebugOptionsActivity::class.java)) }
        button("Done") { finish() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHOW_TOYS, false)) {
            scroll.post { scroll.scrollTo(0, toyHeading.top) }
        }
    }

    private fun addPermissions() {
        heading(getString(R.string.permissions_heading))
        permission(R.string.permissions_overlay, { Settings.canDrawOverlays(this) }, R.string.permissions_overlay_info) {
            PipPermissions.openOverlay(this)
        }
        option(getString(R.string.permissions_access_switch), Option.ACCESSIBILITY)
        accessHint = text("")
        permission(R.string.permissions_access_android, { ImeWatch.enabled(this) }) {
            PipPermissions.openAccessibility(this)
        }
        permission(R.string.permissions_usage, { AppWatcher.hasPermission(this) }, R.string.permissions_usage_info) {
            PipPermissions.openUsage(this)
        }
        permission(R.string.permissions_notifications, { PipPermissions.notificationsEnabled(this) },
            R.string.permissions_notifications_info, R.string.permissions_notifications_manage) {
            PipPermissions.openNotifications(this)
        }
    }

    private fun permission(title: Int, allowed: () -> Boolean, detail: Int? = null,
                           action: Int = R.string.permissions_manage, click: () -> Unit) {
        val label = text("", 15f, R.color.text).apply { setPadding(0, dp(10), 0, dp(3)) }
        permissionStatuses += {
            label.text = getString(R.string.permissions_status, getString(title),
                getString(if (allowed()) R.string.permissions_on else R.string.permissions_off))
        }
        if (detail != null) text(getString(detail))
        button(getString(action), click).contentDescription =
            getString(R.string.permissions_action_description, getString(action), getString(title))
    }

    private fun addApiSettings() {
        heading(getString(R.string.settings_api_heading))
        val api = layoutInflater.inflate(R.layout.settings_api, content, false)
        content.addView(api)
        val baseUrl = api.findViewById<EditText>(R.id.baseUrlField)
        val key = api.findViewById<EditText>(R.id.apiKeyField)
        val model = api.findViewById<EditText>(R.id.modelField)
        fun display(config: AssistantBrain.Config) {
            baseUrl.setText(config.baseUrl)
            key.setText(config.apiKey)
            model.setText(config.model)
        }
        var loaded = AssistantBrain.config(this)
        display(loaded)
        refreshApiIfChanged = {
            val current = AssistantBrain.config(this)
            if (current != loaded) {
                loaded = current
                display(current)
            }
        }

        api.findViewById<Button>(R.id.keyLink).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_api_browser_missing, Toast.LENGTH_SHORT).show()
            }
        }
        api.findViewById<Button>(R.id.saveApiButton).setOnClickListener {
            AssistantBrain.save(this, baseUrl.text.toString().trim(), key.text.toString().trim(), model.text.toString().trim())
            loaded = AssistantBrain.config(this)
            display(loaded)
            Toast.makeText(this, R.string.settings_api_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshApiIfChanged()
        refreshHints()
    }

    private fun refreshHints() {
        permissionStatuses.forEach { it() }
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

    private fun heading(label: String) = text(label, 18f, R.color.text).apply {
        setPadding(0, dp(20), 0, dp(4))
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun text(label: String, size: Float = 14f, color: Int = R.color.textDim): TextView = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(getColor(color))
        content.addView(this, -1, -2)
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = getColorStateList(R.color.surfaceAlt)
        setTextColor(getColor(R.color.text))
        setOnClickListener { click() }
        content.addView(this, -1, -2)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
