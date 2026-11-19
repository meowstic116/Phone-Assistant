package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Local vault management. Credentials can only be revealed after device authentication. */
class VaultActivity : Activity() {

    private var pendingRevealSite: String? = null
    private var revealDialog: AlertDialog? = null

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var siteField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        siteField = field("Website — e.g. example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        userField = field("Username or email", InputType.TYPE_CLASS_TEXT)
        passField = field(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        passField.imeOptions = EditorInfo.IME_ACTION_DONE
        // Their keyboard's autofill has no business keeping a copy of this.
        passField.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        passField.isSaveEnabled = false
        passField.imeOptions = passField.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        passField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(8f), 0, 0)
            visibility = View.GONE
        }

        val save = Button(this).apply {
            text = "Save on this phone"
            backgroundTintList = getColorStateList(R.color.accent)
            setTextColor(getColor(R.color.onAccent))
            isAllCaps = false
            setOnClickListener { save() }
        }

        empty = TextView(this).apply {
            text = "No logins saved on this phone."
            textSize = 13f
            setTextColor(getColor(R.color.textDim))
            setPadding(0, dp(12f), 0, 0)
        }

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
            addView(
                TextView(this@VaultActivity).apply {
                    text = "Logins"
                    textSize = 26f
                    setTextColor(getColor(R.color.text))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            )
            addView(
                TextView(this@VaultActivity).apply {
                    text = "Logins stay encrypted on this phone. Pip does not fill them into " +
                        "other apps. Sign in yourself or use Android Autofill. To recover a stored " +
                        "password, select Reveal and confirm your device lock."
                    textSize = 13f
                    setTextColor(getColor(R.color.textDim))
                    setPadding(0, dp(6f), 0, dp(16f))
                }
            )
            addView(siteField, wide())
            addView(userField, wide(top = 8))
            addView(passField, wide(top = 8))
            addView(save, wide(top = 12))
            addView(status, wide())
            addView(
                View(this@VaultActivity).apply {
                    setBackgroundColor(getColor(R.color.surface))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                    topMargin = dp(18f)
                }
            )
            addView(empty, wide())
            addView(list, wide())
        }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(getColor(R.color.bg))
                addView(column)
            }
        )

    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun save() {
        val site = siteField.text.toString().trim()
        val user = userField.text.toString().trim()
        val password = passField.text.toString()
        if (site.isEmpty() || user.isEmpty() || password.isEmpty()) {
            say("A website, a username and a password — all three.")
            return
        }
        if (!Vault.save(this, site, user, password)) {
            say("That would not save. Try again.")
            return
        }
        siteField.setText("")
        userField.setText("")
        passField.setText("")
        render()
        Toast.makeText(this, "Saved on this phone", Toast.LENGTH_SHORT).show()
    }

    private fun say(line: String) {
        status.text = line
        status.visibility = View.VISIBLE
    }

    private fun render() {
        val logins = Vault.all(this).sortedBy { Vault.host(it.site) }
        list.removeAllViews()
        empty.visibility = if (logins.isEmpty()) View.VISIBLE else View.GONE
        logins.forEach { list.addView(row(it.site, it.username)) }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun row(site: String, username: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, dp(9f))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@VaultActivity).apply {
                    text = Vault.host(site)
                    textSize = 15f
                    setTextColor(getColor(R.color.text))
                }
            )
            addView(
                TextView(this@VaultActivity).apply {
                    text = username
                    textSize = 12f
                    setTextColor(getColor(R.color.textDim))
                }
            )
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(Button(this).apply {
            text = "Reveal"
            isAllCaps = false
            contentDescription = "Reveal the login for ${site}"
            setOnClickListener { confirmReveal(site) }
        })
        row.addView(
            TextView(this).apply {
                text = "×"
                textSize = 18f
                setPadding(dp(14f), dp(2f), dp(6f), dp(2f))
                setTextColor(getColor(R.color.textDim))
                contentDescription = "Delete the login for " + Vault.host(site)
                setOnClickListener {
                    if (Vault.remove(this@VaultActivity, site)) render()
                    else say("I couldn't delete that login. Try again.")
                }
            }
        )
        return row
    }

    @Suppress("DEPRECATION") // Supported by all Android versions this app runs on.
    private fun confirmReveal(site: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val confirmation = keyguard?.createConfirmDeviceCredentialIntent(
            "Reveal saved login", "Confirm your device lock to continue."
        )
        if (confirmation == null) {
            say("Set a PIN, pattern, or password on your phone before revealing a login.")
            return
        }
        pendingRevealSite = site
        runCatching { startActivityForResult(confirmation, REVEAL_LOGIN) }.onFailure {
            pendingRevealSite = null
            say("Couldn't open device authentication.")
        }
    }

    @Deprecated("Device credential confirmation uses an activity result on older Android versions.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REVEAL_LOGIN) return
        val site = pendingRevealSite.also { pendingRevealSite = null } ?: return
        if (resultCode != RESULT_OK || isFinishing || isDestroyed) return
        val login = Vault.all(this).firstOrNull { it.site == site } ?: return
        revealDialog?.dismiss()
        val secret = object : TextView(this) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.text = "Hidden password"
                info.isPassword = true
            }
        }.apply {
            text = "Password: ${login.password}"
            textSize = 16f
            setPadding(dp(24f), dp(16f), dp(24f), dp(12f))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        revealDialog = AlertDialog.Builder(this)
            .setTitle(Vault.host(login.site).ifBlank { login.site })
            .setView(secret)
            .setPositiveButton("Hide", null)
            .create().also { dialog ->
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnDismissListener {
                    secret.text = ""
                    if (revealDialog === dialog) revealDialog = null
                }
                dialog.show()
                secret.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 30_000L)
            }
    }

    override fun onStop() {
        revealDialog?.dismiss()
        revealDialog = null
        super.onStop()
    }

    private companion object {
        const val REVEAL_LOGIN = 101
    }

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 15f
        setTextColor(getColor(R.color.text))
        setHintTextColor(getColor(R.color.textDim))
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun wide(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top.toFloat()) }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
