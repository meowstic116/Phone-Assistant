package com.phoneassistant

import java.io.File
import java.security.MessageDigest

fun main(args: Array<String>) {
    val root = File(args.single())
    fun unpack(name: String, folder: String) {
        File(root, name).inputStream().buffered().use { KokoroFiles.extract(it, File(root, folder).apply { mkdir() }) }
    }
    unpack("ok.tar.bz2", "ready")
    val ready = File(root, "ready")
    check(ready.list()!!.toSet() == setOf(KokoroFiles.MODEL, KokoroFiles.VOICES, KokoroFiles.TOKENS))
    check(!File(root, "escape").exists() && !KokoroFiles.ready(ready))
    check(runCatching { unpack("link.tar.bz2", "link") }.isFailure)
    check(runCatching { unpack("missing.tar.bz2", "missing") }.isFailure)

    val bytes = "test archive".toByteArray()
    val file = File(root, "download")
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    var copied = 0L
    KokoroFiles.copyVerified(bytes.inputStream(), file, bytes.size.toLong(), hash) { copied = it }
    check(copied == bytes.size.toLong() && file.readBytes().contentEquals(bytes))
    check(runCatching { KokoroFiles.copyVerified(bytes.inputStream(), file, 2, hash) {} }.isFailure)
    check(runCatching { KokoroFiles.copyVerified(bytes.inputStream(), file, bytes.size.toLong(), "wrong") {} }.isFailure)
    var ticks = 0L
    val stalled = runCatching {
        KokoroFiles.copyVerified(bytes.inputStream(), file, bytes.size.toLong(), hash,
            budgetNanos = 2, nanoTime = { ticks++ }) { error("Timed out reads must not be written") }
    }.exceptionOrNull()
    check(stalled is java.net.SocketTimeoutException && file.length() == 0L)
    val noProgress = object : java.io.InputStream() {
        override fun read() = error("Expected a bulk read")
        override fun read(b: ByteArray, off: Int, len: Int) = 0
    }
    check(runCatching { KokoroFiles.copyVerified(noProgress, file, bytes.size.toLong(), hash) {} }
        .exceptionOrNull() is java.io.IOException)
    Thread.currentThread().interrupt()
    try {
        check(runCatching { KokoroFiles.copyVerified(bytes.inputStream(), file, bytes.size.toLong(), hash) {} }
            .exceptionOrNull() is java.io.InterruptedIOException)
    } finally { Thread.interrupted() }
    println("Kokoro archive and download verification tests passed.")
}
