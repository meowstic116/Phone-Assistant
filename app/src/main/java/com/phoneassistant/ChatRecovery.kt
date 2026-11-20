package com.phoneassistant

/** Keep a recoverable chat failure on its own turn instead of killing the overlay process. */
internal object ChatRecovery {
    const val REPLY_FAILED = "I couldn’t finish that reply. Try sending it again."

    inline fun <T> attempt(fallback: T, onFailure: (Exception) -> Unit, action: () -> T): T = try {
        action()
    } catch (e: Exception) {
        onFailure(e)
        fallback
    }
}
