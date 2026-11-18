package com.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** View and rewrite the instructions that define who Pip is. */
class PersonaActivity : Activity() {

    private lateinit var field: EditText
    private lateinit var statusLabel: TextView
    private lateinit var useDefaultButton: TextView
    private lateinit var restoreButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona)

        field = findViewById(R.id.promptField)
        statusLabel = findViewById(R.id.statusLabel)
        useDefaultButton = findViewById(R.id.useDefaultButton)
        restoreButton = findViewById(R.id.restoreButton)

        field.setText(Persona.instructions(this))
        showStatus()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            Persona.save(this, field.text.toString())
            field.setText(Persona.instructions(this))
            showStatus()
            val saved = if (Persona.isCustomised(this)) {
                "Saved"
            } else {
                "Back to the built-in prompt"
            }
            Toast.makeText(this, saved, Toast.LENGTH_SHORT).show()
        }

        useDefaultButton.setOnClickListener { askToAdoptDefault() }

        restoreButton.setOnClickListener {
            if (Persona.restoreCustom(this)) {
                field.setText(Persona.instructions(this))
                showStatus()
                Toast.makeText(this, "Your own text is back", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The built-in prompt is rewritten with new versions, but a saved prompt replaces it
     * completely, so it is worth asking before swapping one for the other — even though
     * the swap keeps their text.
     */
    private fun askToAdoptDefault() {
        AlertDialog.Builder(this)
            .setTitle("Use the built-in personality?")
            .setMessage(
                "This loads the current built-in prompt, which gets updated with new " +
                    "versions. Whatever you wrote is kept — \"bring my text back\" puts it " +
                    "back whenever you like."
            )
            .setPositiveButton("Use it") { _, _ -> adoptDefault() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun adoptDefault() {
        // Text typed but not yet saved goes into the backup too, so this never throws away
        // work in the box.
        val current = field.text.toString().trim()
        if (current.isNotEmpty() && current != Persona.DEFAULT_INSTRUCTIONS) {
            Persona.save(this, current)
        }
        Persona.reset(this)
        field.setText(Persona.instructions(this))
        showStatus()
        Toast.makeText(
            this,
            "Using the built-in prompt. Your text was kept.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showStatus() {
        val custom = Persona.isCustomised(this)
        statusLabel.text = if (custom) {
            "Using your own prompt. The built-in one — including any personality updates " +
                "— is not applied while this is saved."
        } else {
            "Using the built-in prompt, updated with each new version."
        }
        useDefaultButton.visibility = if (custom) View.VISIBLE else View.GONE
        restoreButton.visibility = if (Persona.hasSavedText(this)) View.VISIBLE else View.GONE
    }
}
