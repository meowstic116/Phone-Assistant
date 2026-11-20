package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var voiceSummary: TextView
    private lateinit var kokoroStatus: TextView
    private lateinit var kokoroAction: Button
    private val kokoroChanged: () -> Unit = { refreshVoiceSummary() }
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
        heading("Movement & startup")
        option("Wander around the screen", Option.WANDER)
        text("Pip can still move to clear his chat box or keyboard, or to use a toy.")
        toggle(getString(R.string.start_at_boot), PipStartup.enabled(this)) {
            PipStartup.setEnabled(this, it)
            refreshHints()
        }
        bootHint = text("")

        heading("Phone access")
        option(getString(R.string.permissions_access_switch), Option.ACCESSIBILITY)
        accessHint = text("")

        heading("Memory")
        option("Learn from chat", Option.LEARN_FROM_CHAT)
        text("Let Pip save useful details from your conversations. Turning this off leaves existing notes and direct requests to remember something unchanged.")

        heading("Voice")
        toggle("Read Pip’s replies aloud", PipVoice.config(this).enabled) { PipVoice.setEnabled(this, it) }
        voiceSummary = text("")
        text("Kokoro speaks offline. Only VoxCPM2 modes send ordinary reply text to a speech service; private phone results never go to a speech service. If a voice can’t play, Pip shows text without switching voices.")
        button("Choose Pip’s voice") { configureVoice() }
        kokoroStatus = text("")
        kokoroAction = button("Download Kokoro") {
            if (KokoroVoice.ready(this)) {
                AlertDialog.Builder(this).setTitle("Remove Kokoro?")
                    .setMessage("You can download it again later to hear Pip offline.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> KokoroVoice.remove(this) }.show()
            } else {
                AlertDialog.Builder(this).setTitle("Download Kokoro?")
                    .setMessage("Downloads about 110 MB from GitHub. Wi-Fi is recommended. Pip won’t need the internet for speech after setup.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Download") { _, _ -> KokoroVoice.install(this) }.show()
            }
        }
        refreshVoiceSummary()
        button("Preview Pip’s voice") {
            PipVoice.preview(this) { result ->
                if (!isFinishing && !isDestroyed) {
                    refreshVoiceSummary()
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        val toyHeading = addToySettings()
        addPersonalityAndData()
        addPermissions()
        addApiSettings()
        button(getString(R.string.debug_options)) { startActivity(Intent(this, DebugOptionsActivity::class.java)) }
        button("Done") { finish() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHOW_TOYS, false)) {
            scroll.post { scroll.scrollTo(0, toyHeading.top) }
        }
    }

    private fun refreshVoiceSummary() {
        val config = PipVoice.config(this)
        voiceSummary.text = when (config.source) {
            VoiceSource.KOKORO -> "${config.kokoro.label} · Kokoro offline"
            VoiceSource.WIRO -> "${config.style.label} · VoxCPM2 via Wiro" +
                if (config.wiroKey.isBlank()) " · API key needed" else ""
            VoiceSource.SERVER -> "${config.style.label} · Custom VoxCPM2 server"
            VoiceSource.ANDROID -> "Offline Android voice"
        }
        val showKokoro = config.source == VoiceSource.KOKORO || KokoroVoice.busy() || KokoroVoice.ready(this)
        kokoroStatus.visibility = if (showKokoro) View.VISIBLE else View.GONE
        kokoroStatus.text = KokoroVoice.status(this)
        kokoroAction.visibility = if (showKokoro && KokoroVoice.supported()) View.VISIBLE else View.GONE
        kokoroAction.isEnabled = !KokoroVoice.busy()
        kokoroAction.text = if (KokoroVoice.ready(this)) "Remove Kokoro download" else "Download Kokoro"
    }

    override fun onStart() {
        super.onStart()
        KokoroVoice.observe(kokoroChanged)
    }

    override fun onStop() {
        KokoroVoice.forget(kokoroChanged)
        super.onStop()
    }

    private fun configureVoice() {
        val saved = PipVoice.config(this)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(14))
        }
        fun chooser(values: List<String>, index: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@PipSettingsActivity, android.R.layout.simple_spinner_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(index)
            minimumHeight = dp(48)
        }
        fun field(label: String, value: String, type: Int) = EditText(this).apply {
            hint = label
            inputType = type
            setSingleLine(true)
            setText(value)
        }
        val sources = VoiceSource.values()
        val styles = PipVoiceStyle.values()
        val source = chooser(sources.map { it.label }, saved.source.ordinal).apply { contentDescription = "Voice service" }
        val style = chooser(styles.map { it.label }, saved.style.ordinal).apply { contentDescription = "Voice style" }
        val custom = field("Describe a voice (e.g. crisp, lightly robotic)", saved.custom, InputType.TYPE_CLASS_TEXT).apply {
            filters = arrayOf(InputFilter.LengthFilter(PipVoiceStyle.MAX_DESCRIPTION))
        }
        val presets = KokoroPreset.values()
        val preset = chooser(presets.map { it.label }, saved.kokoro.ordinal).apply { contentDescription = "Kokoro voice" }
        val secret = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val wiroKey = field("Wiro API key (not your chat API key)", saved.wiroKey, secret)
        val endpoint = field("https://your-server.example/v1/audio/speech", saved.endpoint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val token = field("Server token (if required)", saved.token, secret)
        val keyLink = Button(this).apply {
            text = "Get a Wiro API key"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wiro.ai/panel/project/new"))) }
                catch (_: ActivityNotFoundException) { Toast.makeText(this@PipSettingsActivity, "No browser available.", Toast.LENGTH_SHORT).show() }
            }
        }
        val help = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        listOf(source, help, preset, wiroKey, keyLink, endpoint, token, style, custom).forEach { fields.addView(it) }
        fun refreshFields() {
            val selected = sources[source.selectedItemPosition.coerceAtLeast(0)]
            fun visible(view: View, show: Boolean) { view.visibility = if (show) View.VISIBLE else View.GONE }
            visible(wiroKey, selected == VoiceSource.WIRO)
            visible(keyLink, selected == VoiceSource.WIRO)
            visible(endpoint, selected == VoiceSource.SERVER)
            visible(token, selected == VoiceSource.SERVER)
            val remote = selected == VoiceSource.WIRO || selected == VoiceSource.SERVER
            visible(preset, selected == VoiceSource.KOKORO)
            visible(style, remote)
            visible(custom, remote && style.selectedItemPosition == PipVoiceStyle.CUSTOM.ordinal)
            help.text = when (selected) {
                VoiceSource.KOKORO -> "On-device English voices. No speech API key or server. Download Kokoro in Voice settings before previewing it."
                VoiceSource.WIRO -> "No server setup. Enter a Wiro project key. Your Wiro account may be charged for speech, including previews."
                VoiceSource.SERVER -> "Connect to a VoxCPM2 server over HTTPS. The server must support style instructions to honor your selection."
                VoiceSource.ANDROID -> "Uses an installed offline voice. Change its sound in Android’s text-to-speech settings."
            }
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        source.onItemSelectedListener = listener
        style.onItemSelectedListener = listener
        refreshFields()
        val dialog = AlertDialog.Builder(this).setTitle("Choose Pip’s voice")
            .setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = sources[source.selectedItemPosition.coerceAtLeast(0)]
                val chosen = styles[style.selectedItemPosition.coerceAtLeast(0)]
                val key = wiroKey.text.toString().trim()
                val address = endpoint.text.toString().trim()
                val bearer = token.text.toString().trim()
                val description = PipVoiceStyle.clean(custom.text.toString())
                when {
                    selected == VoiceSource.WIRO && (key.isBlank() || key.any { it.isWhitespace() || it.isISOControl() }) ->
                        wiroKey.error = "Enter a Wiro project key without spaces."
                    selected == VoiceSource.SERVER && VoiceProtocol.endpoint(address) == null ->
                        endpoint.error = "Use an HTTPS URL ending in /v1/audio/speech."
                    selected == VoiceSource.SERVER && bearer.any { it.isWhitespace() || it.isISOControl() } ->
                        token.error = "Enter the server token without spaces."
                    (selected == VoiceSource.WIRO || selected == VoiceSource.SERVER) && chosen == PipVoiceStyle.CUSTOM && description.isBlank() ->
                        custom.error = "Describe how you want Pip to sound."
                    else -> {
                        PipVoice.save(this, saved.copy(source = selected, endpoint = address, token = bearer,
                            wiroKey = key, style = chosen, custom = description,
                            kokoro = presets[preset.selectedItemPosition.coerceAtLeast(0)]))
                        refreshVoiceSummary()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun addToySettings(): TextView {
        val toyHeading = heading("Toys")
        option("Allow toys", Option.TOYS)
        toySwitches += option("Bring out toys on his own", Option.SPONTANEOUS)
        text("Turn this off to keep enabled toys available only when you ask.")
        text("Pip can pick Doomscroll on his own only when your Home screen is clear.")
        for (toy in Toy.values()) {
            toySwitches += toggle(toy.label, PipSettings.toySelected(this, toy)) {
                PipSettings.setToy(this, toy, it)
                OverlayService.behaviorSettingsChanged()
            }
        }
        button("Painting gallery") { startActivity(Intent(this, PaintingGalleryActivity::class.java)) }
        return toyHeading
    }

    private fun addPersonalityAndData() {
        heading(getString(R.string.settings_personal_heading))
        button(getString(R.string.settings_personality)) { startActivity(Intent(this, PersonaActivity::class.java)) }
        button(getString(R.string.settings_memory)) { startActivity(Intent(this, MemoryActivity::class.java)) }
        button(getString(R.string.settings_logins)) { startActivity(Intent(this, VaultActivity::class.java)) }
    }

    private fun addPermissions() {
        heading(getString(R.string.permissions_heading))
        permission(R.string.permissions_overlay, { Settings.canDrawOverlays(this) }, R.string.permissions_overlay_info) {
            PipPermissions.openOverlay(this)
        }
        permission(R.string.permissions_access_android, { ImeWatch.enabled(this) }) {
            PipPermissions.openAccessibility(this)
        }
        permission(R.string.permissions_usage, { AppWatcher.hasPermission(this) }, R.string.permissions_usage_info) {
            PipPermissions.openUsage(this)
        }
        permission(R.string.permissions_now_playing, { PipMediaAccess.enabled(this) },
            R.string.permissions_now_playing_info, R.string.permissions_now_playing_manage) {
            PipPermissions.openMediaAccess(this)
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
            !ImeWatch.enabled(this) -> "Turn on Phone Assistant in Android accessibility settings under Permissions below."
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
