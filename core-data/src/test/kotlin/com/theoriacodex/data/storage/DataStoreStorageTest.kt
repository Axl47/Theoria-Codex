package com.theoriacodex.data.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `legacy archival is verified idempotent and recoverable after an interrupted copy`() {
        val directory = tempFolder.newFolder("archive-idempotence")
        val live = directory.resolve("settings_store.json")
        val archive = directory.resolve("settings_store.json.migrated-v3")
        val bytes = "legacy".toByteArray()
        live.writeBytes(bytes)
        archive.writeBytes(bytes)

        archiveVerifiedLegacyFile(live, archive)
        archiveVerifiedLegacyFile(live, archive)

        assertFalse(live.exists())
        assertArrayEquals(bytes, archive.readBytes())
    }

    @Test
    fun `legacy archival refuses to overwrite a different retained source`() {
        val directory = tempFolder.newFolder("archive-mismatch")
        val live = directory.resolve("settings_store.json")
        val archive = directory.resolve("settings_store.json.migrated-v3")
        live.writeText("live")
        archive.writeText("different")

        assertThrows(IllegalStateException::class.java) { archiveVerifiedLegacyFile(live, archive) }

        assertTrue(live.isFile)
        assertTrue(archive.isFile)
        assertTrue(archive.readText() == "different")
    }
}
