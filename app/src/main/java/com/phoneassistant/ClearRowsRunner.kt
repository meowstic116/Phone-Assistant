package com.phoneassistant

import android.os.SystemClock

/**
 * Presses every dismissal on a screen, one at a time, and stops when the screen stops being
 * the same list.
 *
 * [ClearRows] decides *whether* a request is housekeeping and which labels are safe to press.
 * This is the part with hands: it re-reads the screen after every press, so it is never working
 * from a stale list, and it gives up the moment the screen changes into something else — a
 * confirmation dialog, a settings page, a different app. Every press is also re-verified
 * against the window it was read from, which is the same rule the agent loop uses.
 *
 * Runs on the agent's worker thread; never touches the UI.
 */
internal object ClearRowsRunner {
    /** Total presses in one job. Enough for a long list, small enough to stay a chore. */
    private const val MAX_PRESSES = 120

    /** Time for the row to leave after a press before the list is read again. */
    private const val SETTLE_MS = 450L

    /** How many times a screen with nothing left to press is scrolled before giving up. */
    private const val SCROLL_TRIES = 4

    /**
     * @param onPress called with the running count after each successful press, for the bubble.
     * @return the line to say, and the record's reason.
     */
    fun run(ctx: android.content.Context, onPress: (Int) -> Unit = {}): Pair<String, String> {
        val first = Screen.snapshot() ?: return "I couldn’t see the screen, so I didn’t press anything." to
            "no screen to sweep"
        var pressed = 0
        var screen = first
        var scrolled = 0
        // The labels on the list we started on. A sweep may only ever shrink this set.
        var baseline = labels(screen)
        while (pressed < MAX_PRESSES) {
            val target = screen.items.firstOrNull { row ->
                !row.editable && !row.password && ClearRows.isDismissal(row.label) &&
                    Screen.targetStillVisible(screen, row)
            }
            if (target == null) {
                if (scrolled >= SCROLL_TRIES) break
                scrolled++
                Screen.scroll(forward = true, item = screen.items.filter { it.scrollable }
                    .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() })
                SystemClock.sleep(SETTLE_MS)
                val next = Screen.snapshot()
                if (next == null || next.app != screen.app || next.windowId != screen.windowId) break
                if (labels(next) == labels(screen)) break // Nothing new arrived: the list is done.
                screen = next
                baseline = baseline + labels(screen)
                continue
            }
            // A press that would land on a row we have already handled is a loop, not progress.
            if (!Screen.targetStillVisible(screen, target)) {
                val refreshed = Screen.snapshot() ?: break
                if (refreshed.app != screen.app || refreshed.windowId != screen.windowId) break
                screen = refreshed
                continue
            }
            val pressedLabel = target.label
            val accepted = Screen.tap(target)
            if (!accepted) {
                // One failed press means this row's control is gone, not that the sweep is over.
                val refreshed = Screen.snapshot() ?: break
                if (refreshed.app != screen.app || refreshed.windowId != screen.windowId) break
                screen = refreshed
                continue
            }
            pressed++
            scrolled = 0
            onPress(pressed)
            SystemClock.sleep(SETTLE_MS)
            val next = Screen.snapshot() ?: return ClearRows.report(pressed, false) to
                "swept $pressed; screen stopped being readable"
            // Same app, same window, and still the same list: only then does the sweep go on.
            if (next.app != screen.app || next.windowId != screen.windowId ||
                !ClearRows.sameList(baseline, labels(next))
            ) {
                return ClearRows.stoppedEarly(pressed) to
                    "swept $pressed, then the screen changed to something else (last: $pressedLabel)"
            }
            screen = next
        }
        return ClearRows.report(pressed, more = false) to "swept $pressed dismissal rows"
    }

    /** The dismissals on a screen, as a comparable set. */
    private fun labels(screen: Screen.Snapshot): Set<String> =
        screen.items.filter { !it.editable && !it.password && ClearRows.isDismissal(it.label) }
            .map { JobProgress.normalize(it.label) }.toSet()
}
