package com.phoneassistant

import android.content.Context

/** Reading the speech preference never initializes a voice engine. */
internal object VoiceSettings {
    data class Config(val enabled: Boolean, val source: VoiceSource, val kokoro: KokoroPreset)

    private val retired = listOf("endpoint", "token", "wiro_key", "style", "custom")
    private fun prefs(context: Context) = context.getSharedPreferences("pip_voice", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).all["enabled"] == true

    fun drivingEnabled(context: Context) = prefs(context).getBoolean("driving_commands", false)
    fun setDrivingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("driving_commands", enabled).apply()
    }
    /**
     * "Are we still navigating?" is a read-only question, so it needs no opt-in to the command
     * set — offering it costs nothing and answers what a driver actually asks.
     */
    fun carQuestions(context: Context) = prefs(context).getBoolean("car_questions", true)
    fun setCarQuestions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("car_questions", enabled).apply()
    }
    fun carNoName(context: Context) = prefs(context).getBoolean("car_no_name", false)
    fun setCarNoName(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("car_no_name", enabled).apply()
    }
    fun drivingHome(context: Context) = prefs(context).getString("driving_home", "").orEmpty()
    fun setDrivingHome(context: Context, address: String) {
        prefs(context).edit().putString("driving_home", address.trim().take(180)).apply()
    }

    fun config(context: Context): Config {
        val prefs = prefs(context)
        val saved = prefs.all
        val source = VoiceSource.restore(saved["source"] as? String)
        if (retired.any(saved::containsKey) || ("source" in saved && saved["source"] != source.name)) {
            // Clear retired provider credentials, even if the user had selected another voice.
            prefs.edit().putString("source", source.name).apply {
                retired.forEach { remove(it) }
            }.apply()
        }
        return Config(saved["enabled"] == true, source, KokoroPreset.restore(saved["kokoro_preset"] as? String))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) PipVoice.stop()
    }

    /** How loud Pip speaks, 0–1, on top of the phone's own media volume. */
    fun volume(context: Context): Float = prefs(context).getFloat("voice_volume", 1f).coerceIn(0f, 1f)

    fun setVolume(context: Context, value: Float) {
        prefs(context).edit().putFloat("voice_volume", value.coerceIn(0f, 1f)).apply()
    }

    fun save(context: Context, config: Config) {
        PipVoice.stop()
        prefs(context).edit().putString("source", config.source.name)
            .putString("kokoro_preset", config.kokoro.name).apply()
    }
}
