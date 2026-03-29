package com.example.lightime.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun apiKey(): String = prefs.getString("dg_api_key", "") ?: ""
    fun language(): String = prefs.getString("dg_language", "en-US") ?: "en-US"
    fun endpointingMs(): Int = (prefs.getString("dg_endpointing", "300") ?: "300").toIntOrNull() ?: 300
    fun interimEnabled(): Boolean = prefs.getBoolean("dg_interim", true)
    fun autoCapEnabled(): Boolean = prefs.getBoolean("auto_cap", true)
    fun micHardwareKey(): String = prefs.getString("mic_hardware_key", "CALL") ?: "CALL"
    fun predictiveT9Enabled(): Boolean = prefs.getBoolean("t9_predictive", true)
    fun forceHideOnscreenT9Keypad(): Boolean = prefs.getBoolean("force_hide_onscreen_t9_keypad", false)
    fun backspaceHardwareKey(): String = prefs.getString("keymap_backspace", "DEL") ?: "DEL"
    fun enterHardwareKey(): String = prefs.getString("keymap_enter", "ENTER") ?: "ENTER"
    fun spaceHardwareKey(): String = prefs.getString("keymap_space", "KEY_0") ?: "KEY_0"
    fun punctuationHardwareKey(): String = prefs.getString("keymap_period", "KEY_1") ?: "KEY_1"
    fun shiftHardwareKey(): String = prefs.getString("keymap_shift", "POUND") ?: "POUND"
    fun symbolHardwareKey(): String = prefs.getString("keymap_symbol", "STAR") ?: "STAR"

    fun keyterms(): List<String> = decodeList(prefs.getString("dg_keyterms", "") ?: "")
    fun correctionsMap(): Map<String, String> = decodeMap(prefs.getString("text_corrections", "") ?: "")
    fun emojiMap(): Map<String, String> = decodeMap(prefs.getString("emoji_replacements", "") ?: "")
    fun spellingMode(): Int = (prefs.getString("spelling_mode", "1") ?: "1").toIntOrNull() ?: 1

    private fun decodeList(raw: String): List<String> = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun decodeMap(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.split('\n').forEach { line ->
            val split = line.split('\t', limit = 2)
            if (split.size == 2 && split[0].isNotBlank()) out[split[0].trim()] = split[1].trim()
        }
        return out
    }
}
