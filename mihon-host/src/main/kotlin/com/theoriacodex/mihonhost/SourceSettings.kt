package com.theoriacodex.mihonhost

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
import eu.kanade.tachiyomi.source.ConfigurableSource

/**
 * One source's settings. The source's own code describes them by filling a [PreferenceScreen] of holder objects; this
 * reads the holders, and each setting's current value from [store], the preferences file the source itself reads.
 *
 * The screen is built anew for every call, as Mihon builds it each time its settings page opens, so a summary the
 * source computes from a stored value is current. Both calls run the source's code: call them off the main thread and
 * expect them to throw whatever that code throws.
 */
class SourceSettings(private val context: Context, private val source: ConfigurableSource, private val store: SharedPreferences) {
    /** The settings the source shows that have a key to be stored under. */
    fun list(): List<ExtensionSetting> = screen().mapNotNull { it.toSetting() }

    /**
     * Stores [setting]'s value, unless the setting is gone or disabled, or the source's change listener refuses the
     * value by returning false. Returns whether it was stored.
     */
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
        // Not apply(): the value must be on disk when this returns, the process may end right after.
        return editor.commit()
    }

    private fun screen(): List<Preference> {
        val screen = PreferenceScreen(context)
        source.setupPreferenceScreen(screen)
        return screen.preferences.onEach { it.store = store }.filter { it.isVisible && !it.key.isNullOrEmpty() }
    }

    private fun Preference.toSetting(): ExtensionSetting? {
        val key = key ?: return null
        val title = title?.toString().orEmpty()
        return when (this) {
            is TwoStatePreference -> {
                val on = stored(defaultValue as? Boolean ?: false) { getBoolean(key, it) }
                ExtensionSetting.Toggle(key, title, ((if (on) summaryOn else summaryOff) ?: summary)?.toString(), isEnabled, on)
            }
            // A text set before the screen is shown stands in for a missing default, as it does in AndroidX.
            is EditTextPreference -> ExtensionSetting.Text(
                key, title, summary?.toString(), isEnabled,
                value = stored(defaultValue as? String ?: text.orEmpty()) { getString(key, it).orEmpty() },
                dialogTitle = dialogTitle?.toString(),
                dialogMessage = dialogMessage?.toString(),
            )
            is ListPreference -> {
                val options = options(entries, entryValues)
                val value = stored(defaultValue as? String) { getString(key, it) }
                // "%s" in a list's summary stands for the chosen entry.
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

    /** The stored value, or [default] when nothing is stored or what is stored is of another type. */
    private fun <T> stored(default: T, read: SharedPreferences.(T) -> T): T = try {
        store.read(default)
    } catch (_: ClassCastException) {
        default
    }

    private fun options(entries: Array<CharSequence>?, values: Array<CharSequence>?): List<ExtensionSettingOption> =
        values.orEmpty().mapIndexed { index, value -> ExtensionSettingOption(value.toString(), entries?.getOrNull(index)?.toString() ?: value.toString()) }
}
