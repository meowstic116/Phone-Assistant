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
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
    private lateinit var openAtBoot: Switch
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

    /** Every explanation, in one place, so a pop-up cannot disagree with a switch. */
    private object Notes {
        val wander = "Pip can still move to clear his chat box or keyboard, or to use a toy."
        val openAtBoot = "Opens Pip’s main screen once after your first unlock. Requires Start Pip " +
            "when the phone starts. Some phones block apps from opening automatically."
        val accessibility = "Lets Pip work other apps when you ask and stay clear of their " +
            "keyboards. Turn this off and Pip won’t read other apps or use accessibility, though " +
            "Android’s separate permission is unchanged."
        val learnFromChat = "Let Pip save useful details from your conversations. Turning this off " +
            "leaves existing notes and direct requests to remember something unchanged."
        val readingAloud = "Both voice options speak offline. If a voice can’t play, Pip shows text " +
            "without switching voices."
        val listening = "Speak in English and address him by name, like ‘Hey Pip, tell me a joke’ " +
            "or ‘Good one, Pip.’ Listening runs on your phone, even after locking. It can use " +
            "battery. To stop, tap Stop listening for Pip here or stop the assistant. " +
            "Notifications are optional. Audio is not saved or sent; recognized chat uses your " +
            "chat API. Phone actions require unlocking and tapping Send. Turn on Read Pip’s " +
            "replies aloud to hear answers while locked."
        val listenOnStart = "Starts the microphone when you tap Start assistant. If Start Pip when " +
            "the phone starts is also on, listening can resume after a reboot. On Android 11 or " +
            "newer, Pip starts listening when his app screen opens after unlocking. Turn on Open " +
            "the Pip app after restart to try opening it automatically. Requires the offline " +
            "model and microphone permission."
        val driving = "Set up and test while parked. With Android Auto connected, use ‘Pip, " +
            "navigate to [destination]’ or ‘Pip, pause Spotify.’ Google Maps must be installed; " +
            "Spotify needs an active local session and Android Notification access for Pip. Say " +
            "‘play Emo Route Mix’ for a named mix; this works only if Spotify exposes playlist " +
            "search to Pip. Pip checks Spotify’s playback state before saying a command worked. " +
            "Enable spoken replies to hear the result. Other phone actions still need review when " +
            "parked."
        val carNoName = "Only while a hands-free Bluetooth device is connected and driving " +
            "commands are on. Exact commands like ‘pause Spotify’, ‘next song’, ‘take me home’, " +
            "or ‘stop navigation’ are accepted; chat and phone tasks still need his name."
        val carQuestions = "A read-only check of Google Maps, so it changes nothing. It also " +
            "works by name at any time. Stopping navigation needs Notification access below, but " +
            "never an unlock: Pip uses Maps’ own Exit button on the navigation notification."
        val allowToys = "Turn off Allow toys for no toys at all, or leave it on and choose which " +
            "toys Pip may bring out."
        val spontaneous = "Turn this off to keep enabled toys available only when you ask. Pip " +
            "can pick Doomscroll on his own only when your Home screen is clear. He skips the " +
            "last toy when picking on his own and waits if there’s no other eligible toy; you " +
            "can still ask for it yourself."
        val toySounds = "Squeaks, pops, pours and whistles, made on the phone with no downloads. " +
            "Every one is a little different each time. Pip stays quiet while he is talking, and " +
            "silent mode still means silent."
        val toySoundsVolume = "How loud the toys are. It is a multiplier on top of your phone’s " +
            "media volume, so this is the whole range and the phone’s volume keys still work." +
            "\n\nTry a toy sound plays a sample at the current volume without waiting for a " +
            "bubble."
        val messagePreviews = "Off by default. When you ask who texted you, Pip checks current " +
            "message previews only while the phone is unlocked. Pip reads the preview with an " +
            "offline voice. The message is never saved to chat history or sent to the API."
    }

    /**
     * Which section is being built. A crash here used to say only that the screen died; with
     * this in the record, Diagnostics names the step and the frame that threw.
     */
    private var building = "start"

    /** Set when the screen could not be built, so later callbacks stay out of the way. */
    private var buildFailed = false

    private fun step(name: String) {
        building = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildScreen(savedInstanceState)
        } catch (t: Throwable) {
            buildFailed = true
            Diag.caught("settings screen · while building $building", t)
            // Also kept on disk and shown on the main screen. This caught the throw, so it
            // never reaches the process-wide handler, and it is exactly the case the user
            // has to be able to report.
            Diag.recordCrash(t)
            // Shown here as well as recorded: the record is what survives, this is what the
            // user can act on without leaving the screen.
            showBuildFailure(t)
        }
    }

    /** A last-resort screen: a crash in settings must still leave something usable behind. */
    private fun showBuildFailure(t: Throwable) {
        val origin = t.stackTrace.firstOrNull { it.className.startsWith("com.phoneassistant") }
            ?.let { "${it.fileName}:${it.lineNumber} ${it.methodName}" }
        val text = TextView(this).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
            text = "Pip settings couldn’t open.\n\n" +
                "While building: $building\n" +
                "${t.javaClass.simpleName}: ${t.message?.take(300).orEmpty()}\n" +
                (origin?.let { "At: $it\n" } ?: "") +
                "\nThis is also kept on the main screen, so it survives closing Pip."
        }
        val back = Button(this).apply {
            setText("Close")
            isAllCaps = false
            setOnClickListener { finish() }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(LinearLayout(this@PipSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
                addView(back)
            })
        })
    }

    private fun buildScreen(savedInstanceState: Bundle?) {
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

        step("start")
        text("Pip settings", 30f, R.color.text)
        step("Movement & startup")
        heading("Movement & startup")
        option("Wander around the screen", Option.WANDER, Notes.wander)
        toggleLive(getString(R.string.start_at_boot), PipStartup.enabled(this), ::bootNote) {
            PipStartup.setEnabled(this, it)
            refreshHints()
        }
        openAtBoot = toggle("Open the Pip app after restart", PipStartup.openAppAtBoot(this),
            Notes.openAtBoot) {
            PipStartup.setOpenAppAtBoot(this, it)
        }

        step("Phone access")
        heading("Phone access")
        optionLive(getString(R.string.permissions_access_switch), Option.ACCESSIBILITY, ::accessNote)

        step("Memory")
        heading("Memory")
        option("Learn from chat", Option.LEARN_FROM_CHAT, Notes.learnFromChat)

        step("Voice")
        heading("Voice")
        toggle("Read Pip’s replies aloud", VoiceSettings.config(this).enabled, Notes.readingAloud) {
            VoiceSettings.setEnabled(this, it)
        }
        voiceSummary = text("")
        plainButton("Choose Pip’s voice") { configureVoice() }
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
        plainButton("Preview Pip’s voice") {
            PipVoice.preview(this) { result ->
                if (!isFinishing && !isDestroyed) {
                    refreshVoiceSummary()
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        plainButton("Preview reply bubble") {
            if (!OverlayService.previewBubble()) Toast.makeText(this,
                "Start Pip and let him finish any current reply, then try again.", Toast.LENGTH_LONG).show()
        }

        step("Talk to Pip")
        heading("Talk to Pip", Notes.listening)
        toggle("Start listening when Pip starts", PipStartup.listenOnStart(this), Notes.listenOnStart) { enabled ->
            PipStartup.setListenOnStart(this, enabled)
            if (enabled) WakeListenService.startProblem(this)?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }.isEnabled = true
        wakeAction = button("") { changeWakeListening() }
        wakeStatus = text("")
        wakeSpeak = button("Speak now (no wake word)") {
            if (!WakeListenService.speakNow()) {
                Toast.makeText(this, "Wait until Pip is ready to listen.", Toast.LENGTH_SHORT).show()
            }
        }
        refreshWakeAction()

        step("Hands-free driving")
        heading("Hands-free driving", Notes.driving)
        toggle("Allow hands-free driving commands", VoiceSettings.drivingEnabled(this), null) {
            VoiceSettings.setDrivingEnabled(this, it)
        }
        toggle("Accept driving commands without saying “Pip”", VoiceSettings.carNoName(this), Notes.carNoName) {
            VoiceSettings.setCarNoName(this, it)
        }
        toggle("Answer “are we still navigating?” without saying “Pip”", VoiceSettings.carQuestions(this),
            Notes.carQuestions) {
            VoiceSettings.setCarQuestions(this, it)
        }
        plainButton("Fix navigation access for Pip") {
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
        plainButton("Set or clear driving home destination") {
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

        step("Toys")
        val toyHeading = addToySettings()
        step("Personality & data")
        addPersonalityAndData()
        step("Permissions")
        addPermissions()
        step("API & model")
        addApiSettings()
        plainButton(getString(R.string.debug_options)) {
            startActivity(Intent(this, DebugOptionsActivity::class.java))
        }
        plainButton("Done") { finish() }
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
        kokoroAction.visibility = if (showKokoro) View.VISIBLE else View.GONE
        kokoroAction.isEnabled = !KokoroVoice.busy()
        kokoroAction.text = if (KokoroVoice.hasDownload(this)) "Remove Kokoro download" else "Download Kokoro"
    }

    override fun onStart() {
        super.onStart()
        if (buildFailed) return
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

    /** Returns the Toys heading's row, so the deep link can scroll to the section itself. */
    private fun addToySettings(): View {
        val toyHeading = heading("Toys")
        option("Allow toys", Option.TOYS, Notes.allowToys)
        toySwitches += option("Bring out toys on his own", Option.SPONTANEOUS, Notes.spontaneous)
        for (toy in Toy.values()) {
            toySwitches += toggle(toy.label, PipSettings.toySelected(this, toy), null) {
                PipSettings.setToy(this, toy, it)
                OverlayService.behaviorSettingsChanged()
            }
        }
        plainButton("Painting gallery") { startActivity(Intent(this, PaintingGalleryActivity::class.java)) }
        toggle("Toy sound effects", ToyAudio.enabled(this), Notes.toySounds) {
            ToyAudio.setEnabled(this, it)
            if (it) ToyAudio.play(this, ToySounds.Sound.POP, 0.9f)
        }
        button("Toy sound volume: ${(ToyAudio.volume(this) * 100).toInt()}%", Notes.toySoundsVolume) {
            val values = arrayOf(30, 50, 70, 90, 100)
            AlertDialog.Builder(this).setTitle("Toy sound volume")
                .setItems(values.map { "$it%" }.toTypedArray()) { _, which ->
                    ToyAudio.setVolume(this, values[which] / 100f)
                    ToyAudio.play(this, ToySounds.Sound.SQUEAK, 0.9f)
                    recreate()
                }
                .setNegativeButton("Cancel", null).show()
        }
        plainButton("Try a toy sound") {
            ToyAudio.play(this, ToySounds.Sound.SQUEAK, 1f)
            ToyAudio.playPair(this, ToySounds.Sound.POP, ToySounds.Sound.GOAL, 1f)
        }
        return toyHeading
    }

    private fun addPersonalityAndData() {
        heading(getString(R.string.settings_personal_heading))
        plainButton(getString(R.string.settings_personality)) {
            startActivity(Intent(this, PersonaActivity::class.java))
        }
        plainButton(getString(R.string.settings_memory)) {
            startActivity(Intent(this, MemoryActivity::class.java))
        }
        plainButton(getString(R.string.settings_logins)) {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private fun addPermissions() {
        heading(getString(R.string.permissions_heading))
        for (permission in Permission.entries) {
            val row = PipPermissions.row(permission)
            permission(row.title, { PipPermissions.granted(this, permission) }, row.description, row.manage) {
                PipPermissions.manage(this, permission)
            }
            if (permission == Permission.NOTIFICATION_ACCESS) {
                option(getString(R.string.permissions_message_previews), Option.MESSAGE_NOTIFICATIONS,
                    Notes.messagePreviews)
                }
        }
    }

    private fun permission(title: Int, allowed: () -> Boolean, detail: Int? = null,
                           action: Int = R.string.permissions_manage, click: () -> Unit) {
        val label = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.text))
        }
        permissionStatuses += {
            label.text = getString(R.string.permissions_status, getString(title),
                getString(if (allowed()) R.string.permissions_on else R.string.permissions_off))
        }
        // The explanation sits behind the information button on the status row, where the
        // permission it describes is named.
        withInfo(getString(title), { detail?.let { getString(it) } }, label, dp(6), 0)
        // The permission's explanation sits behind the information button on its action row.
        val actionButton = button(getString(action), detail?.let { getString(it) }, click)
        actionButton.contentDescription =
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
        // After a failed build none of this exists yet, and throwing here would replace the
        // real failure with a second one before the user could read the first.
        if (buildFailed) return
        try {
            refreshApiIfChanged()
            refreshHints()
            refreshWakeAction()
        } catch (t: Throwable) {
            Diag.caught("settings screen · while refreshing", t)
        }
    }

    /** Automatic start says the same thing whether or not the permission is in place. */
    private fun bootNote(): String = buildString {
        append(getString(R.string.start_at_boot_hint))
        if (PipStartup.enabled(this@PipSettingsActivity) && !Settings.canDrawOverlays(this@PipSettingsActivity)) {
            append("\n\n").append(getString(R.string.start_at_boot_needs_overlay))
        }
    }

    /**
     * Locks the screen from settings, so the feature can be tried without going through a
     * spoken request first. Failures say what is missing rather than failing silently.
     */
    private fun lockNow() {
        // The screen is usually opened without the overlay running, so this may be the first
        // thing that ever needs a context here.
        ScreenLocker.attach(this)
        if (!ScreenLocker.available()) {
            Toast.makeText(this, ScreenLocker.message(ScreenLocker.Result.NO_ACCESS), Toast.LENGTH_LONG).show()
            return
        }
        // The availability check reads the keyguard and, on Android 8, the accessibility tree,
        // so it runs on the worker with the lock itself rather than on the main thread.
        Thread({
            val result = try {
                ScreenLocker.readiness(this) ?: ScreenLocker.lock()
            } catch (t: Throwable) {
                Diag.caught("lock screen from settings", t)
                ScreenLocker.Result.NO_ACCESS
            }
            if (result !in setOf(ScreenLocker.Result.LOCKED, ScreenLocker.Result.ACCEPTED,
                    ScreenLocker.Result.ALREADY_LOCKED)) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, ScreenLocker.message(result), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "PipScreenLockSettings").start()
    }

    /** What the accessibility switch is doing right now, including the step still missing. */
    private fun accessNote(): String = when {
        !PipSettings.enabled(this, Option.ACCESSIBILITY) ->
            "Pip won’t read other apps or use accessibility to keep clear of their keyboards. " +
                "Android’s separate permission is unchanged."
        !ImeWatch.enabled(this) -> "Turn on Phone Assistant in Android accessibility settings under Permissions below."
        else -> "Lets Pip work other apps when you ask and stay clear of their keyboards."
    }

    private fun refreshHints() {
        permissionStatuses.forEach { it() }
        openAtBoot.isEnabled = PipStartup.enabled(this)
        // Status lives behind the information buttons now, so it is worked out on demand.
        val toysAllowed = PipSettings.enabled(this, Option.TOYS)
        toySwitches.forEach { it.isEnabled = toysAllowed }
    }

    private fun option(label: String, option: Option, note: String? = null): Switch =
        optionLive(label, option) { note }

    private fun optionLive(label: String, option: Option, note: () -> String?): Switch =
        toggleLive(label, PipSettings.enabled(this, option), note) {
        PipSettings.set(this, option, it)
        if (option == Option.ACCESSIBILITY) {
            if (!it) Agent.cancel()
            ImeWatch.probe()
        }
        OverlayService.behaviorSettingsChanged()
        refreshHints()
    }

    /**
     * Puts [control] in a row with its information button, and adds that row to the screen.
     *
     * It returns nothing on purpose. Returning the row invited callers to add it a second time,
     * which Android rejects with "The specified child already has a parent" — that is what used
     * to crash this screen on open. A caller that needs a handle for scrolling asks for the row
     * through [infoRow] instead, where the return value is clearly the thing to scroll to.
     */
    private fun withInfo(label: String, note: () -> String?, control: View,
                         padTop: Int = 0, padBottom: Int = 0) {
        infoRow(label, note, control, padTop, padBottom)
    }

    /**
     * The row [withInfo] builds, returned for a caller that needs to scroll to it. Adding this
     * to [content] again is the bug that crashed settings, so it exists only for headings.
     */
    private fun infoRow(label: String, note: () -> String?, control: View,
                        padTop: Int = 0, padBottom: Int = 0): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, padTop, 0, padBottom)
        }
        row.addView(control, LinearLayout.LayoutParams(0, -2, 1f))
        val info = infoButton(label, note)
        if (info != null) row.addView(info, LinearLayout.LayoutParams(dp(40), dp(48)))
        content.addView(row, -1, -2)
        return row
    }

    /** A button that carries an explanation, for controls that are not switches. */
    private fun button(label: String, note: String?, click: () -> Unit): Button {
        val built = button(label, click)
        withInfo(label, { note }, built)
        return built
    }

    private fun toggle(label: String, checked: Boolean, note: String?, change: (Boolean) -> Unit): Switch =
        toggleLive(label, checked, { note }, change)

    /**
     * The same row, with the explanation worked out when the button is tapped — for the few
     * notes that depend on permissions that can change while this screen is open.
     */
    private fun toggleLive(label: String, checked: Boolean, note: () -> String?, change: (Boolean) -> Unit): Switch {
        val switch = Switch(this).apply {
            text = label
            setTextColor(getColor(R.color.text))
            minHeight = dp(48)
            isChecked = checked
            setOnCheckedChangeListener { _, enabled -> change(enabled) }
        }
        withInfo(label, note, switch)
        return switch
    }

    /**
     * The information button itself. A null note means the row has nothing more to say, and
     * a button that opens an empty box would be worse than no button.
     */
    private fun infoButton(label: String, note: () -> String?): ImageButton? {
        // A button that opens an empty box would be worse than no button, so a row with no
        // note gets none. The note is only consulted once, to decide whether to show the icon.
        if (note().isNullOrBlank()) return null
        return ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            background = null
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(12), dp(10), dp(12))
            contentDescription = getString(R.string.settings_about_option, label)
            setOnClickListener { note()?.let { explain(label, it) } }
        }
    }

    /** The pop-up behind an information button: the explanation, and nothing to decide. */
    private fun explain(label: String, note: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(note)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toggle(label: String, checked: Boolean, change: (Boolean) -> Unit): Switch =
        toggle(label, checked, null, change)

    /**
     * A section heading, with the section's long explanation behind its information button.
     * Returns the row as added, so a caller can scroll to the section itself.
     */
    private fun heading(label: String, note: String? = null): View {
        val title = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(getColor(R.color.text))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        return infoRow(label, { note }, title, dp(20), dp(4))
    }

    private fun text(label: String, size: Float = 14f, color: Int = R.color.textDim): TextView = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(getColor(color))
        content.addView(this, -1, -2)
    }

    /**
     * Builds a button without adding it anywhere, so it can be put in an information row.
     *
     * Adding it here as well is exactly what broke this screen: the caller then handed an
     * already-parented view to [infoRow], and Android refused it with "The specified child
     * already has a parent". Only [infoRow] may add a row; nothing else attaches a control.
     */
    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = getColorStateList(R.color.surfaceAlt)
        setTextColor(getColor(R.color.text))
        setOnClickListener { click() }
    }

    /** A button with no information button, added straight to the screen. */
    private fun plainButton(label: String, click: () -> Unit) {
        content.addView(button(label, click), -1, -2)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
