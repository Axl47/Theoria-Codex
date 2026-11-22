package com.theoriacodex.mihonhost

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FilePreferencesTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val file = MihonHost.preferencesFile(context, "source_1")

    @Test
    fun valuesOfEveryTypeAreKeptAcrossReopeningAndChangesAreAnnouncedOnTheMainThread() {
        val preferences = FilePreferences(file)
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("address", "https://server.test").putBoolean("saver", true).putInt("quality", 2)
            .putLong("since", 5_000_000_000L).putFloat("scale", 1.5f).putStringSet("languages", setOf("en", "es")).apply()
        // Posted to the main thread, not called by the writer.
        assertTrue(changed.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("address", "saver", "quality", "since", "scale", "languages"), changed.toSet())

        val reopened = FilePreferences(file)
        assertEquals("https://server.test", reopened.getString("address", null))
        assertTrue(reopened.getBoolean("saver", false))
        assertEquals(2, reopened.getInt("quality", 0))
        assertEquals(5_000_000_000L, reopened.getLong("since", 0))
        assertEquals(1.5f, reopened.getFloat("scale", 0f))
        assertEquals(setOf("en", "es"), reopened.getStringSet("languages", null))

        assertTrue(reopened.edit().remove("saver").putString("address", null).commit())
        assertFalse(FilePreferences(file).contains("saver") || FilePreferences(file).contains("address"))
    }

    @Test
    fun anEditorAppliedTwiceDoesNotReplayItsChanges() {
        val preferences = FilePreferences(file)
        val editor = preferences.edit().putString("kept", "first").clear()
        editor.apply()
        preferences.edit().putString("kept", "second").putString("added", "later").apply()
        // Neither the staged value nor the clear is applied again.
        editor.apply()
        assertEquals(mapOf("kept" to "second", "added" to "later"), preferences.all)
    }

    @Test
    fun anEditWhoseWriteFailsIsStillVisibleAndCommitSaysSo() {
        val preferences = FilePreferences(file)
        assertTrue(preferences.edit().putString("address", "old").commit())
        // The directory can no longer take the new file.
        val directory = checkNotNull(file.parentFile)
        assertTrue(directory.setWritable(false))
        try {
            assertFalse(preferences.edit().putString("address", "new").commit())
            assertEquals("new", preferences.getString("address", null))
        } finally {
            directory.setWritable(true)
        }
        assertEquals("old", FilePreferences(file).getString("address", null))
        // Memory is ahead of the file, so a commit that changes nothing still writes, and says it did.
        assertTrue(preferences.edit().commit())
        assertEquals("new", FilePreferences(file).getString("address", null))
    }

    @Test
    fun aListenerThatFailsToLinkIsReportedAndTheOthersStillHear() {
        val reported = mutableListOf<Throwable>()
        val heard = mutableListOf<String?>()
        val preferences = FilePreferences(file) { reported += it }
        val broken = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> throw NoSuchMethodError("host method removed") }
        val working = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        preferences.registerOnSharedPreferenceChangeListener(broken)
        preferences.registerOnSharedPreferenceChangeListener(working)

        preferences.edit().putBoolean("saver", true).apply()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("host method removed"), reported.map { it.message })
        assertEquals(listOf<String?>("saver"), heard)
    }

    @Test
    fun oneUnreadableEntryIsDroppedAndAnUnreadableFileIsSetAside() {
        file.parentFile?.mkdirs()
        file.writeText("""{"good":{"s":"kept"},"bad":{"x":1},"worse":7}""")
        assertEquals(mapOf("good" to "kept"), FilePreferences(file).all)

        file.writeText("not JSON")
        val preferences = FilePreferences(file)
        assertTrue(preferences.all.isEmpty())
        preferences.edit().putString("after", "written").commit()
        assertEquals("not JSON", File(file.path + ".corrupt").readText())
        assertEquals(mapOf("after" to "written"), FilePreferences(file).all)
    }

    @Test
    fun preferencesLiveWhereAndroidDoesNotBackUpAndOldFilesAreDeletedNotImported() {
        // What debug builds wrote before: a file in shared_prefs, which Android backs up.
        context.getSharedPreferences("mihon_source_9", Context.MODE_PRIVATE).edit().putString("password", "secret").commit()
        context.getSharedPreferences("theoria_other", Context.MODE_PRIVATE).edit().putString("kept", "yes").commit()

        MihonHost.deleteLegacyPreferences(context)

        assertFalse(File(context.dataDir, "shared_prefs").list().orEmpty().any { it.startsWith("mihon_") })
        assertEquals("yes", context.getSharedPreferences("theoria_other", Context.MODE_PRIVATE).getString("kept", null))
        val next = MihonHost.preferencesFile(context, "source_9")
        assertTrue(next.path.startsWith(context.noBackupFilesDir.path))
        assertTrue(FilePreferences(next).all.isEmpty())
        // A name an extension chooses cannot leave the directory.
        assertEquals(next.parentFile, MihonHost.preferencesFile(context, "../../escape").parentFile)
    }
}
