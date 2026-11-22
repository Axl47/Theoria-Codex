// Theoria's own holders, not AndroidX code. Extension binaries are compiled against `androidx.preference` and build
// their settings screen by calling these names, so the package, class and member names are fixed by those binaries.
// Nothing here draws anything: the host reads what an extension put in and shows it with the app's own rows
// (see SourceSettingsScreen). The members are the ones a survey of 300 extensions found in use, and no more.
@file:Suppress("unused")

package androidx.preference

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText

/** One setting. A preference with a [key] keeps its value under that key in its source's preferences. */
open class Preference(val context: Context) {
    var key: String? = null
    var title: CharSequence? = null
    var summary: CharSequence? = null
    var defaultValue: Any? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true
    var onPreferenceChangeListener: OnPreferenceChangeListener? = null

    /** Where values are kept, once the host has taken the screen over from the extension. */
    internal var store: SharedPreferences? = null

    /** Asked before a new value is stored; false refuses it. */
    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }
}

/** The screen an extension adds its settings to. */
class PreferenceScreen(context: Context) : Preference(context) {
    internal val preferences = mutableListOf<Preference>()

    fun addPreference(preference: Preference): Boolean = preferences.add(preference)
}

/** A setting edited in a dialog. */
abstract class DialogPreference(context: Context) : Preference(context) {
    var dialogTitle: CharSequence? = null
    var dialogMessage: CharSequence? = null
}

/** An on/off setting, which can describe each state instead of having one summary. */
abstract class TwoStatePreference(context: Context) : Preference(context) {
    var summaryOn: CharSequence? = null
    var summaryOff: CharSequence? = null
}

class SwitchPreferenceCompat(context: Context) : TwoStatePreference(context)

class CheckBoxPreference(context: Context) : TwoStatePreference(context)

class EditTextPreference(context: Context) : DialogPreference(context) {
    /** Setting it on a screen the host has taken over stores it, which is how a change listener rewrites what was typed. */
    var text: String? = null
        set(value) {
            field = value
            val key = key
            if (key != null) store?.edit()?.putString(key, value)?.apply()
        }

    /** Held but never called: there is no `EditText` to bind, the app draws its own field. */
    var onBindEditTextListener: OnBindEditTextListener? = null

    fun interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }
}

/** One of [entries], stored as the value at the same position in [entryValues]. */
class ListPreference(context: Context) : DialogPreference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
}

/** Any of [entries], stored as the set of their [entryValues]. */
class MultiSelectListPreference(context: Context) : DialogPreference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
}
