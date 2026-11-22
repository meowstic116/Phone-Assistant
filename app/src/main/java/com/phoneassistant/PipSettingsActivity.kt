package com.phoneassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.phoneassistant.SetupPolicy.Permission

class PipSettingsActivity : Activity() {
    companion object {
        internal const val EXTRA_SHOW_TOYS = "com.phoneassistant.settings.SHOW_TOYS"
    }

    private lateinit var content: LinearLayout
    private lateinit var bootHint: TextView
    private lateinit var openAtBoot: Switch
    private lateinit var accessHint: TextView
    private lateinit var voiceSummary: TextView
    private lateinit var kokoroStatus: TextView
    private lateinit var kokoroAction: Button
    private lateinit var wakeAction: Button
    private lateinit var wakeStatus: TextView
    private lateinit var wakeSpeak: Button
    private val kokoroChanged: () -> Unit = { refreshVoiceSummary() }
    private val wakeModelChanged: () -> Unit = { refreshWakeAction() }
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
        openAtBoot = toggle("Open the Pip app after restart", PipStartup.openAppAtBoot(this)) {
            PipStartup.setOpenAppAtBoot(this, it)
        }
        text("Opens Pip’s main screen once after your first unlock. Requires Start Pip when the phone starts. Some phones block apps from opening automatically.")

        heading("Phone access")
        option(getString(R.string.permissions_access_switch), Option.ACCESSIBILITY)
        accessHint = text("")

        heading("Memory")
        option("Learn from chat", Option.LEARN_FROM_CHAT)
        text("Let Pip save useful details from your conversations. Turning this off leaves existing notes and direct requests to remember something unchanged.")

