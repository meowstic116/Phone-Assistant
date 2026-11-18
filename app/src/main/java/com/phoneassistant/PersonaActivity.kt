package com.phoneassistant

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** View and rewrite the instructions that define who Pip is. */
class PersonaActivity : Activity() {

    private lateinit var field: EditText
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_persona)

        field = findViewById(R.id.promptField)
        statusLabel = findViewById(R.id.statusLabel)

        field.setText(Persona.instructions(this))
        showStatus()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            Persona.save(this, field.text.toString())
            showStatus()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.resetButton).setOnClickListener {
            Persona.reset(this)
            field.setText(Persona.instructions(this))
            showStatus()
            Toast.makeText(this, "Back to the built-in Pip", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStatus() {
        statusLabel.text = if (Persona.isCustomised(this)) {
            "Using your custom prompt."
        } else {
            "Using the built-in prompt."
        }
    }
}
