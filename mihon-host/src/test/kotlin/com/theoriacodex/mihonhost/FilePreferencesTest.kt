package com.theoriacodex.mihonhost

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FilePreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun valuesOfEveryTypeAreKeptAcrossReopeningAndChangesAreAnnounced() {
        val file = MihonHost.preferencesFile(context, "source_1")
        val preferences = FilePreferences(file)
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("address", "https://server.test").putBoolean("saver", true).putInt("quality", 2)
            .putLong("since", 5_000_000_000L).putFloat("scale", 1.5f).putStringSet("languages", setOf("en", "es")).apply()

        val reopened = FilePreferences(file)
        assertEquals("https://server.test", reopened.getString("address", null))
        assertTrue(reopened.getBoolean("saver", false))
        assertEquals(2, reopened.getInt("quality", 0))
        assertEquals(5_000_000_000L, reopened.getLong("since", 0))
        assertEquals(1.5f, reopened.getFloat("scale", 0f))
        assertEquals(setOf("en", "es"), reopened.getStringSet("languages", null))
        assertEquals(setOf("address", "saver", "quality", "since", "scale", "languages"), changed.toSet())

        reopened.edit().remove("saver").putString("address", null).commit()
        assertFalse(FilePreferences(file).contains("saver") || FilePreferences(file).contains("address"))
        assertEquals("fallback", FilePreferences(file).getString("address", "fallback"))
    }

    @Test
    fun preferencesLiveWhereAndroidDoesNotBackUpAndOldOnesMoveThere() {
        // What the app wrote before: a file in shared_prefs, which Android backs up.
        context.getSharedPreferences("mihon_source_9", Context.MODE_PRIVATE).edit()
            .putString("password", "secret").putBoolean("saver", true).commit()
        context.getSharedPreferences("theoria_other", Context.MODE_PRIVATE).edit().putString("kept", "yes").commit()

        MihonHost.moveLegacyPreferences(context)

        val file = MihonHost.preferencesFile(context, "source_9")
        assertTrue(file.path.startsWith(context.noBackupFilesDir.path))
        assertEquals(mapOf("password" to "secret", "saver" to true), FilePreferences(file).all)
        val sharedPreferences = File(context.dataDir, "shared_prefs").list().orEmpty().toList()
        assertFalse(sharedPreferences.any { it.startsWith("mihon_") })
        assertEquals("yes", context.getSharedPreferences("theoria_other", Context.MODE_PRIVATE).getString("kept", null))
        // A name an extension chooses cannot leave the directory.
        assertEquals(file.parentFile, MihonHost.preferencesFile(context, "../../escape").parentFile)
    }
}
