package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.data.repository.CodexSortMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRemovalWorkflowTest {
    @Test
    fun `bulk removal reports its count and undo restores exact memberships`() = runTest {
        val repository = InMemoryCodexRepository()
        val codex = repository.createCodex("Saved")
        val posts = listOf(testPost(sourcePostId = "first"), testPost(sourcePostId = "second"))
        posts.forEach { post -> repository.addItem(codex.codexId, post) }
        val originalItems = repository.observeCodexItems(codex.codexId).first()
        val feedback = mutableListOf<Pair<String, String>>()

        CodexRemovalWorkflow(repository).remove(
            codexId = codex.codexId,
            items = originalItems,
            posts = posts,
            showActionableFeedback = { message, action ->
                feedback += message to action
                assertTrue(repository.observeCodexItems(codex.codexId).first().isEmpty())
                true
            },
        )

        assertEquals(listOf("2 posts removed" to "Undo"), feedback)
        assertEquals(originalItems, repository.observeCodexItems(codex.codexId).first())
        assertEquals(
            posts.map { it.id }.toSet(),
            repository.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first().map { it.id }.toSet(),
        )
    }

    @Test
    fun `dismissed bulk removal leaves selected memberships removed`() = runTest {
        val repository = InMemoryCodexRepository()
        val codex = repository.createCodex("Saved")
        val selected = testPost(sourcePostId = "selected")
        val secondSelected = testPost(sourcePostId = "second-selected")
        val retained = testPost(sourcePostId = "retained")
        repository.addItem(codex.codexId, selected)
        repository.addItem(codex.codexId, secondSelected)
        repository.addItem(codex.codexId, retained)

        CodexRemovalWorkflow(repository).remove(
            codexId = codex.codexId,
            items = repository.observeCodexItems(codex.codexId).first(),
            posts = listOf(selected, secondSelected),
            showActionableFeedback = { message, action ->
                assertEquals("2 posts removed", message)
                assertEquals("Undo", action)
                false
            },
        )

        val remainingIds = repository.observeCodexItems(codex.codexId).first().map { it.postId }
        assertEquals(listOf(retained.id), remainingIds)
        assertTrue(selected.id !in remainingIds)
    }
    @Test
    fun `bulk removal and Undo retry retain the unselected post throughout`() = runTest {
        val stored = InMemoryCodexRepository()
        val codex = stored.createCodex("Saved")
        val selected = listOf(testPost(sourcePostId = "one"), testPost(sourcePostId = "two"))
        val retained = testPost(sourcePostId = "retained")
        val original = (selected + retained).mapIndexed { index, post ->
            CodexItem(codex.codexId, post.id, savedAtEpochMs = 100L + index)
        }
        stored.restoreItems(original, selected + retained)
        var removes = 0
        var restores = 0
        val repository = object : CodexRepository by stored {
            override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
                removes++
                if (removes == 1) error("storage unavailable")
                stored.removeItems(codexId, postIds)
            }
            override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
                restores++
                if (restores == 1) error("storage unavailable")
                stored.restoreItems(items, posts)
            }
        }
        val messages = mutableListOf<Pair<String, String>>()
        CodexRemovalWorkflow(repository).remove(codex.codexId, original, selected) { message, action ->
            messages += message to action
            val expected = if (removes == 1) original else original.filter { it.postId == retained.id }
            assertEquals(expected, stored.observeCodexItems(codex.codexId).first())
            true
        }
        assertEquals(listOf("Could not remove posts" to "Retry", "2 posts removed" to "Undo",
            "Could not restore posts" to "Retry"), messages)
        assertEquals(2, removes)
        assertEquals(2, restores)
        assertEquals(original, stored.observeCodexItems(codex.codexId).first().sortedBy { it.savedAtEpochMs })
        assertEquals(
            listOf(retained, selected[1], selected[0]),
            stored.observeCodexPosts(codex.codexId, CodexSortMode.NEWEST_SAVED).first(),
        )
    }

}
