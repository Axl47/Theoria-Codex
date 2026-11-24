package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.viewer.ViewerOwnerTestData.image
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.queue
import com.theoriacodex.app.viewer.ViewerOwnerTestData.request
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the Viewer owner schedules neighbor prefetch: waiting, decoding, skipping and concurrency. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelPrefetchTest {
    @Test
    fun `neighbors wait for the first media on screen, or until the wait runs out`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                started += media.key
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        owner.replaceSession(session("prefetch-gate", listOf(post("gallery", media = (0..3).map { image("$it.jpg") }))))
        val keys = owner.state.value.currentPage?.media.orEmpty().map { media -> media.key }

        owner.onAction(queue(keys[1]))
        runCurrent()
        assertTrue(started.isEmpty())
        owner.onAction(ViewerAction.MediaReady(requireNotNull(owner.state.value.session), keys[0], 0L))
        runCurrent()
        assertEquals(listOf(keys[1]), started)
        // Later pages do not wait for the page on screen.
        owner.onAction(ViewerAction.SelectMedia(2))
        owner.onAction(queue(keys[3]))
        runCurrent()
        assertEquals(listOf(keys[1], keys[3]), started)

        owner.replaceSession(session("prefetch-gate-slow", listOf(post("slow", media = (0..1).map { image("slow-$it.jpg") }))))
        val slow = owner.state.value.currentPage?.media.orEmpty().last().key
        owner.onAction(queue(slow))
        advanceTimeBy(VIEWER_PREFETCH_READY_WAIT_MS - 1)
        runCurrent()
        assertTrue(slow !in started)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(slow, started.last())
    }

    @Test
    fun `decoding a downloaded image does not wait for a download slot, and happens once`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, request ->
                started += media.key
                if (media.key.mediaIndex == 3) ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, decoded = request.distance <= 1) else awaitCancellation()
            },
            scopeOverride = this,
        )
        owner.replaceSession(session("decode-slot", listOf(post("gallery", media = (0..3).map { image("$it.jpg") }))))
        val session = requireNotNull(owner.state.value.session)
        val keys = owner.state.value.currentPage?.media.orEmpty().map { media -> media.key }
        owner.onAction(ViewerAction.MediaReady(session, keys[0], 0L))
        owner.onAction(queue(keys[3], distance = 3))
        runCurrent()
        // Downloaded while far away, it is done until it comes close enough to decode.
        owner.onAction(queue(keys[3], distance = 3))
        runCurrent()
        assertEquals(listOf(keys[3]), started)

        val close = ViewerPrefetchRequest(keys[3], distance = 1, decode = true)
        owner.onAction(ViewerAction.QueuePrefetch(listOf(request(keys[1]), request(keys[2]), close)))
        runCurrent()
        assertEquals(listOf(keys[3], keys[1], keys[2], keys[3]), started)

        owner.onAction(ViewerAction.QueuePrefetch(listOf(close)))
        runCurrent()
        assertEquals(4, started.size)
        owner.onAction(ViewerAction.QueuePrefetch(emptyList()))
        runCurrent()
    }

    @Test
    fun `prefetch skips unknown, repeated and settled media and forgets what a resolution replaced`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                started += media.key
                ViewerPrefetchResult(if (media.key.mediaIndex == 1) ViewerPrefetchOutcome.SKIPPED else ViewerPrefetchOutcome.FAILED)
            },
            scopeOverride = this,
        )
        val gallery = post("gallery", media = (0..2).map { image("$it.jpg") })
        owner.replaceSession(session("ledger", listOf(gallery)))
        val session = requireNotNull(owner.state.value.session)
        val (k0, k1, k2) = owner.state.value.currentPage?.media.orEmpty().map { media -> media.key }
        owner.onAction(ViewerAction.MediaReady(session, k0, 0L))
        val unknownPost = com.theoriacodex.app.viewer.state.ViewerMediaKey(PostId(SourceKey.PIXIV, "gone"), 0)
        val unknownIndex = com.theoriacodex.app.viewer.state.ViewerMediaKey(gallery.id, 7)

        owner.onAction(queue(k1, unknownPost, k1, k2, unknownIndex))
        advanceUntilIdle()
        assertEquals(listOf(k1, k2), started)
        owner.onAction(queue(k1, k2))
        advanceUntilIdle()
        assertEquals(listOf(k1, k2), started)

        owner.applyResolvedPost(gallery.copy(title = "Resolved"))
        owner.onAction(queue(k1))
        advanceUntilIdle()
        assertEquals(listOf(k1, k2, k1), started)
    }

    @Test
    fun `prefetch limits active work and cancels neighbors that are no longer desired`() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val startedKeys = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val cancelledKeys = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                val current = active.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, current) }
                synchronized(startedKeys) { startedKeys += media.key }
                try {
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                    synchronized(cancelledKeys) { cancelledKeys += media.key }
                }
            },
            scopeOverride = this,
        )
        owner.replaceSession(
            session(
                "prefetch-window",
                listOf(post("gallery", media = (0..5).map { index -> image("$index.jpg") })),
            ),
        )
        val keys = owner.state.value.currentPage?.media.orEmpty().map { media -> media.key }
        owner.onAction(ViewerAction.MediaReady(requireNotNull(owner.state.value.session), keys.first(), 0L))

        owner.onAction(queue(*keys.toTypedArray()))
        runCurrent()

        assertEquals(VIEWER_PREFETCH_MAX_CONCURRENCY, active.get())
        assertEquals(VIEWER_PREFETCH_MAX_CONCURRENCY, peak.get())
        assertEquals(keys.take(2).toSet(), synchronized(startedKeys) { startedKeys.toSet() })

        val replacementNeighbor = keys.last()
        owner.onAction(queue(replacementNeighbor))
        runCurrent()

        assertEquals(1, active.get())
        assertEquals(replacementNeighbor, synchronized(startedKeys) { startedKeys.last() })
        assertTrue(keys.take(2).all { key -> key in synchronized(cancelledKeys) { cancelledKeys.toSet() } })

        owner.onAction(ViewerAction.QueuePrefetch(emptyList()))
        runCurrent()
        assertEquals(0, active.get())
    }
}
