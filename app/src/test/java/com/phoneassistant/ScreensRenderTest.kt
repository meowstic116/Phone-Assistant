package com.phoneassistant

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings screen is assembled in code, and a control attached in two places crashed
 * it twice ("The specified child already has a parent"). `tests/run-settings-layout.sh`
 * guards the source text; these tests build the screens for real on every CI run and
 * would have caught both crashes. Each screen must build through its real lifecycle and
 * leave a consistent view tree behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensRenderTest {

    /** A view with two parents is rejected at attach time; this catches a detached one. */
    private fun assertTreeConsistent(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                assertNotNull("child $i of ${root.javaClass.simpleName} is detached", child)
                assertTrue(
                    "child $i of ${root.javaClass.simpleName} has a foreign parent",
                    child.parent === root
                )
                assertTreeConsistent(child)
            }
        }
    }

    private fun <T : Activity> build(activity: Class<T>): T {
        val started = Robolectric.buildActivity(activity).setup().get()
        assertTreeConsistent(started.window.decorView)
        return started
    }

    /** Every button label on the screen, depth-first. */
    private fun buttonLabels(root: View, into: MutableList<String> = mutableListOf()): MutableList<String> {
        if (root is Button) into.add(root.text?.toString().orEmpty())
        if (root is ViewGroup) for (i in 0 until root.childCount) buttonLabels(root.getChildAt(i), into)
        return into
    }

    /** Every text label on the screen, depth-first. */
    private fun allTexts(root: View, into: MutableList<String> = mutableListOf()): MutableList<String> {
        if (root is TextView) into.add(root.text?.toString().orEmpty())
        if (root is ViewGroup) for (i in 0 until root.childCount) allTexts(root.getChildAt(i), into)
        return into
    }

    /** How many views match [match], actually attached, depth-first. */
    private fun countViews(root: View, match: (View) -> Boolean): Int {
        var count = if (match(root)) 1 else 0
        if (root is ViewGroup) for (i in 0 until root.childCount) count += countViews(root.getChildAt(i), match)
        return count
    }

    @Test
    fun settingsScreenBuildsWithoutCrashing() {
        val activity = build(PipSettingsActivity::class.java)
        // The settings builder catches its own crashes and swaps in a failure screen, so a
        // crash would not fail this test by itself. The real screen's scroll container is
        // the proof that nothing was thrown while assembling the rows.
        assertNotNull(
            "settings fell back to its build-failure screen",
            activity.findViewById<View>(R.id.pipSettingsScroll)
        )
        // Controls that were built but never attached vanished from the screen while the
        // code stayed green — three of them once. The screen must actually contain them.
        val labels = buttonLabels(activity.window.decorView)
        assertTrue("Download Kokoro button is missing from settings", "Download Kokoro" in labels)
        assertTrue("Speak now button is missing from settings", "Speak now (no wake word)" in labels)
        assertTrue(
            "the recognition download / listening button is missing from settings",
            labels.any {
                it in setOf("Download offline recognition (57 MB)", "Start listening for Pip",
                    "Stop listening for Pip", "Downloading offline recognition…",
                    "Offline listening unavailable on this device")
            }
        )
        // Built even when hidden (nothing to remove yet) — attached, so it cannot vanish.
        assertTrue(
            "Remove offline recognition download button is missing from settings",
            "Remove offline recognition download" in labels
        )
        // Volume controls: both sliders must be real rows, not just remembered handles.
        val texts = allTexts(activity.window.decorView)
        assertTrue("toy volume slider is missing", texts.any { it.startsWith("Toy sound volume: ") })
        assertTrue("voice volume slider is missing", texts.any { it.startsWith("Pip’s voice volume: ") })
        assertTrue("expected two volume sliders", countViews(activity.window.decorView) { it is SeekBar } >= 2)
    }

    @Test
    fun onboardingScreenBuildsWithoutCrashing() {
        build(OnboardingActivity::class.java)
    }

    @Test
    fun memoryScreenBuildsWithoutCrashing() {
        build(MemoryActivity::class.java)
    }

    @Test
    fun personaScreenBuildsWithoutCrashing() {
        build(PersonaActivity::class.java)
    }

    @Test
    fun vaultScreenBuildsWithoutCrashing() {
        // The Keystore is absent under Robolectric, which exercises the exact path the
        // README promises: a vault that cannot be opened is left untouched and explained.
        build(VaultActivity::class.java)
    }

    @Test
    fun chatHistoryScreenBuildsWithoutCrashing() {
        build(ChatHistoryActivity::class.java)
    }

    @Test
    fun tasksMenuBuildsWithBothLists() {
        val activity = build(TasksActivity::class.java)
        val texts = allTexts(activity.window.decorView)
        assertTrue("automatic tasks section is missing", "Automatic tasks" in texts)
        assertTrue("scheduled tasks section is missing", "Scheduled tasks" in texts)
    }
}
