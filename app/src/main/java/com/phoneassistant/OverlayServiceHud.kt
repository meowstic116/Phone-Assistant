package com.phoneassistant

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

// ------------------------------------------------------- floating diagnostics
//
// The diagnostics panel's windows, split out of OverlayService. These are extensions on
// the service purely to keep the state machine readable: the panel's state stays with the
// service next to the chip and the pet, and nothing here changes behaviour.

/**
 * Small, live, and over everything. The Diagnostics screen is an activity, so it can only
 * be read while it is in front — and the keyboard it is describing closes as soon as you
 * leave the app that owns it. This panel is a window of the overlay itself, so it keeps
 * reading while you type somewhere else.
 */
internal fun OverlayService.showHud() {
    if (hud != null) return
    val view = DiagHud(this).apply { onClose = { hideHud() } }
    val width = dpInt(190f)
    val params = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Touchable so it can be dragged, but never focusable: it must not take the
        // keyboard away from the app whose keyboard it is measuring. A panel opened
        // while a step is pressing starts out of the way like everything else.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (overlayDucked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Top right, where it covers the least of a keyboard in progress.
        x = (screenW - width - dpInt(10f)).coerceAtLeast(0)
        y = topInset + dpInt(10f)
    }
    view.setOnTouchListener(hudTouch)
    if (!runCatching { windowManager.addView(view, params) }.isSuccess) return
    hud = view
    hudParams = params
    if (overlayDucked) setHudDucked(true)
    hudHeld = false
    // Painted straight away, so it never appears hollow for a quarter of a second.
    renderHud()
    handler.postDelayed(hudTicker, OverlayService.HUD_REFRESH_MS)
}

internal fun OverlayService.hideHud() {
    handler.removeCallbacks(hudTicker)
    hud?.let { view -> runCatching { windowManager.removeView(view) } }
    hud = null
    hudParams = null
    // The next panel is a new window with new params, so it starts unducked.
    hudHeld = false
}

internal fun OverlayService.renderHud() {
    hud?.render(status(), ImeWatch.enabled(this), ImeWatch.lastSeenPx, ImeWatch.lastSeenAt)
}

/**
 * Dragging is measured from where the finger went down rather than by adding each move, so
 * a dropped frame cannot make the panel drift away from the finger that is holding it. A
 * tap that goes nowhere is not a click: the only clickable part is the × in the header.
 */
private val OverlayService.hudTouch: View.OnTouchListener
    get() = View.OnTouchListener { _, event ->
        val params = hudParams ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hudDownX = event.rawX
                hudDownY = event.rawY
                hudLeft = params.x
                hudTop = params.y
                hudHeld = true
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (overlayDucked) return@OnTouchListener true
                // The window has no height until it is laid out; its real one is only known
                // after the first frame.
                val height = hud?.height?.takeIf { it > 0 } ?: params.height
                params.x = (hudLeft + (event.rawX - hudDownX)).toInt()
                    .coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                params.y = (hudTop + (event.rawY - hudDownY)).toInt()
                    .coerceIn(topInset, (screenH - height - bottomInset).coerceAtLeast(topInset))
                hud?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hudHeld = false
                true
            }

            else -> false
        }
    }
