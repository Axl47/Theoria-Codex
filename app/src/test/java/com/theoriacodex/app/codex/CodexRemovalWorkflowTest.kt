package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexRepository
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
        val owner = CodexCommandViewModel(repository, {}, {}, this)
        owner.remove(codex.codexId, original, selected)
        runCurrent()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first())
        val failure = owner.feedback.value.single()
        assertEquals("Retry", failure.actionLabel)
        owner.acknowledge(failure.id, true)
        runCurrent()
        val removed = owner.feedback.value.single()
        assertEquals("Undo", removed.actionLabel)
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        owner.acknowledge(removed.id, true)
        runCurrent()
        val restoreFailure = owner.feedback.value.single()
        assertEquals("Could not restore posts", restoreFailure.message)
        assertEquals(listOf(original.last()), stored.observeCodexItems(codex.codexId).first())
        owner.acknowledge(restoreFailure.id, true)
        runCurrent()
        assertEquals(original, stored.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs })
        assertEquals(2, removes)
        assertEquals(2, restores)
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
        val owner = CodexCommandViewModel(repository, {}, {}, this)
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
        owner.acknowledge(completion.id, false)
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
        }, {}, this)
        owner.create("original-profile", "New")
        owner.delete(likesCodexIdForProfile("original-profile"), "original-profile")
        runCurrent()
        assertEquals(2, owner.feedback.value.size)
        assertTrue(owner.feedback.value.all { !it.succeeded })
        owner.feedback.value.toList().forEach { owner.acknowledge(it.id, true) }
        runCurrent()
        assertEquals(requestedIds[0], requestedIds[1])
        assertTrue(codexBelongsToProfile(requestedIds.singleOrNull() ?: requestedIds.first(), "original-profile"))
        assertEquals(listOf("original-profile", "original-profile"), profiles)
        assertFalse(owner.feedback.value.any { !it.succeeded })
    }
}
