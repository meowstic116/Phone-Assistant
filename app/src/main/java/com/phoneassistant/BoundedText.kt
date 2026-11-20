package com.phoneassistant

import java.io.IOException
import java.io.Reader

/** Limits externally supplied text before handing it to a JSON parser. */
internal fun Reader.readBounded(maxChars: Int): String = buildString {
    val buffer = CharArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > maxChars - length) throw IOException("Response exceeds size limit")
        append(buffer, 0, count)
    }
}