        heading("Voice")
        toggle("Read Pip’s replies aloud", VoiceSettings.config(this).enabled) { VoiceSettings.setEnabled(this, it) }
        voiceSummary = text("")
        text("Both voice options speak offline. If a voice can’t play, Pip shows text without switching voices.")
        button("Choose Pip’s voice") { configureVoice() }
        kokoroStatus = text("")
        kokoroAction = button("Download Kokoro") {
            if (KokoroVoice.hasDownload(this)) {
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

        button("Preview reply bubble") {
            if (!OverlayService.previewBubble()) Toast.makeText(this,
                "Start Pip and let him finish any current reply, then try again.", Toast.LENGTH_LONG).show()
        }

        heading("Talk to Pip")
        text("Speak in English and address him by name, like ‘Hey Pip, tell me a joke’ or ‘Good one, Pip.’ Listening runs on your phone, even after locking. It can use battery. To stop, tap Stop listening for Pip here or stop the assistant. Notifications are optional. Audio is not saved or sent; recognized chat uses your chat API. Phone actions require unlocking and tapping Send. Turn on Read Pip’s replies aloud to hear answers while locked.")
        toggle("Start listening when Pip starts", PipStartup.listenOnStart(this)) { enabled ->
            PipStartup.setListenOnStart(this, enabled)
            if (enabled) WakeListenService.startProblem(this)?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }.isEnabled = KokoroVoice.supported()
        text("Starts the microphone when you tap Start assistant. If Start Pip when the phone starts is also on, listening can resume after a reboot. On Android 11 or newer, Pip starts listening when his app screen opens after unlocking. Turn on Open the Pip app after restart to try opening it automatically. Requires the offline model and microphone permission.")
        wakeAction = button("") { changeWakeListening() }
        wakeStatus = text("")
        wakeSpeak = button("Speak now (no wake word)") {
            if (!WakeListenService.speakNow()) {
                Toast.makeText(this, "Wait until Pip is ready to listen.", Toast.LENGTH_SHORT).show()
            }
        }
        refreshWakeAction()

        heading("Hands-free driving")
        text("Set up and test while parked. With Android Auto connected, use ‘Pip, navigate to [destination]’ or ‘Pip, pause Spotify.’ Google Maps must be installed; Spotify needs an active local session and Android Notification access for Pip. Say ‘play Emo Route Mix’ for a named mix; this works only if Spotify exposes playlist search to Pip. Pip now checks Spotify’s playback state before saying a command worked. Enable spoken replies to hear the result. Other phone actions still need review when parked.")
        toggle("Allow hands-free driving commands", VoiceSettings.drivingEnabled(this)) {
            VoiceSettings.setDrivingEnabled(this, it)
        }
        toggle("Accept driving commands without saying “Pip”", VoiceSettings.carNoName(this)) {
            VoiceSettings.setCarNoName(this, it)
        }
        text("Only while a hands-free Bluetooth device is connected and driving commands are on. " +
            "Exact commands like “pause Spotify”, “next song”, “take me home”, or “stop navigation” " +
            "are accepted; chat and phone tasks still need his name.")
        toggle("Answer “are we still navigating?” without saying “Pip”", VoiceSettings.carQuestions(this)) {
            VoiceSettings.setCarQuestions(this, it)
        }
        text("A read-only check of Google Maps, so it changes nothing. It also works by name at any " +
            "time. Stopping navigation needs Notification access below, but never an unlock: Pip uses " +
            "Maps’ own Exit button on the navigation notification.")
        button("Fix navigation access for Pip") {
            if (PipMediaAccess.enabled(this)) {
                Toast.makeText(this, "Pip can already see Google Maps’ navigation notification.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Turn on Pip under Notification access, then try “Pip, stop navigation.”",
                    Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.onFailure {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
        }
        button("Set or clear driving home destination") {
            val destination = EditText(this).apply {
                hint = "Street address or destination"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
                filters = arrayOf(android.text.InputFilter.LengthFilter(180))
                setSingleLine(true)
                setText(VoiceSettings.drivingHome(this@PipSettingsActivity))
            }
            AlertDialog.Builder(this).setTitle("Driving home destination")
                .setMessage("Used for ‘Pip, navigate home.’ Saved on this phone and sent to Google Maps only when requested.")
                .setView(destination).setNegativeButton("Cancel", null)
                .setNeutralButton("Clear") { _, _ -> VoiceSettings.setDrivingHome(this, "") }
                .setPositiveButton("Save") { _, _ -> VoiceSettings.setDrivingHome(this, destination.text.toString()) }
                .show()
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

    private fun refreshWakeAction() {
        if (!::wakeAction.isInitialized) return
        val status = WakeListenService.feedback
        wakeStatus.text = status.message
        wakeSpeak.visibility = if (WakeListenService.running) View.VISIBLE else View.GONE
        wakeSpeak.isEnabled = status.canSpeak
        wakeAction.isEnabled = WakeListenService.running || (!WakeModel.busy && KokoroVoice.supported())
        wakeAction.text = when {
            WakeListenService.running -> "Stop listening for Pip"
            !KokoroVoice.supported() -> "Offline listening unavailable on this device"
            WakeModel.busy -> "Downloading offline recognition…"
            !WakeModel.ready(this) -> "Download offline recognition (57 MB)"
            else -> "Start listening for Pip"
        }
    }

    private fun changeWakeListening() {
        if (WakeListenService.running) { WakeListenService.stop(this); return }
        if (!WakeModel.ready(this)) {
            WakeModel.install(this) { success ->
                if (isFinishing || isDestroyed) return@install
                Toast.makeText(this, if (success) "Ready. Tap Start listening for Pip."
                    else "Couldn’t install offline recognition. Check your connection and free space.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!OverlayService.running || OverlayService.stopping) {
            Toast.makeText(this, "Start the assistant before turning on listening.", Toast.LENGTH_LONG).show()
            return
        }
        val bluetooth = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
                .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                .toTypedArray()
        }
        // The Bluetooth grant is optional: without it Pip simply keeps the phone mic, so
        // only the microphone itself may block starting a session.
        if (!WakeListenService.hasMicPermission(this)) {
            requestPermissions(bluetooth, 702)
            return
        }
        // Ask for the optional Bluetooth route without holding up listening. The listener
        // falls back to the phone mic when the grant is missing.
        if (Manifest.permission.BLUETOOTH_CONNECT in bluetooth) requestPermissions(bluetooth, 703)
        WakeListenService.startProblem(this)?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        if (!WakeListenService.start(this)) Toast.makeText(this, "Android couldn’t start listening. Try again with settings open.", Toast.LENGTH_LONG).show()
    }

    private fun refreshVoiceSummary() {
        val config = VoiceSettings.config(this)
        voiceSummary.text = when (config.source) {
            VoiceSource.KOKORO -> "${config.kokoro.label} · Kokoro offline"
            VoiceSource.ANDROID -> "Offline Android voice"
        }
        val showKokoro = config.source == VoiceSource.KOKORO || KokoroVoice.busy() || KokoroVoice.hasDownload(this)
        kokoroStatus.visibility = if (showKokoro) View.VISIBLE else View.GONE
        kokoroStatus.text = KokoroVoice.status(this)
        kokoroAction.visibility = if (showKokoro && KokoroVoice.supported()) View.VISIBLE else View.GONE
        kokoroAction.isEnabled = !KokoroVoice.busy()
        kokoroAction.text = if (KokoroVoice.hasDownload(this)) "Remove Kokoro download" else "Download Kokoro"
    }

    override fun onStart() {
        super.onStart()
        KokoroVoice.observe(kokoroChanged)
        WakeListenService.onChanged = ::refreshWakeAction
        WakeModel.observe(wakeModelChanged)
    }

    override fun onStop() {
        KokoroVoice.forget(kokoroChanged)
        WakeModel.forget(wakeModelChanged)
        WakeListenService.onChanged = null
        super.onStop()
    }

    private fun configureVoice() {
        val saved = VoiceSettings.config(this)
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
        val sources = VoiceSource.values()
        val presets = KokoroPreset.values()
        val source = chooser(sources.map { it.label }, saved.source.ordinal).apply { contentDescription = "Voice engine" }
        val preset = chooser(presets.map { it.label }, saved.kokoro.ordinal).apply { contentDescription = "Kokoro voice" }
        val help = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        listOf(source, help, preset).forEach { fields.addView(it) }
        fun refreshFields() {
            val selected = sources[source.selectedItemPosition.coerceAtLeast(0)]
            preset.visibility = if (selected == VoiceSource.KOKORO) View.VISIBLE else View.GONE
            help.text = when (selected) {
                VoiceSource.KOKORO -> "On-device English voices. Download Kokoro in Voice settings before previewing it."
                VoiceSource.ANDROID -> "Uses an installed offline voice. Change its sound in Android’s text-to-speech settings."
            }
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        source.onItemSelectedListener = listener
        refreshFields()
        val dialog = AlertDialog.Builder(this).setTitle("Choose Pip’s voice")
            .setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = sources[source.selectedItemPosition.coerceAtLeast(0)]
                VoiceSettings.save(this, saved.copy(source = selected,
                    kokoro = presets[preset.selectedItemPosition.coerceAtLeast(0)]))
                refreshVoiceSummary()
                dialog.dismiss()
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
        toggle("Toy sound effects", ToyAudio.enabled(this)) {
            ToyAudio.setEnabled(this, it)
            if (it) ToyAudio.play(this, ToySounds.Sound.POP, 0.9f)
        }
        text("Squeaks, pops, pours and whistles, made on the phone with no downloads. Every one is a " +
            "little different each time. Pip stays quiet while he is talking, and silent mode still " +
            "means silent.")
        button("Toy sound volume: ${(ToyAudio.volume(this) * 100).toInt()}%") {
            val values = arrayOf(30, 50, 70, 90, 100)
            AlertDialog.Builder(this).setTitle("Toy sound volume")
                .setItems(values.map { "$it%" }.toTypedArray()) { _, which ->
                    ToyAudio.setVolume(this, values[which] / 100f)
                    ToyAudio.play(this, ToySounds.Sound.SQUEAK, 0.9f)
                    recreate()
                }
                .setNegativeButton("Cancel", null).show()
        }
        button("Try a toy sound") {
            ToyAudio.play(this, ToySounds.Sound.SQUEAK, 1f)
            ToyAudio.playPair(this, ToySounds.Sound.POP, ToySounds.Sound.GOAL, 1f)
        }
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
        for (permission in Permission.entries) {
            val row = PipPermissions.row(permission)
            permission(row.title, { PipPermissions.granted(this, permission) }, row.description, row.manage) {
                PipPermissions.manage(this, permission)
            }
            if (permission == Permission.NOTIFICATION_ACCESS) {
                option(getString(R.string.permissions_message_previews), Option.MESSAGE_NOTIFICATIONS)
                text(getString(R.string.permissions_message_previews_info))
                button(getString(R.string.read_message_with_pip)) { startActivity(Intent(this, ReadMessageActivity::class.java)) }
            }
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
            val url = baseUrl.text.toString().trim()
            val apiKey = key.text.toString().trim()
            if (url.isNotBlank() && !SetupPolicy.validUrl(url)) {
                baseUrl.error = "Enter an HTTPS URL without credentials, query parameters, or a fragment."
                return@setOnClickListener
            }
            if (apiKey.isNotBlank() && !SetupPolicy.validApiKey(apiKey)) {
                key.error = "Enter an API key without spaces or control characters."
                return@setOnClickListener
            }
            AssistantBrain.save(this, url, apiKey, model.text.toString().trim())
            loaded = AssistantBrain.config(this)
            display(loaded)
            Toast.makeText(this, R.string.settings_api_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) OverlayService.onPipWindowFocused()
    }

    override fun onResume() {
        super.onResume()
        refreshApiIfChanged()
        refreshHints()
        refreshWakeAction()
    }

    private fun refreshHints() {
        permissionStatuses.forEach { it() }
        bootHint.setText(if (PipStartup.enabled(this) && !Settings.canDrawOverlays(this))
            R.string.start_at_boot_needs_overlay else R.string.start_at_boot_hint)
        openAtBoot.isEnabled = PipStartup.enabled(this)
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
