package com.phoneassistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.phoneassistant.SetupPolicy.Check
import com.phoneassistant.SetupPolicy.Permission
import com.phoneassistant.SetupPolicy.Step
import java.util.concurrent.Future

/** First launch or a manual replay: welcome, verify the brain, and explain Android's permissions. */
class OnboardingActivity : Activity() {
    companion object {
        internal const val EXTRA_REPLAY = "com.phoneassistant.setup.REPLAY"
    }

    private lateinit var page: LinearLayout
    private lateinit var step: Step
    private var replay = false
    // Kept in memory through rotation, never in the saved-state Bundle or preferences until Finish.
    private var replayDraft: AssistantBrain.Config? = null
    private var requestId = 0
    private var validation: Future<*>? = null
    private val permissionUpdates = mutableListOf<() -> Unit>()
    private var wakeFailed = false
    private var wakeStatus: TextView? = null
    private var wakeButton: Button? = null
    private var kokoroStatus: TextView? = null
    private var kokoroButton: Button? = null
    private val wakeListener: () -> Unit = { runOnUiThread { refreshVoiceCards() } }
    private val kokoroListener: () -> Unit = { runOnUiThread { refreshVoiceCards() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alreadyComplete = AssistantBrain.setupComplete(this)
        replay = alreadyComplete && intent.getBooleanExtra(EXTRA_REPLAY, false)
        if (alreadyComplete && !replay) {
            openMain()
            return
        }
        if (replay) {
            @Suppress("DEPRECATION")
            replayDraft = lastNonConfigurationInstance as? AssistantBrain.Config
        }
        val saved = savedInstanceState?.getString("step")?.let { name -> Step.values().firstOrNull { it.name == name } }
        show(SetupPolicy.resumeStep(saved, verified()))
    }

    @Deprecated("Retain a verified replay draft without saving the API key in a Bundle")
    override fun onRetainNonConfigurationInstance(): Any? = replayDraft

    private fun verified(): Boolean = SetupPolicy.proofForRun(replay, AssistantBrain.setupVerified(this), replayDraft != null)

    override fun onSaveInstanceState(outState: Bundle) {
        if (::step.isInitialized) outState.putString("step", step.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        permissionUpdates.forEach { it() }
        WakeModel.observe(wakeListener)
        KokoroVoice.observe(kokoroListener)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionUpdates.forEach { it() }
    }

    @Deprecated("Wizard back navigates within its own steps")
    override fun onBackPressed() {
        if (!::step.isInitialized || step == Step.WELCOME) finish()
        else show(backTarget())
    }

    override fun onDestroy() {
        cancelValidation()
        WakeModel.forget(wakeListener)
        KokoroVoice.forget(kokoroListener)
        super.onDestroy()
    }

    private fun backTarget(): Step = when (step) {
        Step.VOICE -> Step.PERMISSIONS
        Step.PERMISSIONS -> Step.API
        else -> Step.WELCOME
    }

    private fun show(target: Step) {
        cancelValidation()
        currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
        step = SetupPolicy.resumeStep(target, verified())
        permissionUpdates.clear()
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isFocusableInTouchMode = true
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            isFillViewport = true
            addView(page)
        })
        page.requestFocus()
        if (step != Step.WELCOME) {
            button(page, "Back") { show(backTarget()) }
        }
        line(page, "STEP ${step.ordinal + 1} OF ${Step.values().size}", 12f, R.color.accent, 16).letterSpacing = 0.13f
        when (step) {
            Step.WELCOME -> welcome()
            Step.API -> connect()
            Step.PERMISSIONS -> permissions()
            Step.VOICE -> voice()
        }
    }

