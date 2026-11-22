// Derived from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.source

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt

/**
 * A source with user settings. Mihon's `setupPreferenceScreen` is absent: this host shows no settings screen, so
 * sources run on their default preferences.
 */
interface ConfigurableSource : Source {
    fun getSourcePreferences(): SharedPreferences = sourcePreferences(preferenceKey())
}

fun ConfigurableSource.preferenceKey(): String = "source_$id"

fun ConfigurableSource.sourcePreferences(): SharedPreferences = sourcePreferences(preferenceKey())

fun sourcePreferences(key: String): SharedPreferences =
    Injekt.getInstance<Application>(Application::class.java).getSharedPreferences(key, Context.MODE_PRIVATE)
