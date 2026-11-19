package com.phoneassistant

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Where the user gives Pip a login to use, and where they can see and delete the ones he has.
 *
 * The password is never shown again once it is saved — not masked, not behind a reveal, not at
 * all — because nothing in the app ever needs to read it back except the one place that types
 * it, and a screen that can show it is a screen that can leak it.
 *
 * Pip opens this himself, with the site filled in, when a job runs into a sign-in he has no
 * login for: he cannot read a password off the screen (Android redacts them, which is the
 * whole reason he asks), so this form is the only honest way to offer to remember one.
 */
class VaultActivity : Activity() {

    companion object {
        /** The site to prefill, for the form Pip opens when a job needs a login. */
        const val EXTRA_SITE = "site"
    }

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var siteField: EditText
    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        siteField = field("Website — e.g. example.com", InputType.TYPE_TEXT_VARIATION_URI)
        userField = field("Username or email", InputType.TYPE_CLASS_TEXT)
        passField = field(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        passField.imeOptions = EditorInfo.IME_ACTION_DONE
        // Their keyboard's autofill has no business keeping a copy of this.
        passField.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
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
            text = "No logins saved yet. Pip will ask for one when a job needs it."
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
                    text = "The logins Pip may use, and the only place they are kept. They are " +
                        "encrypted on this phone with a key that cannot be copied off it. They " +
                        "are never sent to the AI, never put in the chat, and never leave the " +
                        "phone — Pip types them into the site itself, and you can delete any " +
                        "of them here."
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

        // Prefilled by Pip when a job ran into a sign-in, so the form is one thing to fill in
        // rather than a site to remember and type again.
        intent.getStringExtra(EXTRA_SITE)?.takeIf { it.isNotBlank() }?.let { site ->
            siteField.setText(site)
            userField.requestFocus()
        }
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
        // He says so in the bubble, so saving from the form he opened has an answer.
        OverlayService.loginSaved(site)
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
        logins.forEach { list.addView(row(it)) }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun row(login: Vault.Login): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9f), 0, dp(9f))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@VaultActivity).apply {
                    text = Vault.host(login.site)
                    textSize = 15f
                    setTextColor(getColor(R.color.text))
                }
            )
            addView(
                TextView(this@VaultActivity).apply {
                    text = login.username
                    textSize = 12f
                    setTextColor(getColor(R.color.textDim))
                }
            )
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            TextView(this).apply {
                text = "×"
                textSize = 18f
                setPadding(dp(14f), dp(2f), dp(6f), dp(2f))
                setTextColor(getColor(R.color.textDim))
                contentDescription = "Delete the login for " + Vault.host(login.site)
                setOnClickListener {
                    Vault.remove(this@VaultActivity, login.site)
                    render()
                }
            }
        )
        return row
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
