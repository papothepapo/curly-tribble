package com.example.lightime.settings

import android.os.Bundle
import androidx.preference.EditTextPreference
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

        findPreference<SwitchPreferenceCompat>("dg_interim")?.isChecked = prefs.getBoolean("dg_interim", true)
        findPreference<SwitchPreferenceCompat>("auto_cap")?.isChecked = prefs.getBoolean("auto_cap", true)
    }
}
