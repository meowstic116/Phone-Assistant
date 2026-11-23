package com.phoneassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The tasks menu: what Pip does on his own. Automatic tasks stand until they are deleted
 * here; scheduled tasks run once at their time and then leave the list on their own.
 */
class TasksActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        PipTasks.onChanged = { runOnUiThread(::render) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        PipTasks.onChanged = null
        super.onDestroy()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        content.removeAllViews()
        val tasks = PipTasks.all(this)
        val automatic = tasks.filter { it.kind == PipTask.Kind.AUTOMATIC }
        val scheduled = tasks.filter { it.kind == PipTask.Kind.SCHEDULED }
        heading("Automatic tasks")
        if (automatic.isEmpty()) empty("Nothing watching yet. Tell Pip: “whenever an ad shows up, skip it.”")
        automatic.forEach { content.addView(row(it, now)) }
        heading("Scheduled tasks")
        if (scheduled.isEmpty()) empty("Nothing due yet. Tell Pip: “at 5pm, remind me to stretch.”")
        scheduled.forEach { content.addView(row(it, now)) }
    }

    private fun row(task: PipTask, now: Long): View {
        val due = task.kind == PipTask.Kind.SCHEDULED
        val title = when {
            due -> task.detail
            task.app.isNotEmpty() -> "Whenever ${task.trigger} — ${task.app}"
            else -> "Whenever ${task.trigger}"
        }
        val subtitle = when {
            !due -> "Pip does: ${task.detail}"
            task.announced && task.fireAt <= now -> "Overdue — waiting for you to be back on the home screen"
            else -> "Due ${TaskClock.whenSpoken(task.fireAt, now)}"
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 16f, true))
            addView(label(subtitle, 13f, false))
        }
        val remove = Button(this).apply {
            text = "Delete"
            textSize = 12f
            setOnClickListener {
                PipTasks.remove(this@TasksActivity, task.id)
                Toast.makeText(this@TasksActivity, "Removed", Toast.LENGTH_SHORT).show()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
            addView(remove)
        }
    }

    private fun heading(title: String) {
        content.addView(label(title, 14f, true).apply {
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun empty(note: String) {
        content.addView(label(note, 13f, false).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
