package com.theoriacodex.app.source

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostResolutionFlightsTest {
    private val id = PostId(SourceKey.IWARA, "1")
    private val post = Post(
        id = id,
        preview = ImageRef("https://example.test/1.jpg", null, "image/jpeg"),
        full = null,
        media = emptyList(),
        pageUrl = "https://example.test/1",
        width = 1,
        height = 1,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    @Test
    fun `overlapping callers share one request and later callers start a new one`() = runTest {
        val flights = PostResolutionFlights()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val fetch: suspend () -> Post? = { requests++; release.await(); post }

        val first = async { flights.resolve(id, fetch) }
        val second = async { flights.resolve(id, fetch) }
        runCurrent()
        release.complete(Unit)

        assertSame(post, first.await())
        assertSame(post, second.await())
        assertEquals(1, requests)
        assertSame(post, flights.resolve(id, fetch))
        assertEquals(2, requests)
    }

    @Test
    fun `the first caller leaving does not cancel the request another caller waits on`() = runTest {
        val flights = PostResolutionFlights()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val fetch: suspend () -> Post? = { requests++; release.await(); post }

        val first = launch { flights.resolve(id, fetch) }
        val second = async { flights.resolve(id, fetch) }
        runCurrent()
        first.cancel()
        runCurrent()
        release.complete(Unit)

        assertSame(post, second.await())
        assertEquals(1, requests)
    }

    @Test
    fun `the request is cancelled once every caller has left`() = runTest {
        val flights = PostResolutionFlights()
        val cancelled = CompletableDeferred<Unit>()
        val fetch: suspend () -> Post? = { try { awaitCancellation() } finally { cancelled.complete(Unit) } }

        val caller = launch { flights.resolve(id, fetch) }
        runCurrent()
        caller.cancel()
        advanceUntilIdle()

        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `a failure reaches every caller with its source failure reason`() = runTest {
        val flights = PostResolutionFlights()
        val release = CompletableDeferred<Unit>()
        val failure = SourceAdapterException(SourceFailureReason.RATE_LIMITED, "slow down")
        val fetch: suspend () -> Post? = { release.await(); throw failure }

        val callers = List(2) { async { runCatching { flights.resolve(id, fetch) }.exceptionOrNull() } }
        runCurrent()
        release.complete(Unit)

        callers.forEach {
            // Search treats a rate limit specially, so the reason must survive the hop through the shared request.
            assertEquals(SourceFailureReason.RATE_LIMITED, (it.await() as SourceAdapterException).reason)
        }
    }
}
