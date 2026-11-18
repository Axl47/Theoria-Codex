package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
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
        val original = settingsFile(AppSettings()).toByteArray()
        file.writeBytes(original)
        val snapshot = ReadOnlyProfileBackupFiles.capture(directory)
        snapshot.requireUnchanged()
        assertEquals(RepositoryPolicies.normalizeSettings(AppSettings()), snapshot.settings)
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(listOf(DATASTORE_SETTINGS_FILE_NAME), directory.listFiles()!!.map { it.name })
        file.writeText(settingsFile(AppSettings().copy(cache = CacheSettings(cacheFullImageOnSave = true))))
        assertTrue(runCatching { snapshot.requireUnchanged() }.isFailure)
    }

    @Test fun `files a Gson build wrote decode without being rewritten`() = runTest {
        val directory = temporary.newFolder()
        File(directory, DATASTORE_SETTINGS_FILE_NAME).writeText(golden("datastore/settings-retired-keys.json"))
        val pins = File(directory, "pinned_searches.json").apply { writeText(goldenPinnedSearchesFile()) }
        val positions = File(directory, READING_POSITIONS_FILE_NAME).apply { writeText(golden("reading-positions/gson-written.json")) }

        val snapshot = ReadOnlyProfileBackupFiles.capture(directory)

        assertEquals(RepositoryPolicies.normalizeSettings(AppSettings(oldSearchBar = true)), snapshot.settings)
        assertEquals(goldenPins(), snapshot.savedSearches)
        assertEquals(goldenPinnedSearchesFile(), pins.readText())
        assertEquals(
            listOf(
                PostId(SourceKey.parseOrThrow("custom_safebooru_org"), "tom & jerry's <cut>=1") to 12,
                PostId(SourceKey.PIXIV, "12345") to 3,
                PostId(SourceKey.GELBOORU, "é☃") to 1,
            ),
            snapshot.readingPositions.map { it.postId to it.mediaNumber },
        )
        assertEquals(golden("reading-positions/gson-written.json"), positions.readText())
    }

    @Test fun `a missing settings file reads as the defaults the live store shows`() = runTest {
        val directory = temporary.newFolder()

        val snapshot = ReadOnlyProfileBackupFiles.capture(directory)

        assertEquals(SettingsDataStoreFile().toDomain(), snapshot.settings)
        snapshot.requireUnchanged()
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `corrupt future and pending stores fail without quarantine or recovery writes`() = runTest {
        listOf("{broken", "{\"schemaVersion\":99}").forEach { document ->
            val directory = temporary.newFolder()
            File(directory, DATASTORE_SETTINGS_FILE_NAME).writeText(document)
            val names = directory.listFiles()!!.map { it.name }
            assertTrue(runCatching { ReadOnlyProfileBackupFiles.capture(directory) }.isFailure)
            assertEquals(names, directory.listFiles()!!.map { it.name })
        }
        val directory = temporary.newFolder()
        File(directory, DATASTORE_SETTINGS_FILE_NAME).writeText(DurableJson.encodeToString(SettingsDataStoreFile.serializer(), SettingsDataStoreFile()))
        val pending = File(directory, "pending_profile_deletion.json").apply { writeText("pending") }
        assertTrue(runCatching { ReadOnlyProfileBackupFiles.capture(directory) }.isFailure)
        assertEquals("pending", pending.readText())
    }

    private fun settingsFile(settings: AppSettings) =
        DurableJson.encodeToString(SettingsDataStoreFile.serializer(), SettingsDataStoreFile.fromDomain(settings))

    private fun golden(path: String) = checkNotNull(javaClass.getResource("/golden/$path")) { "missing golden $path" }.readText()
}