    private fun welcome() {
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pip)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16) })
        heading("Meet Pip")
        line(page, "Pip is a little companion who lives on your screen. Talk to him, play with his toys, or ask him to help with an app.", 16f, top = 10)
        line(page, "First, connect an AI provider. Then choose what Pip is allowed to do on your phone.", 15f, top = 12)
        if (replay) line(page, getString(R.string.debug_setup_replay_notice), 15f, top = 10)
        button(page, "Get started", primary = true, top = 22) { show(Step.API) }
        if (replay) button(page, getString(R.string.debug_setup_exit), top = 6) { finish() }
    }

    private fun connect() {
        heading("Connect Pip")
        line(page, "Enter an API key to continue. Pip sends one small test prompt to the provider and model below. The test doesn’t include your chats or screen contents.", 15f, top = 10)
        val privacy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        page.addView(privacy, row(12))
        line(privacy, getString(R.string.setup_privacy_heading), 16f, R.color.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        line(privacy, getString(R.string.setup_privacy_local), 14f, top = 6)
        line(privacy, getString(R.string.setup_privacy_api), 14f, top = 8)
        line(privacy, getString(R.string.setup_privacy_passwords), 14f, top = 8)
        val api = layoutInflater.inflate(R.layout.settings_api, page, false)
        page.addView(api)
        val base = api.findViewById<EditText>(R.id.baseUrlField)
        val key = api.findViewById<EditText>(R.id.apiKeyField)
        val model = api.findViewById<EditText>(R.id.modelField)
        val config = replayDraft ?: AssistantBrain.config(this)
        base.setText(config.baseUrl)
        key.setText(config.apiKey)
        model.setText(config.model)
        api.findViewById<Button>(R.id.keyLink).setOnClickListener {
            openSettings(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        api.findViewById<Button>(R.id.resetApiButton).setOnClickListener {
            // Refills the form only; the key is not saved until setup is verified.
            val defaults = AssistantBrain.defaultConfig()
            base.setText(defaults.baseUrl)
            key.setText(defaults.apiKey)
            model.setText(defaults.model)
        }
        api.findViewById<Button>(R.id.saveApiButton).visibility = View.GONE
        val status = line(page, "", 14f, R.color.accent, 8).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val verify = button(page, "Verify & continue", primary = true, top = 12)
        fun feedback(message: String) {
            status.text = message
            status.visibility = View.VISIBLE
        }
        fun busy(value: Boolean) {
            verify.isEnabled = !value
            base.isEnabled = !value
            key.isEnabled = !value
            model.isEnabled = !value
        }
        verify.setOnClickListener {
            val input = AssistantBrain.Config(base.text.toString().trim(), key.text.toString().trim(), model.text.toString().trim())
            val issue = when {
                input.apiKey.isBlank() || input.model.isBlank() || input.baseUrl.isBlank() -> Check.MISSING
                !SetupPolicy.validUrl(input.baseUrl) -> Check.URL
                !SetupPolicy.validApiKey(input.apiKey) -> Check.KEY
                else -> null
            }
            if (issue != null) {
                feedback(checkMessage(issue))
                return@setOnClickListener
            }
            currentFocus?.let { getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(it.windowToken, 0) }
            busy(true)
            feedback("Checking your API key and model…")
            val id = ++requestId
            validation = AssistantBrain.verifySetup(input) { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || id != requestId || step != Step.API) return@runOnUiThread
                    validation = null
                    if (result == Check.OK) {
                        if (replay) replayDraft = input else AssistantBrain.saveVerifiedSetup(this, input)
                        show(Step.PERMISSIONS)
                    } else {
                        busy(false)
                        feedback(checkMessage(result))
                    }
                }
            }
        }
    }

    private fun permissions() {
        heading("Make room for Pip")
        line(page, getString(R.string.setup_permissions_intro), 15f, top = 10)
        for (permission in Permission.entries) {
            val entry = PipPermissions.row(permission)
            val card = permission(getString(entry.title), getString(entry.description), { PipPermissions.granted(this, permission) }) {
                PipPermissions.requestFromSetup(this, permission)
            }
            if (permission == Permission.NOTIFICATION_ACCESS) {
                card.addView(Switch(this).apply {
                    setText(R.string.permissions_message_previews)
                    setTextColor(getColor(R.color.text))
                    minHeight = dp(48)
                    isChecked = PipSettings.enabled(this@OnboardingActivity, PipSettings.Option.MESSAGE_NOTIFICATIONS)
                    setOnCheckedChangeListener { _, enabled ->
                        PipSettings.set(this@OnboardingActivity, PipSettings.Option.MESSAGE_NOTIFICATIONS, enabled)
                    }
                }, row(4))
                line(card, getString(R.string.permissions_message_previews_info), 14f)
            }
        }
        button(page, "Continue", primary = true, top = 18) { show(Step.VOICE) }
    }

    /** The optional voice services: ask once, download now or later in Pip settings. */
    private fun voice() {
        heading("Voice, if you want it")
        line(page, "Two optional downloads let you talk to Pip and hear him answer aloud. Both work offline on this phone. Say no for now and add them later in Pip settings.", 15f, top = 10)

        val stt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        page.addView(stt, row(12))
        line(stt, "Voice to text — talk to Pip", 16f, R.color.text).setTypeface(null, Typeface.BOLD)
        line(stt, "Lets you ask out loud, hands-free. The wake word and your speech are recognized on this phone; audio is never saved or sent.", 14f, top = 6)
        wakeStatus = line(stt, "", 14f, R.color.accent, 8)
        wakeButton = button(stt, "", top = 6) { startWakeDownload() }

        val tts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        page.addView(tts, row(11))
        line(tts, "Text to voice — Pip speaks", 16f, R.color.text).setTypeface(null, Typeface.BOLD)
        line(tts, "Pip reads his replies aloud in his own voice, offline.", 14f, top = 6)
        kokoroStatus = line(tts, "", 14f, R.color.accent, 8)
        kokoroButton = button(tts, "", top = 6) { startKokoroDownload() }

        button(page, "Finish setup", primary = true, top = 18) {
            if (replay) {
                val verifiedDraft = replayDraft ?: return@button show(Step.API)
                AssistantBrain.saveVerifiedSetup(this, verifiedDraft)
                finish()
            } else if (AssistantBrain.finishSetup(this)) openMain() else show(Step.API)
        }
        refreshVoiceCards()
    }

    private fun startWakeDownload() {
        if (WakeModel.busy || WakeModel.ready(this)) return
        wakeFailed = false
        refreshVoiceCards()
        WakeModel.install(this) { ok ->
            runOnUiThread {
                if (!ok) wakeFailed = true
                refreshVoiceCards()
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, if (ok) "Voice to text is ready."
                    else "Couldn’t download voice to text. Check your connection and free space.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startKokoroDownload() {
        if (KokoroVoice.busy() || KokoroVoice.ready(this)) return
        KokoroVoice.install(this)
    }

    private fun refreshVoiceCards() {
        if (!::step.isInitialized || step != Step.VOICE || wakeStatus == null) return
        val wakeReady = WakeModel.ready(this)
        wakeStatus?.text = when {
            wakeReady -> "Installed."
            WakeModel.busy -> "Downloading voice to text…"
            wakeFailed -> "Couldn’t download. Check your connection and free space."
            else -> "Not downloaded — a one-time 57 MB download."
        }
        wakeButton?.text = when {
            wakeReady -> "Installed"
            WakeModel.busy -> "Downloading…"
            else -> "Download voice to text (57 MB)"
        }
        wakeButton?.isEnabled = !wakeReady && !WakeModel.busy
        val kokoroReady = KokoroVoice.ready(this)
        kokoroStatus?.text = KokoroVoice.status(this)
        kokoroButton?.text = when {
            kokoroReady -> "Installed"
            KokoroVoice.busy() -> "Downloading…"
            else -> "Download Pip’s voice (110 MB)"
        }
        kokoroButton?.isEnabled = !kokoroReady && !KokoroVoice.busy() && KokoroVoice.supported()
    }

    private fun permission(title: String, description: String, granted: () -> Boolean, request: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_card)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        page.addView(card, row(11))
        line(card, title, 16f, R.color.text).setTypeface(null, Typeface.BOLD)
        line(card, description, 14f, top = 3)
        val allow = button(card, "Allow", top = 3, click = request)
        permissionUpdates += {
            val enabled = granted()
            allow.text = if (enabled) "Allowed" else "Allow"
            allow.contentDescription = if (enabled) "$title: allowed" else "Allow $title"
            allow.isEnabled = !enabled
        }
        permissionUpdates.last().invoke()
        return card
    }

    private fun checkMessage(result: Check): String = when (result) {
        Check.MISSING -> "Enter an API key, base URL, and model before continuing."
        Check.URL -> "Use the provider’s final HTTPS API base URL, without a query or redirect."
        Check.KEY -> "The provider rejected this key or its permissions. Check the key, project, and model access."
        Check.MODEL -> "The provider couldn’t use these settings. Check the base URL and model name."
        Check.LIMIT -> "The provider’s request limit was reached. Wait a little and try again."
        Check.PROVIDER -> "The provider is having trouble. Try again shortly."
        Check.NETWORK -> "Couldn’t connect to the provider. Check your internet connection and try again."
        Check.RESPONSE -> "The provider didn’t return a usable chat reply. Check the model and try again."
        Check.OK -> "API verified."
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No app is available to open this page.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun cancelValidation() {
        requestId++
        validation?.cancel(true)
        validation = null
    }

    private fun heading(text: String) {
        line(page, text, 32f, R.color.text, 6).apply {
            setTypeface(typeface, Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
    }

    private fun line(parent: LinearLayout, label: String, size: Float, color: Int = R.color.textDim,
                     top: Int = 0) = TextView(this).apply {
        text = label
        textSize = size
        setTextColor(getColor(color))
        setLineSpacing(dp(2).toFloat(), 1f)
        parent.addView(this, row(top))
    }

    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, top: Int = 0,
                       click: (() -> Unit)? = null) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = getColorStateList(if (primary) R.color.accent else R.color.surfaceAlt)
        setTextColor(getColor(if (primary) R.color.onAccent else R.color.text))
        if (click != null) setOnClickListener { click() }
        parent.addView(this, row(top))
    }

    private fun row(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
