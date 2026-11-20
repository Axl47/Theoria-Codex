package com.theoriacodex.app.backup

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.data.repository.ProfileBackupPreview
import com.theoriacodex.data.repository.ProfileRestoreResult
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val actions = FakeBackupActions()
    private val documents = FakeBackupDocuments()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `selected backup waits for confirmation and survives UI recreation`() = runTest {
        val owner = owner()
        owner.import("document")
        advanceUntilIdle()

        assertEquals(0, actions.restores)
        assertEquals(preview, owner.state.value.preview)
        assertFalse(owner.state.value.applyPreferences)

        // A recreated Activity receives the same owner and preview without another document read.
        val recreatedOwner = store["backup"] as BackupViewModel
        recreatedOwner.restore()
        advanceUntilIdle()

        assertEquals(1, documents.reads)
        assertEquals(1, actions.restores)
        assertArrayEquals(documents.bytes, actions.restoredBytes)
        assertFalse(actions.appliedPreferences)
        assertNull(owner.state.value.preview)
        assertTrue(owner.effects.first().contains("2 profiles and 3 collections added"))
    }

    @Test
    fun `dismissed preview cannot restore and a fresh import resets preference consent`() = runTest {
        val owner = owner()
        owner.import("document")
        advanceUntilIdle()
        owner.applyPreferences(true)
        owner.dismissDialog()
        owner.restore()
        advanceUntilIdle()
        assertEquals(0, actions.restores)

        owner.import("another-document")
        advanceUntilIdle()
        assertFalse(owner.state.value.applyPreferences)
        owner.applyPreferences(true)
        owner.restore()
        advanceUntilIdle()
        assertTrue(actions.appliedPreferences)
    }

    @Test
    fun `invalid backup clears any prior preview and cannot reach restore`() = runTest {
        val owner = owner()
        owner.import("valid")
        advanceUntilIdle()
        documents.readFailure = IllegalArgumentException("Backup files must be 32 MiB or smaller.")

        owner.import("too-large")
        advanceUntilIdle()
        owner.restore()
        advanceUntilIdle()

        assertEquals(0, actions.restores)
        assertNull(owner.state.value.preview)
        assertFalse(owner.state.value.busy)
        assertEquals("Backup files must be 32 MiB or smaller.", owner.effects.first())
    }

    @Test
    fun `busy restore ignores repeated confirmation and preview dismissal`() = runTest {
        val owner = owner()
        actions.restoreGate = CompletableDeferred()
        owner.import("document")
        advanceUntilIdle()

        owner.restore()
        runCurrent()
        owner.restore()
        owner.dismissDialog()
        assertTrue(owner.state.value.busy)
        assertEquals(preview, owner.state.value.preview)
        assertEquals(1, actions.restores)

        actions.restoreGate?.complete(Unit)
        advanceUntilIdle()
        assertNull(owner.state.value.preview)
    }

    @Test
    fun `failed export removes its incomplete document and allows another export`() = runTest {
        val owner = owner()
        documents.writeFailure = IOException("disk full")
        owner.includeRecents(false)
        owner.export("destination")
        advanceUntilIdle()

        assertEquals(listOf("destination"), documents.discarded)
        assertFalse(actions.includedRecents)
        assertFalse(owner.state.value.busy)
        assertEquals("Could not save the backup. Please try again.", owner.effects.first())

        documents.writeFailure = null
        owner.export("new-destination")
        advanceUntilIdle()
        assertArrayEquals(actions.exportedBytes, documents.writtenBytes)
        assertEquals(listOf("destination"), documents.discarded)
        assertEquals("Backup saved.", owner.effects.first())
    }

    @Test
    fun `failed restore keeps its validated preview for retry`() = runTest {
        val owner = owner()
        owner.import("document")
        advanceUntilIdle()
        actions.restoreFailure = IOException("storage unavailable")
        owner.restore()
        advanceUntilIdle()

        assertFalse(owner.state.value.busy)
        assertEquals(preview, owner.state.value.preview)
        assertEquals("Could not restore the backup. Please try again.", owner.effects.first())

        actions.restoreFailure = null
        owner.restore()
        advanceUntilIdle()
        assertEquals(1, documents.reads)
        assertEquals(2, actions.restores)
        assertNull(owner.state.value.preview)
    }

    @Test
    fun `bounded reader accepts exact size and rejects oversized or empty streams`() = runTest {
        assertArrayEquals(byteArrayOf(1, 2, 3), readBackupDocument(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        val oversized = ByteArrayInputStream(ByteArray(100))
        try {
            readBackupDocument(oversized, 3)
            fail("Expected oversized backup rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(96, oversized.available())
        }
        try {
            readBackupDocument(ByteArrayInputStream(byteArrayOf()), 3)
            fail("Expected empty backup rejection")
        } catch (_: IllegalArgumentException) {
            // Empty files must not enter preview decoding.
        }
    }

    private fun owner() = BackupViewModel(actions, documents, dispatcher).also { store.put("backup", it) }
}

private val preview = ProfileBackupPreview(1, listOf("Main", "Art"), 3, 8, 4, 2, 7, 1)

private class FakeBackupActions : BackupActions {
    val exportedBytes = byteArrayOf(8, 9)
    var includedRecents = true
    var restores = 0
    var restoredBytes: ByteArray? = null
    var appliedPreferences = false
    var restoreGate: CompletableDeferred<Unit>? = null
    var restoreFailure: Exception? = null

    override suspend fun export(includeRecents: Boolean): ByteArray {
        includedRecents = includeRecents
        return exportedBytes
    }

    override fun preview(bytes: ByteArray) = preview

    override suspend fun restore(bytes: ByteArray, applyPreferences: Boolean): ProfileRestoreResult {
        restores++
        restoredBytes = bytes
        appliedPreferences = applyPreferences
        restoreGate?.await()
        restoreFailure?.let { throw it }
        return ProfileRestoreResult(2, 3)
    }
}

private class FakeBackupDocuments : BackupDocuments {
    val bytes = byteArrayOf(1, 2, 3)
    var reads = 0
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    val discarded = mutableListOf<String>()
    var writtenBytes: ByteArray? = null

    override suspend fun read(document: String): ByteArray {
        reads++
        readFailure?.let { throw it }
        return bytes
    }

    override suspend fun write(document: String, bytes: ByteArray) {
        writeFailure?.let { throw it }
        writtenBytes = bytes
    }

    override suspend fun discard(document: String) {
        discarded += document
    }
}
