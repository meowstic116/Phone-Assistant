package com.phoneassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var startButton: Button
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private val powerStateListener: () -> Unit = { refreshPowerButton() }
    private var availableRelease: UpdateChecker.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AssistantBrain.setupComplete(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            if (OverlayService.running) {
                OverlayService.stop(this)
            } else {
                startAssistant()
            }
        }

        findViewById<Button>(R.id.pipSettingsButton).setOnClickListener {
            startActivity(Intent(this, PipSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.toysButton).setOnClickListener {
            startActivity(Intent(this, ToysActivity::class.java))
        }

        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        updateStatus = findViewById(R.id.updateStatus)
        updateButton = findViewById(R.id.updateButton)
        updateButton.setOnClickListener {
            val release = availableRelease
            val target = release?.downloadUrl ?: release?.pageUrl
            if (release != null && !target.isNullOrBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
            } else {
                checkForUpdates()
            }
        }
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (!::startButton.isInitialized) return
        OverlayService.onPowerStateChanged = powerStateListener
        refreshPowerButton()
    }

    override fun onPause() {
        if (OverlayService.onPowerStateChanged === powerStateListener) OverlayService.onPowerStateChanged = null
        super.onPause()
    }

    private fun checkForUpdates() {
        updateButton.isEnabled = false
        updateStatus.text = "Checking for updates…"
        updateButton.text = "Check for updates"
        availableRelease = null

        UpdateChecker.check(this) { result ->
            runOnUiThread {
                updateButton.isEnabled = true
                if (result == null) {
                    updateStatus.text = "Couldn't reach GitHub"
                } else if (result.version.isBlank()) {
                    // GitHub answers 404 both for "no releases" and for a private repo,
                    // so the message has to cover both rather than guess.
                    updateStatus.text = "No releases found — the repo may be private " +
                        "(installed " + result.currentVersion + ")"
                } else if (result.newer) {
                    availableRelease = result
                    updateStatus.text = "Update available: " + result.version +
                        " (installed " + result.currentVersion + ")"
                    updateButton.text = "Download " + result.version
                } else {
                    updateStatus.text = "Up to date (" + result.currentVersion + ")"
                }
            }
        }
    }

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show()
            PipPermissions.openOverlay(this)
            return
        }
        OverlayService.start(this)
    }

    private fun refreshPowerButton() {
        val stopping = OverlayService.stopping
        startButton.isEnabled = !stopping
        startButton.text = when {
            stopping -> "Stopping Pip…"
            OverlayService.running -> "Stop assistant"
            else -> "Start assistant"
        }
        startButton.backgroundTintList = getColorStateList(
            if (OverlayService.running) R.color.surfaceAlt else R.color.accent
        )
        startButton.setTextColor(getColor(if (OverlayService.running) R.color.text else R.color.onAccent))
    }
}
