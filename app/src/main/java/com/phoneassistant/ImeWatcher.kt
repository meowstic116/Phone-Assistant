package com.phoneassistant

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reports how tall the keyboard is, including a keyboard opened in another app.
 *
 * A floating overlay window is never sent the IME inset unless the keyboard was raised for it,
 * which is why a probe window cannot see someone else's typing. This asks the accessibility
 * framework instead, which knows about every window on the display. It reads window type and
 * bounds only — never any window's contents, and it never acts on any event.
 */
class ImeWatcher : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        refresh()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ImeWatch.publish(0)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ImeWatch.publish(0)
        super.onDestroy()
    }

    /** Reads the IME window, if the display has one. */
    private fun refresh() {
        val height = runCatching { imeWindowHeight() }.getOrDefault(0)
        ImeWatch.publish(height)
    }

    private fun imeWindowHeight(): Int {
        val screen = resources.displayMetrics.heightPixels
        val box = Rect()
        var tallest = 0
        // getWindows() lists the windows that are on screen, so an IME that is not showing
        // simply is not in the list.
        windows.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
            window.getBoundsInScreen(box)
            // A keyboard app that takes the whole screen would leave him nowhere to stand,
            // so it is treated as no keyboard rather than as everything.
            if (box.height() > screen * 0.7f) return@forEach
            tallest = maxOf(tallest, box.height())
        }
        return tallest
    }
}

/**
 * What [ImeWatcher] last saw. The service runs on its own thread, so the callback is called
 * there and whoever listens is expected to hop to the main looper.
 */
object ImeWatch {

    /** Keyboard height in pixels, 0 when none is up or the service is not switched on. */
    @Volatile var heightPx = 0
        private set

    /**
     * The last keyboard height that was not zero, and when it was seen. A keyboard closes as
     * soon as you leave the app it was open in, so without this the number is gone by the time
     * it can be read; this keeps it until the next one.
     */
    @Volatile var lastSeenPx = 0
        private set

    @Volatile var lastSeenAt = 0L
        private set

    @Volatile var onChange: ((Int) -> Unit)? = null

    /** Whether the user has switched the service on in Settings. */
    fun enabled(ctx: Context): Boolean {
        val services = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(ctx, ImeWatcher::class.java)
        return services.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun publish(height: Int) {
        if (height > 0) {
            lastSeenPx = height
            lastSeenAt = SystemClock.elapsedRealtime()
        }
        if (height == heightPx) return
        heightPx = height
        onChange?.invoke(height)
    }
}
