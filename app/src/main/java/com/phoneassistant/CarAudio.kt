package com.phoneassistant

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/**
 * What Android reports about car audio, and the one public way to make the car supply the
 * microphone.
 *
 * Capture normally follows Android's audio policy, which keeps a plain recording on the phone.
 * A communication route is different: Android 12+ exposes [AudioManager.setCommunicationDevice]
 * for exactly this, and older versions expose Bluetooth SCO. [Sco] asks for that route for one
 * listening session and quietly gives up, so the phone microphone keeps working when the car
 * will not take it. Nothing here is a system API and nothing needs a privileged permission.
 */
internal object CarAudio {
    data class Status(val bluetooth: Boolean, val connected: Boolean, val sco: Boolean, val device: String?)

    fun status(ctx: Context): Status {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            return Status(false, false, false, null)
        }
        val manager = ctx.getSystemService(BluetoothManager::class.java) ?: return Status(false, false, false, null)
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) return Status(true, false, false, null)
        fun devices(profile: Int) = runCatching { manager.getConnectedDevices(profile) }.getOrElse { emptyList() }
        val headsets = devices(BluetoothProfile.HEADSET)
        val media = devices(BluetoothProfile.A2DP)
        val device = (headsets + media).firstOrNull()?.let { item ->
            runCatching { item.alias ?: item.name }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        return Status(true, headsets.isNotEmpty() || media.isNotEmpty(), headsets.isNotEmpty(), device)
    }

    /** True when a hands-free link exists, so the car may be able to supply the microphone. */
    fun connected(ctx: Context): Boolean = status(ctx).connected

    fun canUseHeadset(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun summary(ctx: Context): String {
        val status = status(ctx)
        return when {
            !status.bluetooth -> "no Bluetooth radio"
            !status.connected -> "Bluetooth on, nothing connected"
            status.device != null -> "${status.device}${if (status.sco) "" else " (media only, no mic)"}"
            status.sco -> "connected hands-free device"
            else -> "connected media device"
        }
    }

    /**
     * One communication-audio route, held only while Pip listens in a car. [start] returns
     * true only when Android reports the car device as the active input; otherwise the caller
     * keeps the phone microphone.
     */
    internal class Sco(private val ctx: Context) {
        private val audio = ctx.getSystemService(AudioManager::class.java)
        private var device: AudioDeviceInfo? = null
        private var legacySco = false
        var active = false
            private set

        fun start(): Boolean {
            val manager = audio ?: return false
            if (!connected(ctx) || !canUseHeadset(ctx)) return false
            if (Build.VERSION.SDK_INT >= 31) {
                val target = runCatching {
                    manager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                }.getOrNull() ?: return false
                val accepted = runCatching { manager.setCommunicationDevice(target) }.getOrDefault(false)
                if (!accepted) return false
                device = target
            } else {
                @Suppress("DEPRECATION")
                runCatching { manager.startBluetoothSco() }.getOrElse { return false }
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = true
                legacySco = true
            }
            // The request is not proof: wait for Android to report the route as active.
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (routed(manager)) { active = true; return true }
                SystemClock.sleep(120L)
            }
            stop()
            return false
        }

        private fun routed(manager: AudioManager): Boolean = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                .getOrDefault(false)
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.isBluetoothScoOn }.getOrDefault(false)
        }

        /** Releases the route. Safe to call more than once, and never throws. */
        fun stop() {
            val manager = audio
            if (manager != null) {
                if (Build.VERSION.SDK_INT >= 31 && device != null) {
                    runCatching { manager.clearCommunicationDevice() }
                }
                if (legacySco) {
                    @Suppress("DEPRECATION")
                    runCatching { manager.stopBluetoothSco() }
                    @Suppress("DEPRECATION")
                    runCatching { manager.isBluetoothScoOn = false }
                }
            }
            device = null
            legacySco = false
            active = false
        }
    }
}
