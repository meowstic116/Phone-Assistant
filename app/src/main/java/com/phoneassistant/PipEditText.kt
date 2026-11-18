package com.phoneassistant

import android.content.Context
import android.view.KeyEvent
import android.widget.EditText

/**
 * Reports the back key while the soft keyboard is up. The IME swallows that press
 * itself, so a plain EditText never sees it and the bar would linger after the
 * keyboard closes.
 */
class PipEditText(ctx: Context) : EditText(ctx) {

    /** Return true when the press was handled and should not reach the IME. */
    var onBackPressed: (() -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                return onBackPressed?.invoke() ?: false
            }
            // Swallow the down event too, so the IME does not also dismiss itself.
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
