package com.phoneassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var permissionList: LinearLayout
    private lateinit var startButton: Button
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var modelField: EditText
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var availableRelease: UpdateChecker.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionList = findViewById(R.id.permissionList)
        startButton = findViewById(R.id.startButton)
        baseUrlField = findViewById(R.id.baseUrlField)
        apiKeyField = findViewById(R.id.apiKeyField)
        modelField = findViewById(R.id.modelField)

        val config = AssistantBrain.config(this)
        baseUrlField.setText(config.baseUrl)
        apiKeyField.setText(config.apiKey)
        modelField.setText(config.model)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            AssistantBrain.save(
                this,
                baseUrlField.text.toString().trim(),
                apiKeyField.text.toString().trim(),
                modelField.text.toString().trim()
            )
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        startButton.setOnClickListener {
            if (OverlayService.running) {
                OverlayService.stop(this)
                startButton.postDelayed({ refresh() }, 300L)
            } else {
                startAssistant()
            }
        }

        findViewById<TextView>(R.id.keyLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
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

        askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
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
            openOverlaySettings()
            return
        }
        OverlayService.start(this)
        startButton.postDelayed({ refresh() }, 500L)
    }

    private fun refresh() {
        startButton.text = if (OverlayService.running) "Stop assistant" else "Start assistant"
        startButton.backgroundTintList = getColorStateList(
            if (OverlayService.running) R.color.surfaceAlt else R.color.accent
        )
        startButton.setTextColor(getColor(if (OverlayService.running) R.color.text else R.color.onAccent))

        permissionList.removeAllViews()
        addPermissionRow("Display over other apps", Settings.canDrawOverlays(this)) {
            openOverlaySettings()
        }
        addPermissionRow("Usage access (which app you're in)", AppWatcher.hasPermission(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addPermissionRow("Notifications", notificationsGranted()) { askForNotifications() }
    }

    private fun addPermissionRow(title: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val label = TextView(this).apply {
            text = if (granted) "$title — granted" else "$title — needed"
            textSize = 15f
            setTextColor(getColor(if (granted) R.color.textDim else R.color.text))
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (granted) {
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 16f
                setTextColor(getColor(R.color.accent))
            })
        } else {
            row.addView(Button(this).apply {
                text = "Grant"
                setOnClickListener { onGrant() }
            })
        }

        permissionList.addView(row)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
        )
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
