package com.theoriacodex.app.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBackedUpdateStateStoreTest {
    @Test
    fun `persists ignored and remind later fields`() {
        val tempDir = Files.createTempDirectory("update-store-test-").toFile()
        try {
            val file = File(tempDir, "update_state.json")
            val store = FileBackedUpdateStateStore(file)

            store.setIgnoredRelease(42L)
            store.setRemindLater(42L, 123_456L)

            val reopened = FileBackedUpdateStateStore(file)
            val snapshot = reopened.snapshot()
            assertEquals(42L, snapshot.ignoredReleaseId)
            assertEquals(42L, snapshot.remindLaterReleaseId)
            assertEquals(123_456L, snapshot.remindLaterUntilEpochMs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `clear prompt deferrals resets fields`() {
        val tempDir = Files.createTempDirectory("update-store-test-").toFile()
        try {
            val file = File(tempDir, "update_state.json")
            val store = FileBackedUpdateStateStore(file)
            store.setIgnoredRelease(11L)
            store.setRemindLater(11L, 999L)

            store.clearPromptDeferrals()

            val snapshot = store.snapshot()
            assertNull(snapshot.ignoredReleaseId)
            assertNull(snapshot.remindLaterReleaseId)
            assertNull(snapshot.remindLaterUntilEpochMs)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
