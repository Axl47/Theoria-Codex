package com.theoriacodex.app.codex

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.CodexCleaningSnapshot
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.InMemoryCodexLikesTransactions
import com.theoriacodex.data.repository.InMemoryCodexRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexCleaningWorkflowTest {
    @Test fun `clean retry captures original selections and exact Undo can retry`() = runTest {
        val repository = InMemoryCodexRepository()
        listOf("target", "source", "other").forEach { repository.ensureCodex(it, it) }
        val shared = testPost(sourcePostId = "shared")
        val keep = testPost(sourcePostId = "keep")
        repository.addItems("target", listOf(shared, keep))
        repository.addItem("source", shared)
        val original = repository.observeCodexItems("target").first()
        val stored = InMemoryCodexLikesTransactions(repository)
        var cleans = 0
        var restores = 0
        val requests = mutableListOf<CodexCleaningRequest>()
        val failing = object : CodexLikesTransactions by stored {
            override suspend fun cleanCodex(request: CodexCleaningRequest): CodexCleaningSnapshot {
                requests += request
                if (++cleans == 1) error("disk unavailable")
                return stored.cleanCodex(request)
            }
            override suspend fun restoreCleanedCodex(snapshot: CodexCleaningSnapshot) {
                if (++restores == 1) error("disk unavailable")
                stored.restoreCleanedCodex(snapshot)
            }
        }
        val owner = CodexCommandViewModel(repository, {}, {}, CodexCleaningService(failing, StandardTestDispatcher(testScheduler)), this)
        val selected = mutableSetOf("source")
        owner.clean("profile-main", "target", selected)
        selected.clear()
        selected += "other"
        runCurrent()
        assertEquals("Could not clean Codex", owner.feedback.value.single().message)
        assertEquals(original, repository.observeCodexItems("target").first())
        owner.acknowledge(owner.feedback.value.single().id, true)
        runCurrent()
        val completion = owner.feedback.value.single()
        assertEquals("1 post removed from Codex", completion.message)
        assertEquals("Undo", completion.actionLabel)
        assertEquals(listOf(keep.id), repository.observeCodexItems("target").first().map { it.postId })
        assertTrue(requests.all { it.targetId == "target" && it.comparisonIds == setOf("source") })
        owner.acknowledge(completion.id, true)
        runCurrent()
        assertEquals("Could not restore cleaned posts", owner.feedback.value.single().message)
        owner.acknowledge(owner.feedback.value.single().id, true)
        runCurrent()
        assertEquals(original.toSet(), repository.observeCodexItems("target").first().toSet())
        assertEquals("Cleaned posts restored", owner.feedback.value.single().message)
    }

    @Test fun `empty overlap reports no matches without offering Undo`() = runTest {
        val repository = InMemoryCodexRepository()
        listOf("target", "source").forEach { repository.ensureCodex(it, it) }
        val owner = CodexCommandViewModel(repository, {}, {}, CodexCleaningService(
            InMemoryCodexLikesTransactions(repository), StandardTestDispatcher(testScheduler),
        ), this)
        owner.clean("profile-main", "target", setOf("source"))
        runCurrent()
        assertEquals("No matching posts to remove", owner.feedback.value.single().message)
        assertNull(owner.feedback.value.single().action)
    }
}
