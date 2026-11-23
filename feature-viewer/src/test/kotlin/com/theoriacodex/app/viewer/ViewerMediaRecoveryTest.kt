package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What asking the source again for an episode's expired links comes to, as the route asks through the Viewer's owner. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerMediaRecoveryTest {
    private fun episode(link: String) = ImageRef(url = null, localPath = null, mime = "video/mp4", streams = listOf(MediaStream(link, "1080p")))
    private val expired = episode("https://video.test/expired.m3u8")

    @Test
    fun `fresh links replace the episode's media`() = runTest {
        val (owner, listed) = watching()
        val fresh = episode("https://video.test/fresh.m3u8")
        val outcome = owner.recoverMedia(listed, expired) { _, stillApplies ->
            assertTrue(stillApplies())
            listed.copy(media = listOf(fresh), full = fresh, preview = fresh)
        }
        assertEquals(ViewerMediaRecovery.REPLACED, outcome)
        assertEquals(listOf(fresh), owner.state.value.pages.single().media.map { it.ref })
        owner.clearSession()
    }

    @Test
    fun `nothing new, whether no answer, a failure, the same links or too slow, leaves the media as it was`() = runTest {
        val (owner, listed) = watching()
        val answers: List<suspend () -> Post?> = listOf(
            { null },
            { error("The extension failed") },
            { listed.copy(title = "Same links") },
            { awaitCancellation() },
        )
        answers.forEach { answer ->
            assertEquals(ViewerMediaRecovery.NOTHING_NEW, owner.recoverMedia(listed, expired) { _, _ -> answer() })
        }
        assertEquals(listOf(expired), owner.state.value.pages.single().media.map { it.ref })
        owner.clearSession()
    }

    @Test
    fun `leaving the page cancels the lookup`() = runTest {
        val (owner, listed) = watching()
        val cancelled = CompletableDeferred<Unit>()
        val ask = async {
            owner.recoverMedia(listed, expired) { _, _ ->
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        runCurrent()
        ask.cancel()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        owner.clearSession()
    }

    /** A watched series of one episode whose links expired, and the post its page lists. */
    private fun TestScope.watching(): Pair<ViewerViewModel, Post> {
        val owner = testViewerOwner(SavedStateHandle(), scopeOverride = this)
        owner.replaceSession(session("watching", listOf(post("episode", SourceKey.HITOMI, listOf(expired)))).copy(series = ViewerSeries.Watching()))
        return owner to owner.state.value.pages.single().post
    }
}
