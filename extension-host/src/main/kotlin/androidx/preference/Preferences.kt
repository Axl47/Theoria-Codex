@file:Suppress("unused")

package androidx.preference

import android.content.Context
import android.content.SharedPreferences
import android.widget.EditText

open class Preference(val context: Context) {
    var key: String? = null
    var title: CharSequence? = null
    var summary: CharSequence? = null
    var defaultValue: Any? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true
    var onPreferenceChangeListener: OnPreferenceChangeListener? = null

    internal var store: SharedPreferences? = null
        private set

    internal fun attach(store: SharedPreferences) {
        this.store = store
        val key = key?.takeIf { it.isNotEmpty() } ?: return
        if (!store.contains(key)) storeDefault(store.edit(), key)?.apply()
        onAttached(store, key)
    }

    internal open fun storeDefault(editor: SharedPreferences.Editor, key: String): SharedPreferences.Editor? = null

    internal open fun onAttached(store: SharedPreferences, key: String) = Unit

    internal fun storeString(value: String?) {
        val key = key ?: return
        store?.edit()?.putString(key, value)?.apply()
    }

    internal fun storedString(store: SharedPreferences, key: String): String? = try {
        store.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }
}

class PreferenceScreen internal constructor(context: Context, private val values: SharedPreferences) : Preference(context) {
    internal val preferences = mutableListOf<Preference>()

    fun addPreference(preference: Preference): Boolean {
        preference.attach(values)
        return preferences.add(preference)
    }
}

abstract class DialogPreference(context: Context) : Preference(context) {
    var dialogTitle: CharSequence? = null
    var dialogMessage: CharSequence? = null
}

abstract class TwoStatePreference(context: Context) : Preference(context) {
    var summaryOn: CharSequence? = null
    var summaryOff: CharSequence? = null

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) = (defaultValue as? Boolean)?.let { editor.putBoolean(key, it) }
}

class SwitchPreferenceCompat(context: Context) : TwoStatePreference(context)

class CheckBoxPreference(context: Context) : TwoStatePreference(context)

class EditTextPreference(context: Context) : DialogPreference(context) {
    private var current: String? = null

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

    var onBindEditTextListener: OnBindEditTextListener? = null

    fun interface OnBindEditTextListener {
        fun onBindEditText(editText: EditText)
    }
}

class ListPreference(context: Context) : DialogPreference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
    private var current: String? = null

    var value: String?
        get() = current
        set(value) {
            current = value
            storeString(value)
        }

    fun findIndexOfValue(value: String?): Int = entryValues?.indexOfFirst { it.toString() == value } ?: -1

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) = (defaultValue as? String)?.let { editor.putString(key, it) }

    override fun onAttached(store: SharedPreferences, key: String) {
        current = storedString(store, key)
    }
}

class MultiSelectListPreference(context: Context) : DialogPreference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null

    override fun storeDefault(editor: SharedPreferences.Editor, key: String) =
        (defaultValue as? Set<*>)?.let { editor.putStringSet(key, it.filterIsInstanceTo(LinkedHashSet<String>())) }
}
