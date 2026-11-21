package com.phoneassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var startButton: Button
    private val powerStateListener: () -> Unit = { refreshPowerButton() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceSettings.config(this) // Migrate retired speech settings without starting a voice engine.
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

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first", Toast.LENGTH_LONG).show()
            PipPermissions.openOverlay(this)
            return
        }
        if (!OverlayService.startFromApp(this)) {
            Toast.makeText(this, "Android couldn’t start Pip. Try again with the app open.", Toast.LENGTH_LONG).show()
        }
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
