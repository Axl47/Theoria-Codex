package com.theoriacodex.data.repository

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadOnlyProfileBackupFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `read-only snapshot preserves settings bytes and detects later atomic replacements`() = runTest {
        val directory = temporary.newFolder()
        val file = File(directory, DATASTORE_SETTINGS_FILE_NAME)
        val original = Gson().toJson(SettingsDataStoreFile.fromDomain(AppSettings())).toByteArray()
        file.writeBytes(original)
        val snapshot = ReadOnlyProfileBackupFiles.capture(directory)
        snapshot.requireUnchanged()
        assertEquals(RepositoryPolicies.normalizeSettings(AppSettings()), snapshot.settings)
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(listOf(DATASTORE_SETTINGS_FILE_NAME), directory.listFiles()!!.map { it.name })
        file.writeText(Gson().toJson(SettingsDataStoreFile.fromDomain(AppSettings().copy(cache = CacheSettings(cacheFullImageOnSave = true)))))
        assertTrue(runCatching { snapshot.requireUnchanged() }.isFailure)
    }

    @Test fun `missing corrupt future and pending stores fail without quarantine or recovery writes`() = runTest {
        listOf<String?>(null, "{broken", "{\"schemaVersion\":99}").forEach { document ->
            val directory = temporary.newFolder()
            if (document != null) File(directory, DATASTORE_SETTINGS_FILE_NAME).writeText(document)
            val names = directory.listFiles()!!.map { it.name }
            assertTrue(runCatching { ReadOnlyProfileBackupFiles.capture(directory) }.isFailure)
            assertEquals(names, directory.listFiles()!!.map { it.name })
        }
        val directory = temporary.newFolder()
        File(directory, DATASTORE_SETTINGS_FILE_NAME).writeText(Gson().toJson(SettingsDataStoreFile()))
        val pending = File(directory, "pending_profile_deletion.json").apply { writeText("pending") }
        assertTrue(runCatching { ReadOnlyProfileBackupFiles.capture(directory) }.isFailure)
        assertEquals("pending", pending.readText())
    }
}
