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
    println("Kokoro archive and download verification tests passed.")
}
