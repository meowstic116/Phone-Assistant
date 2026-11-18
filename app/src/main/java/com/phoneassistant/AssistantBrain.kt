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

        val systemPrompt = Persona.systemPrompt(appContext, appLabel, stage, event)

        executor.execute {
            val result = try {
                request(config, systemPrompt, history)
            } catch (t: Throwable) {
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
            return false to "I need an API key before I can think. Add one on the setup screen."
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.8)
            // Kept tight on purpose: short replies are the character, and a cap makes that
            // stick even when the model feels chatty.
            .put("max_tokens", 150)

        val connection = (URL(endpointFor(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val detail = raw.replace("\n", " ").take(160)
                return false to "My brain said no ($code). $detail"
            }

            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

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
