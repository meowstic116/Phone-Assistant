package com.phoneassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** What Pip remembers about you between conversations. Delete anything that is wrong. */
class MemoryActivity : Activity() {

    private lateinit var factList: LinearLayout
    private lateinit var emptyLabel: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        factList = findViewById(R.id.factList)
        emptyLabel = findViewById(R.id.emptyLabel)
        input = findViewById(R.id.memoryInput)

        findViewById<Button>(R.id.rememberButton).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                Facts.add(this, text)
                input.setText("")
                render()
            }
        }

        findViewById<TextView>(R.id.forgetAllButton).setOnClickListener {
            Facts.clear(this)
            render()
            Toast.makeText(this, "Forgotten", Toast.LENGTH_SHORT).show()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val facts = Facts.all(this)
        factList.removeAllViews()
        emptyLabel.visibility = if (facts.isEmpty()) View.VISIBLE else View.GONE
        facts.forEach { fact -> factList.addView(row(fact)) }
    }

    private fun row(fact: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }

        val label = TextView(this).apply {
            text = fact
            textSize = 14f
            setTextColor(getColor(R.color.text))
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val delete = TextView(this).apply {
            text = "×"
            textSize = 18f
            setPadding(dp(14), dp(2), dp(6), dp(2))
            setTextColor(getColor(R.color.textDim))
            setOnClickListener {
                Facts.remove(this@MemoryActivity, fact)
                render()
            }
        }
        row.addView(delete)
        return row
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
