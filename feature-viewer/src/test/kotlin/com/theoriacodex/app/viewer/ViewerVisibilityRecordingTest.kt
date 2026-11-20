package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.testing.testPost
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ViewerVisibilityRecordingTest {
    @Test
    fun `reattached renderer cannot duplicate an admitted write or cancel it with a payload refresh`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
        val visit = visit("first")
        val commit = CompletableDeferred<Unit>()
        var writes = 0
        var finished = false
        owner.replaceSession(visit)
        owner.recordVisiblePost(visit.toViewerSessionIdentity(), visit.posts.single(), 1) { _, number, actual ->
            assertEquals(visit.sessionId, actual.sessionId)
            assertEquals(1, number)
            writes++
            commit.await()
            finished = true
        }
        runCurrent()
        assertEquals(1, writes)
        assertFalse(finished)

        owner.updateSession { it.copy(posts = it.posts.map { post -> post.copy(title = "Resolved title") }) }
        owner.recordVisiblePost(visit.toViewerSessionIdentity(), visit.posts.single(), 1) { _, _, _ -> writes++ }
        commit.complete(Unit)
        runCurrent()
        assertTrue(finished)
        assertEquals(1, writes)
    }

    @Test
    fun `a new visit to the same post counts again while stale and foreign events are rejected`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
        val first = visit("first")
        val second = visit("second")
        val recorded = mutableListOf<String>()
        owner.replaceSession(first)
        owner.recordVisiblePost(first.toViewerSessionIdentity(), first.posts.single(), 1) { _, _, session -> recorded += session.sessionId }
        runCurrent()
        owner.replaceSession(second)
        owner.recordVisiblePost(first.toViewerSessionIdentity(), first.posts.single(), 1) { _, _, _ -> error("Stale event") }
        owner.recordVisiblePost(second.toViewerSessionIdentity(), testPost(sourcePostId = "foreign"), 1) { _, _, _ -> error("Foreign post") }
        owner.recordVisiblePost(second.toViewerSessionIdentity(), second.posts.single(), 1) { _, _, session -> recorded += session.sessionId }
        runCurrent()
        owner.clearSession()
        owner.recordVisiblePost(second.toViewerSessionIdentity(), second.posts.single(), 1) { _, _, _ -> error("Cleared visit") }
        runCurrent()
        assertEquals(listOf("first", "second"), recorded)
    }

    @Test
    fun `partial best effort persistence cannot double a completed statistic on reattachment`() = runTest {
        val owner = ViewerViewModel(SavedStateHandle(), scopeOverride = this)
        val visit = visit("partial")
        var statistics = 0
        owner.replaceSession(visit)
        repeat(2) {
            owner.recordVisiblePost(visit.toViewerSessionIdentity(), visit.posts.single(), 1) { _, _, _ ->
                statistics++
                throw IOException("Recents storage unavailable after the statistic committed")
            }
            runCurrent()
        }
        assertEquals(1, statistics)
        assertEquals(visit.toViewerSessionIdentity(), owner.state.value.session)
    }

    private fun visit(id: String) = ViewerSession(
        posts = listOf(testPost(sourcePostId = "watched")),
        context = ViewerLaunchContext(queryHash = "query", startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH, scrollOffsetHint = 0),
        sessionId = id,
    )
}
