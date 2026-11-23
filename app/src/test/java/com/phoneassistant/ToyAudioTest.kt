package com.phoneassistant

import android.app.Application
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The toy sound gates, which is where the sounds went quiet twice: the ringer rule lived in
 * a diagnostics helper that playback never consulted (so vibrate muted every toy), and the
 * enable switch was cached per process (so turning it on did nothing until a restart).
 *
 * The sounds themselves are covered by the host suite in tests/JobProgressTest.kt; what is
 * pinned here is the promise around them: plays on vibrate, silent on silent, and the switch
 * takes effect on the very next squeak.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToyAudioTest {

    private val ctx: Application
        get() = RuntimeEnvironment.getApplication()

    private fun ringer(mode: Int) {
        val audio = ctx.getSystemService(AudioManager::class.java)!!
        audio.ringerMode = mode
    }

    @Test
    fun vibratePlaysSilentDoesNot() {
        // The whole point of PR #45: vibrate is where phones live, and a toy must be heard
        // there. Silent is a deliberate choice and must stay quiet.
        ringer(AudioManager.RINGER_MODE_NORMAL)
        assertFalse("normal ringer must not mute toys", ToyAudio.ringerMuted(ctx))
        ringer(AudioManager.RINGER_MODE_VIBRATE)
        assertFalse("vibrate must not mute toys", ToyAudio.ringerMuted(ctx))
        ringer(AudioManager.RINGER_MODE_SILENT)
        assertTrue("silent must mute toys", ToyAudio.ringerMuted(ctx))
    }

    @Test
    fun summaryTellsTheTruthAboutTheRinger() {
        ringer(AudioManager.RINGER_MODE_SILENT)
        assertTrue(ToyAudio.summary(ctx).contains("silent"))
        ringer(AudioManager.RINGER_MODE_VIBRATE)
        assertFalse("vibrate is audible now, so it is not a muting state", ToyAudio.summary(ctx).contains("silent"))
    }

    @Test
    fun switchTakesEffectOnTheNextPlay() {
        // The PR #46 regression: enabled() used to cache per process start, so flipping the
        // switch in settings did nothing until the app was killed.
        assertTrue("the box starts with sounds on", ToyAudio.enabled(ctx))
        ToyAudio.setEnabled(ctx, false)
        assertFalse("off must be visible immediately", ToyAudio.enabled(ctx))
        ToyAudio.setEnabled(ctx, true)
        assertTrue("on must be visible immediately", ToyAudio.enabled(ctx))
    }

    @Test
    fun volumeIsAlwaysAudibleWhenSet() {
        assertEquals(0.7, ToyAudio.volume(ctx).toDouble(), 0.001)
        ToyAudio.setVolume(ctx, 0f)
        assertEquals("volume may be quiet but never inaudible", 0.2, ToyAudio.volume(ctx).toDouble(), 0.001)
        ToyAudio.setVolume(ctx, 5f)
        assertEquals(1.0, ToyAudio.volume(ctx).toDouble(), 0.001)
    }
}
