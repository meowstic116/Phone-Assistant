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
 * Pip's way into other apps: how tall the keyboard is, and the screen he works on.
 *
 * A floating overlay window is never sent the IME inset unless the keyboard was raised for it,
 * which is why a probe window cannot see someone else's typing. This asks the accessibility
 * framework instead, which knows about every window on the display. The keyboard part reads
 * window type and bounds only.
 *
 * The rest is what [Screen] needs to do a job the user asked for: the element list of the
 * window in front, and the ability to press, type, scroll, and go back. None of that happens
 * while it is idle — see [Screen], which only reads when a job is running, and nothing is
 * kept or sent anywhere except to the model choosing the next step.
 */
class ImeWatcher : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Screen.connect(this)
        ImeWatch.measure = { refresh() }
        refresh()
    }

    /**
     * Only a window change can change which keyboard is up, or how tall it is. This service
     * is handed every event from every app on the phone, and it used to re-read the window
     * list for all of them — hundreds of times a second while a list was scrolling, each one
     * allocating a fresh window list. Anything missed here is picked up by the overlay's own
     * 400 ms re-read, which is the safety net under this.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY)) return
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Screen.recordFeedScroll(event)
            return
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            refresh()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ImeWatch.measure = null
        ImeWatch.publish(0)
        // Hands off at once: a job in flight must stop rather than act on a service that is
        // on its way out.
        Screen.disconnect(this)
        Agent.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ImeWatch.measure = null
        ImeWatch.publish(0)
        Screen.disconnect(this)
        Agent.cancel()
        super.onDestroy()
    }

    /** Reads the IME window, if the display has one. */
    private fun refresh() {
        val height = if (PipSettings.enabled(this, PipSettings.Option.ACCESSIBILITY)) {
            runCatching { imeWindowHeight() }.getOrDefault(0)
        } else 0
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

    /**
     * Measures the keyboard again, now. Events only arrive when a window changes, and a
     * keyboard caught while it was still rising reports a height it never has once it is up —
     * with no second event to correct it. The overlay calls this on its own re-read so the
     * number it walks clear of is the one on the screen.
     *
     * Direct rather than posted: the service and the overlay are one process on one thread,
     * so this is a read of the window list at the moment it is asked for. Null while the
     * service is not connected.
     */
    internal var measure: (() -> Unit)? = null

    fun probe() {
        measure?.invoke()
    }

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
