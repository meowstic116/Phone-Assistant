package com.phoneassistant

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Asks GitHub for the newest published release and compares it with the installed
 * version. Read-only: it never downloads anything itself, it hands back a URL.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/meowstic116/Phone-Assistant/releases/latest"

    data class Result(
        val newer: Boolean,
        val version: String,
        val currentVersion: String,
        val downloadUrl: String?,
        val pageUrl: String
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun check(ctx: Context, onDone: (Result?) -> Unit) {
        val app = ctx.applicationContext
        val current = installedVersion(app)
        executor.execute {
            val result = try {
                fetch(current)
            } catch (t: Throwable) {
                null
            }
            onDone(result)
        }
    }

    private fun fetch(current: String): Result? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects API requests that carry no user agent.
            setRequestProperty("User-Agent", "PhoneAssistant")
        }

        try {
            val code = connection.responseCode
            if (code == 404) {
                // No releases published yet — not an error worth shouting about.
                return Result(false, "", current, null, "")
            }
            if (code !in 200..299) return null

            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return null

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                        apkUrl = url
                        break
                    }
                }
            }

            return Result(
                newer = isNewer(tag, current),
                version = tag,
                currentVersion = current,
                downloadUrl = apkUrl,
                pageUrl = json.optString("html_url")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun isNewer(tag: String, current: String): Boolean {
        val remote = numbers(tag)
        val local = numbers(current)
        if (remote.isEmpty() || local.isEmpty()) return false
        for (index in 0 until maxOf(remote.size, local.size)) {
            val r = remote.getOrElse(index) { 0 }
            val l = local.getOrElse(index) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun numbers(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
        // A leading non-numeric segment means this is not a version we can compare.
        if (parts.firstOrNull() == null) return emptyList()
        return parts.mapNotNull { it }
    }
}
