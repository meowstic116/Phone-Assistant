package com.phoneassistant

import java.net.URI

internal object UpdatePolicy {
    private const val REPO = "https://github.com/meowstic116/Phone-Assistant/releases"
    private val version = Regex("[vV]?[0-9]+(?:\\.[0-9]+){0,3}")
    private val apkName = Regex("[A-Za-z0-9_.-]+\\.apk", RegexOption.IGNORE_CASE)

    fun isNewer(tag: String, current: String): Boolean {
        val remote = numbers(tag) ?: return false
        val local = numbers(current) ?: return false
        for (i in 0 until maxOf(remote.size, local.size)) {
            val compare = remote.getOrElse(i) { 0 }.compareTo(local.getOrElse(i) { 0 })
            if (compare != 0) return compare > 0
        }
        return false
    }

    fun releasePage(tag: String): String? = if (numbers(tag) != null) "$REPO/tag/$tag" else null

    fun apkUrl(tag: String, name: String, url: String): String? {
        if (numbers(tag) == null || !apkName.matches(name)) return null
        val expected = "$REPO/download/$tag/$name"
        return url.takeIf { it == expected && URI(it).normalize().toString() == it }
    }

    private fun numbers(raw: String): List<Int>? {
        if (raw.length > 48 || !version.matches(raw)) return null
        return raw.removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: return null }
    }
}
