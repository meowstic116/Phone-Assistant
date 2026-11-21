package com.phoneassistant

import java.net.URI

/** Pure onboarding rules; the API screen only unlocks after the configured model replies. */
internal object SetupPolicy {
    enum class Step { WELCOME, API, PERMISSIONS }
    enum class Check { OK, MISSING, URL, KEY, MODEL, LIMIT, PROVIDER, NETWORK, RESPONSE }

    // Shared by the initial setup and Pip settings, in display order.
    enum class Permission { NOTIFICATIONS, NOTIFICATION_ACCESS, OVERLAY, MICROPHONE, USAGE, ACCESSIBILITY }

    /** An optional replay must earn its own proof without resetting the completed install. */
    fun proofForRun(replay: Boolean, storedProof: Boolean, replayProof: Boolean): Boolean =
        if (replay) replayProof else storedProof

    fun resumeStep(saved: Step?, verified: Boolean): Step = when {
        saved == Step.PERMISSIONS && !verified -> Step.API
        saved != null -> saved
        verified -> Step.PERMISSIONS
        else -> Step.WELCOME
    }

    fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) return trimmed
        return "$trimmed/chat/completions"
    }

    fun validApiKey(key: String): Boolean = key.length in 1..4096 && key.none { it.isWhitespace() || it.isISOControl() }

    fun validUrl(baseUrl: String): Boolean = runCatching {
        val uri = URI(baseUrl)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    fun rejectedKeyTag(tag: String?) = when (tag) {
        "API_KEY_INVALID", "API_KEY_EXPIRED", "INVALID_API_KEY", "invalid_api_key" -> true
        else -> false
    }

    fun response(code: Int, errorTag: String?, hasCompletion: Boolean): Check = when {
        code in 200..299 -> if (hasCompletion) Check.OK else Check.RESPONSE
        code == 401 || code == 403 || code == 400 && rejectedKeyTag(errorTag) -> Check.KEY
        code == 429 -> Check.LIMIT
        code in 500..599 -> Check.PROVIDER
        code in 300..399 -> Check.URL
        else -> Check.MODEL
    }
}
