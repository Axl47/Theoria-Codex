package com.theoriacodex.app.update

import com.google.gson.GsonBuilder
import com.google.gson.JsonSerializer
import com.theoriacodex.data.storage.AtomicJsonFileStore
import com.theoriacodex.data.storage.LegacyJsonRecoveryRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedUpdateStateStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists ignored and remind later fields`() = runTest {
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
    fun `clear prompt deferrals resets fields`() = runTest {
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
    fun `persists last installed changelog`() = runTest {
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

    @Test
    fun `corrupt snapshot is quarantined and legacy valid snapshot remains compatible`() = runTest {
        val file = File(tempDir("update-store-tolerant-test-"), "update_state.json")
        val corruptBytes = "{broken".toByteArray()
        file.writeBytes(corruptBytes)
        val registry = LegacyJsonRecoveryRegistry()

        assertEquals(
            UpdateStateSnapshot(),
            FileBackedUpdateStateStore(file, recoveryRegistry = registry).snapshot(),
        )
        val recovery = registry.recoveries.value.single()
        assertEquals("Updater state", recovery.logicalStore)
        assertTrue(File(recovery.backupPath!!).readBytes().contentEquals(corruptBytes))
        assertTrue(!file.exists())

        file.writeText(
            """
            {
              "lastSeenReleaseId": 701,
              "ignoredReleaseId": 702
            }
            """.trimIndent(),
        )
        val legacy = FileBackedUpdateStateStore(file).snapshot()
        assertEquals(701L, legacy.lastSeenReleaseId)
        assertEquals(702L, legacy.ignoredReleaseId)
        assertNull(legacy.pendingInstallReleaseId)
    }

    @Test
    fun `failed atomic replacement preserves prior update state`() = runTest {
        val file = File(tempDir("update-store-failed-write-test-"), "update_state.json")
        FileBackedUpdateStateStore(file).setLastSeenReleaseId(800L)
        val priorBody = file.readText()
        val failingGson = GsonBuilder()
            .registerTypeAdapter(
                UpdateStateSnapshot::class.java,
                JsonSerializer<UpdateStateSnapshot> { _, _, _ -> error("serialization failed") },
            )
            .create()
        val store = FileBackedUpdateStateStore(
            file = file,
            fileStore = AtomicJsonFileStore(gson = failingGson),
        )

        val failure = runCatching { store.setIgnoredRelease(801L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(priorBody, file.readText())
        assertEquals(800L, store.snapshot().lastSeenReleaseId)
        assertNull(store.snapshot().ignoredReleaseId)
    }

    @Test
    fun `cancelled atomic replacement preserves the complete prior snapshot`() = runTest {
        val file = File(tempDir("update-store-cancelled-write-test-"), "update_state.json")
        val stableStore = FileBackedUpdateStateStore(file)
        stableStore.update { current ->
            current.copy(
                ignoredReleaseId = 900L,
                remindLaterReleaseId = 900L,
                remindLaterUntilEpochMs = 123_456L,
            )
        }
        val priorBody = file.readText()
        val cancellingGson = GsonBuilder()
            .registerTypeAdapter(
                UpdateStateSnapshot::class.java,
                JsonSerializer<UpdateStateSnapshot> { _, _, _ -> throw CancellationException("cancelled") },
            )
            .create()
        val store = FileBackedUpdateStateStore(
            file = file,
            fileStore = AtomicJsonFileStore(gson = cancellingGson),
        )

        val failure = runCatching {
            store.update { current ->
                current.copy(
                    ignoredReleaseId = null,
                    remindLaterReleaseId = null,
                    remindLaterUntilEpochMs = null,
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(priorBody, file.readText())
        assertEquals(900L, store.snapshot().ignoredReleaseId)
        assertEquals(900L, store.snapshot().remindLaterReleaseId)
        assertEquals(123_456L, store.snapshot().remindLaterUntilEpochMs)
    }

    private fun tempDir(prefix: String): File {
        return tempFolder.newFolder(prefix)
    }
}
