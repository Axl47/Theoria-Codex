// Derived from Aniyomi (https://github.com/aniyomiorg/aniyomi), Apache License 2.0; see LICENSE-APACHE-2.0.txt in this module.
// Trimmed to what extension binaries call. Package, class and member names are fixed by those binaries.
package eu.kanade.tachiyomi.animesource

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.sourcePreferences as namedPreferences

/** An anime source with user settings, described and stored exactly as a manga source's (`ConfigurableSource`). */
interface ConfigurableAnimeSource : AnimeSource {
    fun getSourcePreferences(): SharedPreferences = namedPreferences(preferenceKey())

    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableAnimeSource.preferenceKey(): String = "source_$id"

fun ConfigurableAnimeSource.sourcePreferences(): SharedPreferences = namedPreferences(preferenceKey())

fun sourcePreferences(key: String): SharedPreferences = namedPreferences(key)
