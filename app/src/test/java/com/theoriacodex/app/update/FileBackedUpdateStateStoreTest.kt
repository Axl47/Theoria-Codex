package com.theoriacodex.app.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedUpdateStateStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists ignored and remind later fields`() {
        val tempDir = tempDir("update-store-test-")
        val file = File(tempDir, "update_state.json")
        val store = FileBackedUpdateStateStore(file)

        store.setIgnoredRelease(42L)
        store.setRemindLater(42L, 123_456L)

        val reopened = FileBackedUpdateStateStore(file)
        val snapshot = reopened.snapshot()
        assertEquals(42L, snapshot.ignoredReleaseId)
        assertEquals(42L, snapshot.remindLaterReleaseId)
        assertEquals(123_456L, snapshot.remindLaterUntilEpochMs)
    }

    @Test
    fun `clear prompt deferrals resets fields`() {
        val tempDir = tempDir("update-store-test-")
        val file = File(tempDir, "update_state.json")
        val store = FileBackedUpdateStateStore(file)
        store.setIgnoredRelease(11L)
        store.setRemindLater(11L, 999L)

        store.clearPromptDeferrals()

        val snapshot = store.snapshot()
        assertNull(snapshot.ignoredReleaseId)
        assertNull(snapshot.remindLaterReleaseId)
        assertNull(snapshot.remindLaterUntilEpochMs)
    }

    @Test
    fun `persists last installed changelog`() {
        val tempDir = tempDir("update-store-test-")
        val file = File(tempDir, "update_state.json")
        val store = FileBackedUpdateStateStore(file)
        store.setLastInstalledChangelog(
            PendingPostInstallChangelog(
                releaseId = 99L,
                fromVersionCode = 100,
                versionCode = 101,
                tagName = "main-vc101-abc1234",
                commitShaShort = "abc1234",
                changelogMarkdown = "## Fixes\n- Improved startup",
                changelogSections = listOf(
                    ChangelogSection(
                        title = "Fixes",
                        bullets = listOf("Improved startup"),
                    )
                ),
            )
        )

        val reopened = FileBackedUpdateStateStore(file)
        val snapshot = reopened.snapshot()
        val changelog = snapshot.lastInstalledChangelog
        assertNotNull(changelog)
        assertEquals(99L, changelog?.releaseId)
        assertEquals(100, changelog?.fromVersionCode)
        assertEquals(101, changelog?.versionCode)
        assertEquals("abc1234", changelog?.commitShaShort)
    }

    private fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }
}
