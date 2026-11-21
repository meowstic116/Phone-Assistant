package com.phoneassistant

import java.io.IOException
import java.io.InterruptedIOException
import java.io.Reader
import java.net.SocketTimeoutException

/** Limits size and total read time before handing external text to a JSON parser. */
internal fun Reader.readBounded(
    maxChars: Int,
    budgetNanos: Long = Long.MAX_VALUE,
    nanoTime: () -> Long = System::nanoTime
): String {
    require(maxChars >= 0 && budgetNanos > 0L)
    val started = nanoTime()
    fun checkBudget() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Response read interrupted")
        if (nanoTime() - started >= budgetNanos) throw SocketTimeoutException("Response body took too long")
    }
    return buildString {
        val buffer = CharArray(4096)
        while (true) {
            checkBudget()
            val count = read(buffer)
            checkBudget()
            if (count < 0) break
            if (count == 0) throw IOException("Response reader made no progress")
            if (count > maxChars - length) throw IOException("Response exceeds size limit")
            append(buffer, 0, count)
        }
    }
}
