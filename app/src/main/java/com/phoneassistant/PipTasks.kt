package com.phoneassistant

import android.content.Context

/**
 * The stored tasks, one line each. The tasks menu edits this and the runner reads it;
 * nothing else in the app may write task lines. Newer tasks sit in front of older ones.
 */
internal object PipTasks {
    private const val PREFS = "pip_tasks"
    private const val KEY = "lines"

    /** Called after any change, so open menus can re-render. */
    var onChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<PipTask> =
        prefs(ctx).getStringSet(KEY, emptySet()).orEmpty().mapNotNull(PipTask::decode)
            .sortedWith(compareByDescending<PipTask> { it.kind == PipTask.Kind.AUTOMATIC }
                .thenBy { it.fireAt }.thenByDescending { it.createdAt })

    fun add(ctx: Context, task: PipTask) = write(ctx) { it.add(task.encode()) }

    fun update(ctx: Context, task: PipTask) = write(ctx) {
        it.removeAll { line -> line.startsWith("${task.id}\t") }
        it.add(task.encode())
    }

    fun remove(ctx: Context, id: String) = write(ctx) {
        it.removeAll { line -> line.startsWith("$id\t") }
    }

    private fun write(ctx: Context, change: (MutableSet<String>) -> Unit) {
        val lines = prefs(ctx).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        change(lines)
        prefs(ctx).edit().putStringSet(KEY, lines).apply()
        onChanged?.invoke()
    }
}
