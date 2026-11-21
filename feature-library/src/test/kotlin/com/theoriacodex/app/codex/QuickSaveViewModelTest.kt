package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModelStore
import com.theoriacodex.app.codex.transfer.CodexTransferService
import com.theoriacodex.app.testing.InMemoryCodexLikesTransactions
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCacheRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryStatisticsRepository
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.stubs.StubAdapterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickSaveViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()
    private val codices = InMemoryCodexRepository()
    private val post = testPost(sourcePostId = "one")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `quick save adds the post and records what it added`() = runTest(dispatcher) {
        codices.ensureCodex("target", "Skies")
        val owner = owner()

        val record = owner.quickSave(post, "target", fromForYou = false)

        val membership = codices.observeCodexItems("target").first().single().savedAtEpochMs
        assertEquals(QuickSaveRecord(post, "target", "Skies", inserted = 1, savedAtEpochMs = 42L, membership), record)
        assertEquals(record, owner.lastQuickSave.value)
        assertEquals(listOf(post.id), codices.observeCodexItems("target").first().map { it.postId })
    }

    @Test fun `undo removes exactly the post the last quick save added`() = runTest(dispatcher) {
        codices.ensureCodex("target", "Skies")
        val kept = testPost(sourcePostId = "kept")
        codices.addItem("target", kept)
        val owner = owner()
        owner.quickSave(post, "target", fromForYou = false)

        owner.undoLastQuickSave()
        advanceUntilIdle()

        assertNull(owner.lastQuickSave.value)
        assertEquals(listOf(kept.id), codices.observeCodexItems("target").first().map { it.postId })
    }

    @Test fun `undoing an earlier record removes only its post and keeps the newer save as the last`() = runTest(dispatcher) {
        codices.ensureCodex("target", "Skies")
        val later = testPost(sourcePostId = "later")
        val owner = owner()
        val first = requireNotNull(owner.quickSave(post, "target", fromForYou = false))
        val second = requireNotNull(owner.quickSave(later, "target", fromForYou = false))

        owner.undoQuickSave(first)
        advanceUntilIdle()

        assertEquals(second, owner.lastQuickSave.value)
        assertEquals(listOf(later.id), codices.observeCodexItems("target").first().map { it.postId })
    }

    @Test fun `undo leaves a later save of the same post to that Codex in place`() = runTest(dispatcher) {
        codices.ensureCodex("target", "Skies")
        val owner = owner()
        val record = requireNotNull(owner.quickSave(post, "target", fromForYou = false))
        codices.removeItems("target", setOf(post.id))
        val replacement = CodexItem("target", post.id, requireNotNull(record.membershipSavedAtEpochMs) + 1)
        codices.restoreItems(listOf(replacement), listOf(post))

        owner.undoQuickSave(record)

        assertEquals(listOf(replacement), codices.observeCodexItems("target").first())
    }

    @Test fun `a save that added nothing cannot be undone`() = runTest(dispatcher) {
        codices.ensureCodex("target", "Skies")
        codices.addItem("target", post)
        val owner = owner()

        val record = requireNotNull(owner.quickSave(post, "target", fromForYou = false))
        owner.undoLastQuickSave()
        advanceUntilIdle()

        assertEquals(0, record.inserted)
        assertFalse(record.undoable)
        assertEquals(record, owner.lastQuickSave.value)
        assertTrue(codices.observeCodexItems("target").first().any { it.postId == post.id })
    }

    @Test fun `a failed save returns null and records nothing`() = runTest(dispatcher) {
        val owner = owner()
        assertNull(owner.quickSave(post, "missing", fromForYou = false))
        assertNull(owner.lastQuickSave.value)
    }

    private fun owner(): QuickSaveViewModel {
        val transfer = CodexTransferService(
            codices, InMemoryCodexLikesTransactions(codices = codices), InMemoryCacheRepository(), StubAdapterRegistry(),
            workerDispatcher = dispatcher,
        )
        val saver = CodexSaver(transfer, codices, InMemoryStatisticsRepository())
        return QuickSaveViewModel(saver, codices, cacheFullImage = { false }, clock = { 42L }).also { store.put("quick", it) }
    }
}
