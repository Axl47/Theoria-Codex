package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.InMemoryCodexRepository
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
        val retained = testPost(sourcePostId = "retained")
        repository.addItem(codex.codexId, selected)
        repository.addItem(codex.codexId, retained)

        CodexRemovalWorkflow(repository).remove(
            codexId = codex.codexId,
            items = repository.observeCodexItems(codex.codexId).first(),
            posts = listOf(selected),
            showActionableFeedback = { message, action ->
                assertEquals("Post removed", message)
                assertEquals("Undo", action)
                false
            },
        )

        val remainingIds = repository.observeCodexItems(codex.codexId).first().map { it.postId }
        assertEquals(listOf(retained.id), remainingIds)
        assertTrue(selected.id !in remainingIds)
    }
}
