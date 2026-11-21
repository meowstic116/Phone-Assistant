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

    fun save(context: Context, config: Config) {
        PipVoice.stop()
        prefs(context).edit().putString("source", config.source.name)
            .putString("kokoro_preset", config.kokoro.name).apply()
    }
}
