package com.example.lightime.settings

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.example.lightime.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        findPreference<EditTextPreference>("dg_api_key")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val value = pref.text.orEmpty().trim()
            when {
                value.isBlank() -> "Required for live dictation"
                value.length <= 8 -> "Saved"
                else -> "Saved (${value.take(4)}...${value.takeLast(4)})"
            }
        }
        findPreference<EditTextPreference>("dg_endpointing")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val value = pref.text.orEmpty().trim().ifBlank { "300" }
            "$value ms silence timeout"
        }
        findPreference<EditTextPreference>("dg_language")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            pref.text.orEmpty().trim().ifBlank { "en-US" }
        }
        findPreference<EditTextPreference>("dg_keyterms")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val count = pref.text.orEmpty().lineSequence().map { it.trim() }.count { it.isNotEmpty() }
            if (count == 0) "No boosted terms configured" else "$count boosted term${if (count == 1) "" else "s"}"
        }
        findPreference<EditTextPreference>("custom_dictionary_words")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val count = pref.text.orEmpty().lineSequence().map { it.trim() }.count { it.isNotEmpty() }
            if (count == 0) "No custom T9 words" else "$count custom T9 word${if (count == 1) "" else "s"}"
        }
        findPreference<EditTextPreference>("text_corrections")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            countTabMappings(pref.text.orEmpty(), "No phrase corrections", "phrase correction")
        }
        findPreference<EditTextPreference>("emoji_replacements")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            countTabMappings(pref.text.orEmpty(), "No emoji replacements", "emoji replacement")
        }

        listOf(
            "mic_hardware_key",
            "keymap_backspace",
            "keymap_enter",
            "keymap_space",
            "keymap_period",
            "keymap_symbol",
            "keymap_mode_cycle",
            "spelling_mode"
        ).forEach { key ->
            findPreference<ListPreference>(key)?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        findPreference<SwitchPreferenceCompat>("dg_stt_enabled")?.isChecked = prefs.getBoolean("dg_stt_enabled", true)
        findPreference<SwitchPreferenceCompat>("dg_interim")?.isChecked = prefs.getBoolean("dg_interim", true)
        findPreference<SwitchPreferenceCompat>("auto_cap")?.isChecked = prefs.getBoolean("auto_cap", true)
        findPreference<SwitchPreferenceCompat>("t9_predictive")?.isChecked = prefs.getBoolean("t9_predictive", true)
        findPreference<SwitchPreferenceCompat>("force_hide_onscreen_t9_keypad")?.isChecked =
            prefs.getBoolean("force_hide_onscreen_t9_keypad", false)

        findPreference<Preference>("open_ime_settings")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            true
        }
        findPreference<Preference>("show_input_picker")?.setOnPreferenceClickListener {
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
            true
        }
    }

    private fun countTabMappings(raw: String, emptyLabel: String, singularLabel: String): String {
        val count = raw.lineSequence()
            .map { it.trim() }
            .count { line -> line.contains('\t') && line.substringBefore('\t').isNotBlank() }
        return when (count) {
            0 -> emptyLabel
            1 -> "1 $singularLabel"
            else -> "$count ${singularLabel}s"
        }
    }
}
