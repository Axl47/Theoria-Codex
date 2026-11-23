// Theoria's own holders, not AndroidX code. Extension binaries are compiled against `androidx.preference` and build
// their settings screen by calling these names, so the package, class and member names are fixed by those binaries.
// Nothing here draws anything: the host reads what an extension put in and shows it with the app's own rows
// (see SourceSettingsScreen). The members are the ones surveys of 300 manga and 168 anime extensions found in use, and
// no more.
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

    /** Where values are kept, from the moment the preference is added to a screen. */
    internal var store: SharedPreferences? = null
        private set

    /**
     * What AndroidX does when a preference joins a screen: from then on it reads and writes [store], and a declared
     * default is stored when nothing is yet, so the source's own code reads the value its row shows.
     */
    internal fun attach(store: SharedPreferences) {
        this.store = store
        val key = key?.takeIf { it.isNotEmpty() } ?: return
        if (!store.contains(key)) storeDefault(store.edit(), key)?.apply()
        onAttached(store, key)
    }

    /** Puts [defaultValue] under [key] when it is of the type this preference stores; null when there is none to store. */
    internal open fun storeDefault(editor: SharedPreferences.Editor, key: String): SharedPreferences.Editor? = null

    internal open fun onAttached(store: SharedPreferences, key: String) = Unit

    /** Stores [value] under [key] once on a screen, for the preferences whose value an extension can set. */
    internal fun storeString(value: String?) {
        val key = key ?: return
        store?.edit()?.putString(key, value)?.apply()
    }

    internal fun storedString(store: SharedPreferences, key: String): String? = try {
        store.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    /** Asked before a new value is stored; false refuses it. */
    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }
}

/** The screen an extension adds its settings to. */
class PreferenceScreen internal constructor(context: Context, private val values: SharedPreferences) : Preference(context) {
    internal val preferences = mutableListOf<Preference>()

    fun addPreference(preference: Preference): Boolean {
        preference.attach(values)
        return preferences.add(preference)
    }
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

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) = (defaultValue as? Boolean)?.let { editor.putBoolean(key, it) }
}

class SwitchPreferenceCompat(context: Context) : TwoStatePreference(context)

class CheckBoxPreference(context: Context) : TwoStatePreference(context)

class EditTextPreference(context: Context) : DialogPreference(context) {
    private var current: String? = null

    /**
     * The stored text, once on a screen. Setting it there stores it, which is how a change listener rewrites what was
     * typed; a text set before that is replaced by the stored one or the default, as in AndroidX.
     */
    var text: String?
        get() = current
        set(value) {
            current = value
            storeString(value)
        }

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) = (defaultValue as? String)?.let { editor.putString(key, it) }

    override fun onAttached(store: SharedPreferences, key: String) {
        current = storedString(store, key)
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
    private var current: String? = null

    /** The stored value, once on a screen; setting it there stores it, as [EditTextPreference.text] does. */
    var value: String?
        get() = current
        set(value) {
            current = value
            storeString(value)
        }

    /** Where [value] is in [entryValues], or -1: change listeners use it to find the entry that was picked. */
    fun findIndexOfValue(value: String?): Int = entryValues?.indexOfFirst { it.toString() == value } ?: -1

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) = (defaultValue as? String)?.let { editor.putString(key, it) }

    override fun onAttached(store: SharedPreferences, key: String) {
        current = storedString(store, key)
    }
}

/** Any of [entries], stored as the set of their [entryValues]. */
class MultiSelectListPreference(context: Context) : DialogPreference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) =
        (defaultValue as? Set<*>)?.let { editor.putStringSet(key, it.filterIsInstanceTo(LinkedHashSet<String>())) }
}
