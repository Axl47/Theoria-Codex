package com.theoriacodex.data.repository

import com.theoriacodex.data.storage.AsynchronousStore
import com.theoriacodex.data.storage.DurableStorePhase
import com.theoriacodex.data.storage.FileStoreRecoveryRegistry
import com.theoriacodex.data.storage.sha256
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Each DataStore can refuse to open. Its value flows are collected while the app is still being built, so a refusal
 * must reach the startup gate through [AsynchronousStore.awaitReady] and leave those collectors waiting, not failing.
 */
class DataStoreOpenFailureTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class Opened(val store: AsynchronousStore, val values: Flow<*>)

    private val stores: Map<String, (File, CoroutineScope, FileStoreRecoveryRegistry) -> Opened> = mapOf(
        DATASTORE_SETTINGS_FILE_NAME to { directory, scope, recoveries ->
            DataStoreSettingsRepository(directory, scope, recoveries).let { Opened(it, it.observeSettings()) }
        },
        DATASTORE_STATISTICS_FILE_NAME to { directory, scope, recoveries ->
            DataStoreStatisticsRepository(directory, scope, recoveries).let { Opened(it, it.observeStatistics()) }
        },
        DATASTORE_UI_RESTORE_FILE_NAME to { directory, scope, recoveries ->
            DataStoreUiRestoreRepository(directory, scope, recoveries).let { Opened(it, it.observeViewerLaunchContext()) }
        },
        SITE_PROFILES_FILE_NAME to { directory, scope, recoveries ->
            DataStoreSiteProfileRepository(directory, scope, recoveries).let { Opened(it, it.observeProfiles()) }
        },
    )

    @Test
    fun `a store a newer build wrote fails to open, keeps its file, and opens on retry once readable`() {
        stores.forEach { (fileName, open) ->
            val directory = tempFolder.newFolder()
            val file = directory.resolve(fileName).apply { writeText("""{"schemaVersion":99}""") }
            val original = file.readBytes()

            assertRefusesThenRetries(fileName, directory, open, unblock = { file.delete() }) {
                assertArrayEquals(fileName, original, file.readBytes())
                assertTrue(fileName, directory.setAsideCopiesOf(fileName).isEmpty())
            }
        }
    }

    @Test
    fun `a store whose unreadable file cannot be set aside fails to open and keeps its file`() {
        stores.forEach { (fileName, open) ->
            val directory = tempFolder.newFolder()
            val broken = "{broken".encodeToByteArray()
            val file = directory.resolve(fileName).apply { writeBytes(broken) }
            // A directory holds the set-aside name, so the file cannot be moved there.
            val blocker = directory.resolve("$fileName.corrupt-${broken.size}-${broken.sha256()}").apply { mkdir() }

            assertRefusesThenRetries(fileName, directory, open, unblock = { blocker.delete() }) {
                assertArrayEquals(fileName, broken, file.readBytes())
            }
            assertArrayEquals(fileName, broken, directory.setAsideCopiesOf(fileName).single().readBytes())
        }
    }

    @Test
    fun `a set-aside file that no longer matches its name is left unlisted and its store still opens`() {
        stores.forEach { (fileName, open) ->
            val directory = tempFolder.newFolder()
            val named = "{set aside".encodeToByteArray()
            val damaged = directory.resolve("$fileName.corrupt-${named.size}-${named.sha256()}")
                .apply { writeText("{changed!!") }
            val intact = "{also set aside".encodeToByteArray()
            val verified = directory.resolve("$fileName.corrupt-${intact.size}-${intact.sha256()}").apply { writeBytes(intact) }
            val recoveries = FileStoreRecoveryRegistry()
            val scope = newScope()
            try {
                val opened = open(directory, scope, recoveries)

                runBlocking { withTimeout(TIMEOUT_MS) { opened.values.first() } }

                assertEquals(fileName, DurableStorePhase.READY, opened.store.storageStatus.value.phase)
                assertEquals(fileName, listOf(verified.absolutePath), recoveries.recoveries.value.map { it.backupPath })
                assertEquals(fileName, "{changed!!", damaged.readText())
            } finally {
                closeScope(scope)
            }
        }
    }

    /**
     * Opens the store at [fileName] with a collector already running, as the app container does. The collector must
     * not fail, [AsynchronousStore.awaitReady] must throw and record why, and [unchanged] holds while it is refused.
     * After [unblock], a retry on the same instance opens it and the waiting collector receives its value.
     */
    private fun assertRefusesThenRetries(
        fileName: String,
        directory: File,
        open: (File, CoroutineScope, FileStoreRecoveryRegistry) -> Opened,
        unblock: () -> Unit,
        unchanged: () -> Unit,
    ) {
        val scope = newScope()
        try {
            val opened = open(directory, scope, FileStoreRecoveryRegistry())
            val collector = scope.async { opened.values.first() }

            assertTrue(fileName, runCatching { runBlocking { opened.store.awaitReady() } }.isFailure)
            val status = opened.store.storageStatus.value
            assertEquals(fileName, DurableStorePhase.FAILED, status.phase)
            assertNotNull(fileName, status.failureReason)
            runBlocking { kotlinx.coroutines.delay(SETTLE_MS) }
            assertFalse(fileName, collector.isCompleted)
            unchanged()

            unblock()
            runBlocking { withTimeout(TIMEOUT_MS) { opened.store.awaitReady() } }
            assertEquals(fileName, DurableStorePhase.READY, opened.store.storageStatus.value.phase)
            runBlocking { withTimeout(TIMEOUT_MS) { collector.await() } }
        } finally {
            closeScope(scope)
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun closeScope(scope: CoroutineScope) = runBlocking {
        scope.cancel()
        scope.coroutineContext.job.join()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        /** Long enough for an early collector's own open attempt to fail, which used to fail the collector. */
        const val SETTLE_MS = 200L
    }
}
