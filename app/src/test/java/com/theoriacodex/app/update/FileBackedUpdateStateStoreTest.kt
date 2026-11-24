package com.theoriacodex.app.update

import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
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

        store.update { current ->
            current.copy(ignoredReleaseId = 42L, remindLaterReleaseId = 42L, remindLaterUntilEpochMs = 123_456L)
        }

        val reopened = FileBackedUpdateStateStore(file)
        val snapshot = reopened.snapshot()
        assertEquals(42L, snapshot.ignoredReleaseId)
        assertEquals(42L, snapshot.remindLaterReleaseId)
        assertEquals(123_456L, snapshot.remindLaterUntilEpochMs)
    }

    @Test
    fun `persists last installed changelog`() = runTest {
        val tempDir = tempDir("update-store-test-")
        val file = File(tempDir, "update_state.json")
        val store = FileBackedUpdateStateStore(file)
        val changelog = PendingPostInstallChangelog(
            releaseId = 99L,
            fromVersionCode = 100,
            versionCode = 101,
            tagName = "v1.0.1",
            commitShaShort = "abc1234",
            changelogMarkdown = "## Fixes\n- Improved startup",
            changelogSections = listOf(ChangelogSection(title = "Fixes", bullets = listOf("Improved startup"))),
        )
        store.update { current -> current.copy(lastInstalledChangelog = changelog) }

        val reopened = FileBackedUpdateStateStore(file)
        assertEquals(changelog, reopened.snapshot().lastInstalledChangelog)
    }

    @Test
    fun `corrupt snapshot is quarantined and legacy valid snapshot remains compatible`() = runTest {
        val file = File(tempDir("update-store-tolerant-test-"), "update_state.json")
        val corruptBytes = "{broken".toByteArray()
        file.writeBytes(corruptBytes)
        val registry = FileStoreRecoveryRegistry()

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
        FileBackedUpdateStateStore(file).update { current -> current.copy(lastSeenReleaseId = 800L) }
        val priorBody = file.readText()
        val store = FileBackedUpdateStateStore(file)
        store.snapshot()
        check(file.parentFile.setWritable(false))
        val failure = try {
            runCatching { store.update { current -> current.copy(ignoredReleaseId = 801L) } }.exceptionOrNull()
        } finally {
            file.parentFile.setWritable(true)
        }

        assertTrue(failure is IOException)
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
        val store = FileBackedUpdateStateStore(file)
        val caller = Job()

        val failure = runCatching {
            withContext(caller) {
                store.update { current ->
                    caller.cancel()
                    current.copy(
                        ignoredReleaseId = null,
                        remindLaterReleaseId = null,
                        remindLaterUntilEpochMs = null,
                    )
                }
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
