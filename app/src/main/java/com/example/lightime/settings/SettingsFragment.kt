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
        // mirror compatibility with SettingsStore keys
        findPreference<EditTextPreference>("dg_api_key")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<EditTextPreference>("dg_endpointing")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<EditTextPreference>("dg_language")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("mic_hardware_key")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_backspace")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_enter")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_space")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_period")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_shift")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        findPreference<ListPreference>("keymap_symbol")?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

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
}
