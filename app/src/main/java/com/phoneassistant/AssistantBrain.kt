package com.phoneassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to any OpenAI-compatible chat completions endpoint.
 * The endpoint, key and model are supplied by the user and stored on device.
 */
object AssistantBrain {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private const val PREFS = "brain"

    // Gemini's OpenAI-compatible endpoint: a free AI Studio key works with no card.
    private const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

    // Free tier gives roughly 500 requests a day at 15/min. Gemini 3.8 Flash is
    // smarter but its free tier is only about 20 requests a day.
    private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    private val executor = Executors.newSingleThreadExecutor()

    fun config(ctx: Context): Config {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        )
    }

    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("baseUrl", baseUrl.ifBlank { DEFAULT_BASE })
            .putString("apiKey", apiKey)
            .putString("model", model.ifBlank { DEFAULT_MODEL })
            .apply()
    }

    /**
     * Replies to the last message in [Memory]. [stage] is what is on the screen right
     * now and [event] is anything that just happened because of the message. The callback
     * fires on a background thread and receives (success, text).
     */
    fun reply(
        ctx: Context,
        appLabel: String?,
        stage: String? = null,
        event: String? = null,
        turn: Long,
        onDone: (Boolean, String) -> Unit
    ) {
        val appContext = ctx.applicationContext
        val config = config(appContext)
        val history = Memory.history(appContext)

        // Pull durable facts out of the newest message before building the prompt, so
        // he can use something the user just told him in the very same reply.
        history.lastOrNull { it.role == "user" }?.let {
            Facts.rememberFromMessage(appContext, it.content)
        }

        // Whether he can actually work other apps right now decides what the prompt says
        // about his hands: promising them while the switch is off would be a lie.
        val systemPrompt = Persona.systemPrompt(
            appContext,
            appLabel,
            stage,
            event,
            canAct = Screen.available()
        )

        executor.execute {
            if (!ChatTurns.isCurrent(turn)) {
                onDone(false, "")
                return@execute
            }
            val result = try {
                request(config, systemPrompt, history)
            } catch (t: Throwable) {
                Diag.apiFailed(
                    "did not finish",
                    t.javaClass.simpleName
                )
                false to "I can't reach my brain right now. (${t.javaClass.simpleName})"
            }
            onDone(result.first, result.second)
        }
    }

    fun clearKey(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("apiKey").apply()
    }

    private fun request(
        config: Config,
        systemPrompt: String,
        history: List<Memory.Msg>
    ): Pair<Boolean, String> {
        if (config.apiKey.isBlank()) {
            Diag.apiFailed("no API key", "add one on the setup screen")
            return false to "I need an API key before I can think. Add one on the setup screen."
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        // Kept tight on purpose: short replies are the character, and a cap makes that
        // stick even when the model feels chatty.
        return send(config, messages, temperature = 0.8, maxTokens = 150)
    }

    /**
     * One completion for the agent loop, blocking on the caller's own thread so the loop can
     * stay a loop. Low temperature, because this is choosing a button, not being funny.
     */
    fun complete(
        ctx: Context,
        system: String,
        user: String,
        maxTokens: Int = 220
    ): Pair<Boolean, String> {
        val config = config(ctx.applicationContext)
        if (config.apiKey.isBlank()) {
            Diag.apiFailed("no API key", "add one on the setup screen")
            return false to "I need an API key before I can work your phone."
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return send(config, messages, temperature = 0.2, maxTokens = maxTokens)
    }

    private fun send(
        config: Config,
        messages: JSONArray,
        temperature: Double,
        maxTokens: Int
    ): Pair<Boolean, String> {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)

        val connection = (URL(endpointFor(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Do not forward an authenticated POST or its chat contents to a redirect target.
            instanceFollowRedirects = false
            // Short enough that a request which is never going to be answered does not hold a
            // job's step — and the floating character — for a minute. Answers are capped at a
            // couple of hundred tokens, so a good one is nowhere near this long.
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = when (code) {
                    in 300..399 -> "Use the provider's final API URL; redirects are disabled."
                    401, 403 -> "Check the API key and its permissions."
                    429 -> "The provider is limiting requests. Try again shortly."
                    else -> "Check the endpoint and model in Settings."
                }
                // An external error body can echo secrets or private prompt content.
                Diag.apiFailed("HTTP $code", detail)
                return false to "My brain said no ($code). $detail"
            }
            val raw = connection.inputStream.bufferedReader().use { reader ->
                buildString {
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (length + count > 256 * 1024) error("API response too large")
                        append(buffer, 0, count)
                    }
                }
            }

            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Diag.apiOk()
            return true to text.ifBlank { "(silence)" }
        } finally {
            connection.disconnect()
        }
    }

    private fun endpointFor(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions")) return trimmed
        return trimmed + "/chat/completions"
    }
}
