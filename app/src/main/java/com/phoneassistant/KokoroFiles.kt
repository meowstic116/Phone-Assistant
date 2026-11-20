package com.phoneassistant

import org.apache.tools.bzip2.CBZip2InputStream
import org.apache.tools.tar.TarConstants
import org.apache.tools.tar.TarInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal object KokoroFiles {
    const val PACK = "kokoro-int8-en-v0_19"
    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val LEXICON = "lexicon-en-us.txt"
    const val ARCHIVE_BYTES = 103_248_205L
    const val LEXICON_BYTES = 6_305_773L
    const val ARCHIVE_SHA = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
    const val LEXICON_SHA = "8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a"
    private val files = setOf(MODEL, VOICES, TOKENS)
    private const val MAX_EXPANDED_BYTES = 220_000_000L

    fun ready(dir: File) = files.all { File(dir, it).isFile && File(dir, it).length() > 0 } &&
        File(dir, LEXICON).length() == LEXICON_BYTES

    fun copyVerified(input: InputStream, output: File, bytes: Long, sha: String, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        output.outputStream().buffered().use { dest ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                check(total <= bytes)
                digest.update(buffer, 0, read)
                dest.write(buffer, 0, read)
                progress(total)
            }
        }
        check(total == bytes && digest.digest().joinToString("") { "%02x".format(it) } == sha)
    }

    // The archive has already been verified. Only extract the three exact model paths;
    // archive paths are never used as destination paths, and links are not followed.
    fun extract(input: InputStream, dir: File) {
        check(input.read() == 'B'.code && input.read() == 'Z'.code)
        val found = mutableSetOf<String>()
        var expanded = 0L
        TarInputStream(CBZip2InputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                expanded += entry.size
                check(entry.size >= 0 && expanded <= MAX_EXPANDED_BYTES)
                val leaf = entry.name.substringAfter("$PACK/", "")
                if (leaf !in files || entry.name != "$PACK/$leaf") continue
                check((entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM) &&
                    entry.size > 0 && found.add(leaf))
                File(dir, leaf).outputStream().buffered().use { check(tar.copyTo(it) == entry.size) }
            }
        }
        check(found == files)
    }
}
