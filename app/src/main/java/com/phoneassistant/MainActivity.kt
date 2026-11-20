package com.phoneassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
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
    private lateinit var breakRow: LinearLayout
    private lateinit var breakStatus: TextView
    private var availableRelease: UpdateChecker.Result? = null

    /** Keeps the break countdown honest while the screen is open. */
    private val breakTicker = object : Runnable {
        override fun run() {
            showBreakStatus()
            breakStatus.postDelayed(this, 1_000L)
        }
    }

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

        findViewById<Button>(R.id.pipSettingsButton).setOnClickListener {
            startActivity(Intent(this, PipSettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.keyLink).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }

        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.personaButton).setOnClickListener {
            startActivity(Intent(this, PersonaActivity::class.java))
        }

        findViewById<Button>(R.id.memoryButton).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        findViewById<Button>(R.id.loginsButton).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        breakRow = findViewById(R.id.breakRow)
        breakStatus = findViewById(R.id.breakStatus)
        findViewById<Button>(R.id.endBreakButton).setOnClickListener {
            // He says his own line if he is running; the stored break goes either way.
            if (!OverlayService.endBreak()) Quiet.clear(this)
            showBreakStatus()
        }
        findViewById<Button>(R.id.breakButton).setOnClickListener { askBreakLength() }

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
        breakStatus.post(breakTicker)
    }

    override fun onPause() {
        breakStatus.removeCallbacks(breakTicker)
        super.onPause()
    }

    /** Sends him on a break from the main screen, with the usual lengths to pick from. */
    private fun askBreakLength() {
        val labels = arrayOf("15 minutes", "30 minutes", "1 hour", "2 hours")
        val minutes = longArrayOf(15L, 30L, 60L, 120L)
        AlertDialog.Builder(this)
            .setTitle("How long should Pip take a break?")
            .setItems(labels) { _, which ->
                val ms = minutes[which] * 60_000L
                if (!OverlayService.startBreak(ms)) Quiet.start(this, ms)
                showBreakStatus()
            }
            .show()
    }

    /** Shows exactly how long Pip has left on a toy break, or hides the line when he is free. */
    private fun showBreakStatus() {
        val left = Quiet.remaining(this)
        if (left <= 0L) {
            breakRow.visibility = View.GONE
            return
        }
        val total = (left + 999L) / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        breakStatus.text = if (hours > 0L) {
            "Pip is on a break — %d:%02d:%02d left".format(hours, minutes, seconds)
        } else {
            "Pip is on a break — %d:%02d left".format(minutes, seconds)
        }
        breakRow.visibility = View.VISIBLE
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

    /** One button per thing Pip can put on the screen, so nothing has to be guessed. */
    private fun buildToyButtons() {
        val box = findViewById<LinearLayout>(R.id.toyBox)
        box.removeAllViews()
        var row: LinearLayout? = null

        fun addButton(label: String, action: () -> Boolean) {
            if (row == null) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                }
                box.addView(row)
            }
            val button = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    val ok = action()
                    val note = when {
                        ok -> "Pip is on it"
                        !OverlayService.running -> "Start Pip first"
                        else -> "Pip says not right now"
                    }
                    Toast.makeText(this@MainActivity, note, Toast.LENGTH_SHORT).show()
                }
            }
            row?.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        Toy.values().filter { PipSettings.toyAllowed(this, it) }.forEach { toy ->
            addButton(toy.label) { OverlayService.bringOut(toy) }
        }
        addButton("Tidy up") { OverlayService.tidyUp() }
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
        buildToyButtons()
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
        // Optional: without it he still keeps clear of his own keyboard, but he can neither
        // see a keyboard opened in another app nor work another app for the user.
        addPermissionRow("Android accessibility permission", ImeWatch.enabled(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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
