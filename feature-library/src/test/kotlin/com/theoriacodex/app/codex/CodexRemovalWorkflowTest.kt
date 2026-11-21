package com.theoriacodex.app.codex

import com.theoriacodex.app.ui.undo.UndoAction
import com.theoriacodex.app.ui.undo.UndoSlot
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexRemovalWorkflowTest {
    @Test fun `retained reorder invokes its transaction once and acknowledges completion`() = runTest {
        val orders = mutableListOf<List<String>>()
        val owner = CodexCommandViewModel(InMemoryCodexRepository(), { null }, {}, { orders += it }, CodexCleaningService(InMemoryCodexLikesTransactions()), this)
        val submitted = mutableListOf("b", "a")
        owner.reorder(submitted)
        submitted.clear()
        runCurrent()
        assertEquals(listOf(listOf("b", "a")), orders)
        assertEquals("Codices reordered", owner.feedback.value.single().message)
        owner.acknowledge(owner.feedback.value.single().id)
        runCurrent()
        assertTrue(owner.feedback.value.isEmpty())
        assertEquals(1, orders.size)
    }

    @Test fun `a reorder's Undo restores the previous order once, without an Undo of its own`() = runTest {
        val repository = InMemoryCodexRepository()
        val first = repository.createCodex("First").codexId
        val second = repository.createCodex("Second").codexId
        val orders = mutableListOf<List<String>>()
        val owner = CodexCommandViewModel(repository, { null }, {}, { orders += it }, CodexCleaningService(InMemoryCodexLikesTransactions()), this)
        owner.reorder(listOf(second, first))
        runCurrent()
        val reordered = owner.feedback.value.single()
        assertEquals(UndoAction.Undo, reordered.undoAction)
        owner.press(reordered.id)
        runCurrent()
        assertEquals(listOf(listOf(second, first), listOf(first, second)), orders)
        val restored = owner.feedback.value.single()
        assertEquals(null, restored.undoAction)
        assertTrue(restored.quiet)
    }

    @Test
    fun `detached removal retry and Undo preserve exact memberships and unselected posts`() = runTest {
        val stored = InMemoryCodexRepository()
        val codex = stored.createCodex("Saved")
        val selected = listOf(testPost(sourcePostId = "one"), testPost(sourcePostId = "two"))
        val retained = testPost(sourcePostId = "retained")
        val original = (selected + retained).mapIndexed { index, post -> CodexItem(codex.codexId, post.id, 100L + index) }
        stored.restoreItems(original, selected + retained)
        var removes = 0
        var restores = 0
        val repository = object : CodexRepository by stored {
            override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
                if (++removes == 1) error("storage unavailable")
                stored.removeItems(codexId, postIds)
            }
            override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
                if (++restores == 1) error("storage unavailable")
                stored.restoreItems(items, posts)
            }
        }
        val owner = CodexCommandViewModel(repository, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(repository)), this)
        owner.remove(codex.codexId, original, selected)
        runCurrent()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first())
        val failure = owner.feedback.value.single()
        assertEquals(UndoAction.Retry, failure.undoAction)
        owner.press(failure.id)
        runCurrent()
        val removed = owner.feedback.value.single()
        assertEquals(UndoAction.Undo, removed.undoAction)
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        owner.press(removed.id)
        runCurrent()
        val restoreFailure = owner.feedback.value.single()
        assertEquals("Could not restore posts", restoreFailure.message)
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        owner.press(restoreFailure.id)
        runCurrent()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs })
        assertEquals(2, removes)
        assertEquals(2, restores)
    }

    @Test fun `removing one post looks up its Codex membership and can be undone`() = runTest {
        val stored = InMemoryCodexRepository()
        val codex = stored.createCodex("Saved")
        val removed = testPost(sourcePostId = "removed")
        val kept = testPost(sourcePostId = "kept")
        val original = listOf(removed, kept).mapIndexed { index, post -> CodexItem(codex.codexId, post.id, 100L + index) }
        stored.restoreItems(original, listOf(removed, kept))
        val owner = CodexCommandViewModel(stored, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(stored)), this)
        owner.removePost(codex.codexId, removed)
        runCurrent()
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        owner.press(owner.feedback.value.single().id)
        runCurrent()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs })
    }

    @Test fun `a removal with its own Undo offers no feedback and puts back exactly the membership it took`() = runTest {
        val stored = InMemoryCodexRepository()
        val codex = stored.createCodex("Saved")
        val removed = testPost(sourcePostId = "removed")
        val kept = testPost(sourcePostId = "kept")
        val original = listOf(removed, kept).mapIndexed { index, post -> CodexItem(codex.codexId, post.id, 100L + index) }
        stored.restoreItems(original, listOf(removed, kept))
        val owner = CodexCommandViewModel(stored, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(stored)), this)
        val restore = checkNotNull(owner.removeWithOwnUndo(codex.codexId, removed))
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        assertEquals(null, owner.removeWithOwnUndo(codex.codexId, removed))
        restore()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs })
        assertTrue(owner.feedback.value.isEmpty())
    }

    @Test
    fun `suspended rename and its completion survive a cancelled UI collector`() = runTest {
        val stored = InMemoryCodexRepository()
        stored.ensureCodex("captured", "Before")
        val release = CompletableDeferred<Unit>()
        val repository = object : CodexRepository by stored {
            override suspend fun renameCodex(codexId: String, name: String) {
                release.await()
                stored.renameCodex(codexId, name)
            }
        }
        val owner = CodexCommandViewModel(repository, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(repository)), this)
        val collector = backgroundScope.launch { owner.feedback.collect() }
        owner.rename("captured", "After")
        runCurrent()
        collector.cancelAndJoin()
        release.complete(Unit)
        runCurrent()
        assertEquals("After", stored.observeCodex("captured").first()?.name)
        val completion = owner.feedback.value.single()
        assertTrue(completion.succeeded)
        assertEquals(completion, owner.feedback.first().single())
        owner.acknowledge(completion.id)
        assertTrue(owner.feedback.value.isEmpty())
    }

    @Test
    fun `failed create and Likes clear retry their original profile identities`() = runTest {
        val stored = InMemoryCodexRepository()
        val requestedIds = mutableListOf<String>()
        val profiles = mutableListOf<String>()
        val repository = object : CodexRepository by stored {
            override suspend fun ensureCodex(codexId: String, name: String): com.theoriacodex.domain.model.Codex {
                requestedIds += codexId
                if (requestedIds.size == 1) error("disk full")
                return stored.ensureCodex(codexId, name)
            }
        }
        val owner = CodexCommandViewModel(repository, { profile ->
            profiles += profile
            if (profiles.size == 1) error("disk unavailable")
            null
        }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(repository)), this)
        owner.create("original-profile", "New")
        owner.delete(likesCodexIdForProfile("original-profile"), "original-profile")
        runCurrent()
        assertEquals(2, owner.feedback.value.size)
        assertTrue(owner.feedback.value.all { !it.succeeded })
        owner.feedback.value.toList().forEach { owner.press(it.id) }
        runCurrent()
        assertEquals(requestedIds[0], requestedIds[1])
        assertTrue(codexBelongsToProfile(requestedIds.singleOrNull() ?: requestedIds.first(), "original-profile"))
        assertEquals(listOf("original-profile", "original-profile"), profiles)
        assertFalse(owner.feedback.value.any { !it.succeeded })
    }

    @Test
    fun `a deleted Codex hides until the Library is left, and Undo leaves it untouched`() = runTest {
        val repository = InMemoryCodexRepository()
        val first = repository.createCodex("First")
        val second = repository.createCodex("Second")
        val post = testPost(sourcePostId = "kept")
        repository.addItem(first.codexId, post)
        val released = mutableListOf<String>()
        val owner = CodexCommandViewModel(
            repository, { null }, {}, {}, CodexCleaningService(InMemoryCodexLikesTransactions(repository)), this,
        ) { released += it }

        owner.delete(first.codexId, "profile-main", name = "First")
        assertEquals("Deleted First", owner.pendingDeletes.value[first.codexId]?.label)
        owner.actOnDelete(first.codexId)
        owner.commitDeletes()
        runCurrent()
        assertTrue(owner.pendingDeletes.value.isEmpty())
        assertEquals(listOf(post.id), repository.observeCodexItems(first.codexId).first().map { it.postId })

        owner.delete(first.codexId, "profile-main", name = "First")
        owner.rename(second.codexId, "Renamed")
        runCurrent()
        assertEquals("a newer command does not delete it", 2, repository.observeCodices().first().size)
        owner.commitDeletes()
        runCurrent()
        assertEquals(listOf(second.codexId), repository.observeCodices().first().map { it.codexId })
        assertEquals(listOf(first.codexId), released)
        assertTrue(owner.pendingDeletes.value.isEmpty())
    }

    @Test fun `a failed reorder reports its order so the grid can drop it`() = runTest {
        val owner = CodexCommandViewModel(
            InMemoryCodexRepository(), { null }, {}, { error("disk full") }, CodexCleaningService(InMemoryCodexLikesTransactions()), this,
        )
        val failures = mutableListOf<List<String>>()
        backgroundScope.launch { owner.reorderFailures.collect { failures += it } }
        runCurrent()
        owner.reorder(listOf("b", "a"))
        runCurrent()
        assertEquals(listOf(listOf("b", "a")), failures)
    }

    @Test
    fun `undoing cleared Likes restores them and clearing none offers no Undo`() = runTest {
        val repository = InMemoryCodexRepository()
        val likes = com.theoriacodex.data.repository.InMemoryLikesRepository()
        val transactions = InMemoryCodexLikesTransactions(repository, likes)
        val service = LikesCodexSyncService(transactions, repository, transactions.likes)
        val profile = com.theoriacodex.data.repository.RecommendationProfile("profile-main", "Main")
        service.ensureProfileCodex(profile)
        val owner = CodexCommandViewModel(repository, service::clearProfile, service::restoreClearedLikes, {},
            CodexCleaningService(transactions), this)

        owner.delete(likesCodexIdForProfile("profile-main"), "profile-main")
        runCurrent()
        assertEquals(null, owner.feedback.value.single().undoAction)
        owner.acknowledge(owner.feedback.value.single().id)

        val post = testPost(sourcePostId = "liked")
        service.toggle(profile, post, listOf("tag"))
        owner.delete(likesCodexIdForProfile("profile-main"), "profile-main")
        runCurrent()
        assertTrue(likes.observeLikedPostIds("profile-main").first().isEmpty())

        owner.press(owner.feedback.value.single().id)
        runCurrent()
        assertEquals(setOf(post.id), likes.observeLikedPostIds("profile-main").first())
        assertEquals(listOf(post.id), repository.observeCodexItems(likesCodexIdForProfile("profile-main")).first().map { it.postId })
    }
}

/** Presses feedback [id]'s Undo or Retry the way the app does: offered into the undo slot, then acted on there. */
internal fun CodexCommandViewModel.press(id: Long) {
    val slot = UndoSlot()
    offer(id, slot)
    slot.act(checkNotNull(slot.offer.value))
}
