package com.phoneassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Talks to any OpenAI-compatible chat completions endpoint.
 * The endpoint, key and model are supplied by the user and stored on device.
 */
object AssistantBrain {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private const val PREFS = "brain"
    private const val SETUP_VERIFIED = "setupVerified"
    private const val SETUP_COMPLETE = "setupComplete"

    // Gemini's OpenAI-compatible endpoint: a free AI Studio key works with no card.
    private const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"

    // Free tier gives roughly 500 requests a day at 15/min. Gemini 3.8 Flash is
    // smarter but its free tier is only about 20 requests a day.
    private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    private val executor = Executors.newSingleThreadExecutor()
    private val setupExecutor = Executors.newSingleThreadExecutor()

    fun config(ctx: Context): Config {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE,
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        )
    }

    fun save(ctx: Context, baseUrl: String, apiKey: String, model: String) =
        store(ctx, Config(baseUrl, apiKey, model), verified = false)

    private fun store(ctx: Context, config: Config, verified: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("baseUrl", config.baseUrl.ifBlank { DEFAULT_BASE })
            .putString("apiKey", config.apiKey)
            .putString("model", config.model.ifBlank { DEFAULT_MODEL })
            .putBoolean(SETUP_VERIFIED, verified)
            .apply()
    }

    internal fun setupComplete(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SETUP_COMPLETE, false)

    internal fun setupVerified(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SETUP_VERIFIED, false) &&
            config(ctx).apiKey.isNotBlank()

    internal fun finishSetup(ctx: Context): Boolean {
        if (!setupVerified(ctx)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SETUP_COMPLETE, true).apply()
        return true
    }

    internal fun saveVerifiedSetup(ctx: Context, config: Config) = store(ctx, config, verified = true)

    /** Tests the same endpoint and request format as Pip, with no user data in the prompt. */
    internal fun verifySetup(config: Config, done: (SetupPolicy.Check) -> Unit): Future<*> = setupExecutor.submit {
        val result = when {
            config.apiKey.isBlank() || config.model.isBlank() || config.baseUrl.isBlank() -> SetupPolicy.Check.MISSING
            !SetupPolicy.validUrl(config.baseUrl) -> SetupPolicy.Check.URL
            else -> try {
                val prompt = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK."))
                val reply = post(config, prompt, 0.2, 96, setup = true)
                val parsed = reply.body?.let { runCatching { JSONObject(it) }.getOrNull() }
                val error = parsed?.optJSONObject("error")
                val detail = error?.optJSONArray("details")
                val tag = (0 until (detail?.length() ?: 0)).asSequence()
                    .mapNotNull { detail?.optJSONObject(it)?.optString("reason")?.takeIf(String::isNotBlank) }
                    .firstOrNull(SetupPolicy::rejectedKeyTag)
                    ?: error?.optString("code")
                val completion = error == null && !completionText(parsed).isNullOrBlank()
                SetupPolicy.response(reply.code, tag, completion)
            } catch (_: java.io.IOException) {
                SetupPolicy.Check.NETWORK
            } catch (_: IllegalArgumentException) {
                SetupPolicy.Check.MISSING
            } catch (_: Exception) {
                SetupPolicy.Check.RESPONSE
            }
        }
        if (!Thread.currentThread().isInterrupted) done(result)
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
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("apiKey").remove(SETUP_VERIFIED).apply()
    }

    private fun request(
        config: Config,
        systemPrompt: String,
        history: List<Memory.Msg>
    ): Pair<Boolean, String> {
        if (config.apiKey.isBlank()) {
            Diag.apiFailed("no API key", "add one in Pip settings → API & model")
            return false to "I need an API key before I can think. Add one in Pip settings → API & model."
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
            Diag.apiFailed("no API key", "add one in Pip settings → API & model")
            return false to "I need an API key before I can work your phone. Add one in Pip settings → API & model."
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
        val response = post(config, messages, temperature, maxTokens)
        val code = response.code
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
        val text = completionText(JSONObject(checkNotNull(response.body))) ?: error("API returned no completion text")
        Diag.apiOk()
        return true to text.ifBlank { "(silence)" }
    }

    private fun completionText(json: JSONObject?): String? = json?.optJSONArray("choices")
        ?.optJSONObject(0)?.optJSONObject("message")?.opt("content")?.let { it as? String }?.trim()

    private data class HttpReply(val code: Int, val body: String?)

    private fun post(config: Config, messages: JSONArray, temperature: Double, maxTokens: Int,
                     setup: Boolean = false): HttpReply {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
        val connection = (URL(SetupPolicy.endpoint(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Never forward the authenticated POST or its contents to redirect targets.
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = if (setup) 15_000 else 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else if (setup && code == 400) {
                // Read only to recognize invalid-key error tags; never log or show provider bodies.
                connection.errorStream
            } else null
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
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
            return HttpReply(code, raw)
        } finally {
            connection.disconnect()
        }
    }
}
