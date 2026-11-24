package com.theoriacodex.extensionhost

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.theoriacodex.domain.series.ExtensionSetting
import com.theoriacodex.domain.series.ExtensionSettingOption

class SettingsHook(val preferences: () -> SharedPreferences, val setup: (PreferenceScreen) -> Unit)

class SourceSettings(private val context: Context, private val hook: SettingsHook) {
    private val store: SharedPreferences = hook.preferences()

    fun list(): List<ExtensionSetting> = screen().mapNotNull { it.toSetting() }

    fun change(setting: ExtensionSetting): Boolean {
        val preference = screen().firstOrNull { it.key == setting.key && it.isEnabled } ?: return false
        val key = setting.key
        val editor = store.edit()
        val value: Any = when (setting) {
            is ExtensionSetting.Toggle -> setting.on.takeIf { preference is TwoStatePreference }?.also { editor.putBoolean(key, it) }
            is ExtensionSetting.Text -> setting.value.takeIf { preference is EditTextPreference }?.also { editor.putString(key, it) }
            is ExtensionSetting.Choice -> setting.value?.takeIf { preference is ListPreference }?.also { editor.putString(key, it) }
            is ExtensionSetting.MultiChoice -> setting.values.takeIf { preference is MultiSelectListPreference }?.also { editor.putStringSet(key, it) }
        } ?: return false
        if (preference.onPreferenceChangeListener?.onPreferenceChange(preference, value) == false) return false
        return editor.commit()
    }

    private fun screen(): List<Preference> {
        val screen = PreferenceScreen(context, store)
        hook.setup(screen)
        return screen.preferences.filter { it.isVisible && !it.key.isNullOrEmpty() }
    }

    private fun Preference.toSetting(): ExtensionSetting? {
        val key = key ?: return null
        val title = title?.toString().orEmpty()
        return when (this) {
            is TwoStatePreference -> {
                val on = stored(defaultValue as? Boolean ?: false) { getBoolean(key, it) }
                ExtensionSetting.Toggle(key, title, ((if (on) summaryOn else summaryOff) ?: summary)?.toString(), isEnabled, on)
            }
            is EditTextPreference -> ExtensionSetting.Text(
                key, title, summary?.toString(), isEnabled,
                value = stored(defaultValue as? String ?: "") { getString(key, it).orEmpty() },
                dialogTitle = dialogTitle?.toString(),
                dialogMessage = dialogMessage?.toString(),
            )
            is ListPreference -> {
                val options = options(entries, entryValues)
                val value = stored(defaultValue as? String) { getString(key, it) }
                val chosen = options.firstOrNull { it.value == value }?.label.orEmpty()
                ExtensionSetting.Choice(key, title, summary?.toString()?.replace("%s", chosen), isEnabled, value, options)
            }
            is MultiSelectListPreference -> ExtensionSetting.MultiChoice(
                key, title, summary?.toString(), isEnabled,
                values = stored<Set<String>>((defaultValue as? Set<*>).orEmpty().filterIsInstanceTo(LinkedHashSet())) { getStringSet(key, it).orEmpty().toSet() },
                options = options(entries, entryValues),
            )
            else -> null
        }
    }

    private fun <T> stored(default: T, read: SharedPreferences.(T) -> T): T = try {
        store.read(default)
    } catch (_: ClassCastException) {
        default
    }

    private fun options(entries: Array<CharSequence>?, values: Array<CharSequence>?): List<ExtensionSettingOption> =
        values.orEmpty().mapIndexed { index, value -> ExtensionSettingOption(value.toString(), entries?.getOrNull(index)?.toString() ?: value.toString()) }
}
