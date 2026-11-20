package com.theoriacodex.app.backup

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoBackupWorkflowTest {
    private val day = AUTO_BACKUP_INTERVAL_MS

    @Test fun `leaving the app backs up only when a day has passed`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.workflow.enable("folder", "Backups").join()
        assertEquals(1, harness.folder.files.size)

        harness.now += day - 1
        harness.workflow.onAppBackgrounded()
        assertEquals(1, harness.folder.files.size)

        harness.now += 1
        harness.workflow.onAppBackgrounded()
        assertEquals(2, harness.folder.files.size)
        assertEquals(harness.now, harness.workflow.status.value.lastSuccessEpochMs)
    }

    @Test fun `nothing runs until a folder is chosen`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.workflow.onAppBackgrounded()
        harness.workflow.backUpNow().join()
        assertEquals(0, harness.exports)
    }

    @Test fun `only the newest automatic backups are kept and other files are never touched`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.folder.files["holiday-photo.json"] = ByteArray(1)
        harness.folder.files["theoria-backup.json"] = ByteArray(1)
        harness.workflow.enable("folder", null).join()
        val first = autoBackupFileName(harness.now)
        repeat(AUTO_BACKUP_KEEP_COUNT) {
            harness.now += day
            harness.workflow.backUpNow().join()
        }
        val automatic = harness.folder.files.keys.filter { it.startsWith("theoria-auto-backup-") }
        assertEquals(AUTO_BACKUP_KEEP_COUNT, automatic.size)
        assertFalse(first in automatic)
        assertTrue("holiday-photo.json" in harness.folder.files)
        assertTrue("theoria-backup.json" in harness.folder.files)
    }

    @Test fun `a failed run keeps older backups, reports why, and retries next time`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.workflow.enable("folder", null).join()
        val kept = harness.folder.files.keys.toSet()

        harness.now += day
        harness.folder.failure = SecurityException("revoked")
        harness.workflow.onAppBackgrounded()
        assertEquals(kept, harness.folder.files.keys.toSet())
        assertEquals("Theoria lost access to the backup folder. Choose it again.", harness.workflow.status.value.failure)

        harness.folder.failure = null
        harness.workflow.onAppBackgrounded()
        assertNull(harness.workflow.status.value.failure)
        assertEquals(2, harness.folder.files.size)
    }

    @Test fun `an interrupted run's partial file is removed by the next one`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        val abandoned = autoBackupFileName(1L) + AUTO_BACKUP_PART_SUFFIX
        harness.folder.files[abandoned] = ByteArray(1)
        harness.folder.files["mine.part"] = ByteArray(1)
        harness.workflow.enable("folder", null).join()
        assertFalse(abandoned in harness.folder.files)
        assertTrue("mine.part" in harness.folder.files)
    }

    @Test fun `schedule survives a restart and turning it off clears it`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.workflow.enable("folder", "Backups").join()
        val reopened = AutoBackupWorkflow(harness.store, { harness.folder }, { ByteArray(1) }, this, { harness.now })
        assertEquals("Backups", reopened.status.value.folderName)
        assertNotNull(reopened.status.value.lastSuccessEpochMs)

        reopened.disable()
        assertFalse(harness.store.read().enabled)
    }

    private fun TestScope.harness() = Harness(this)

    private inner class Harness(scope: TestScope) {
        var now = 1_000_000L
        var exports = 0
        val folder = FakeFolder()
        val store = MemoryStore()
        val workflow = AutoBackupWorkflow(store, { folder }, { exports++; ByteArray(4) }, scope, { now })
    }

    private class MemoryStore : AutoBackupStore {
        private var value = AutoBackupStatus()
        override fun read() = value
        override fun write(status: AutoBackupStatus) { value = status }
    }

    private class FakeFolder : AutoBackupFolder {
        val files = linkedMapOf<String, ByteArray>()
        var failure: Exception? = null
        override fun names() = failure?.let { throw it } ?: files.keys.toList()
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun delete(name: String) { files.remove(name) }
    }
}
