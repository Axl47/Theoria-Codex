package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import com.theoriacodex.app.viewer.ViewerOwnerTestData.gif
import com.theoriacodex.app.viewer.ViewerOwnerTestData.image
import com.theoriacodex.app.viewer.ViewerOwnerTestData.post
import com.theoriacodex.app.viewer.ViewerOwnerTestData.queue
import com.theoriacodex.app.viewer.ViewerOwnerTestData.request
import com.theoriacodex.app.viewer.ViewerOwnerTestData.session
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaError
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `a neighbor GIF waits until the current media shows or fails, while other neighbors start at once (ADR 0226)`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                started += media.key
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        val media = listOf(image("0.jpg"), image("1.jpg"), gif("2.gif"), image("3.jpg"), gif("4.gif"))
        owner.replaceSession(session("gif-hold", listOf(post("gallery", media = media))))
        val session = requireNotNull(owner.state.value.session)
        val keys = owner.state.value.currentPage?.media.orEmpty().map { item -> item.key }
        owner.onAction(ViewerAction.MediaReady(session, keys[0], 0L))
        owner.onAction(ViewerAction.SelectMedia(1))

        owner.onAction(queue(keys[2], keys[3]))
        runCurrent()
        assertEquals(listOf(keys[3]), started)
        owner.onAction(ViewerAction.MediaReady(session, keys[1], 0L))
        runCurrent()
        assertEquals(listOf(keys[3], keys[2]), started)

        owner.onAction(ViewerAction.SelectMedia(3))
        owner.onAction(queue(keys[4]))
        runCurrent()
        assertEquals(listOf(keys[3], keys[2]), started)
        owner.onAction(ViewerAction.MediaFailed(session, ViewerMediaError.Recoverable(keys[3], "failed")))
        runCurrent()
        assertEquals(listOf(keys[3], keys[2], keys[4]), started)
    }

    @Test
    fun `a neighbor GIF that waited for a download slot still waits while the page now current loads (ADR 0226)`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val slotsHeld = CompletableDeferred<Unit>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                started += media.key
                if (media.kind != com.theoriacodex.app.viewer.state.ViewerMediaKind.GIF) slotsHeld.await()
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        val media = listOf(image("0.jpg"), image("1.jpg"), image("2.jpg"), gif("3.gif"), image("4.jpg"))
        owner.replaceSession(session("gif-slot", listOf(post("gallery", media = media))))
        val session = requireNotNull(owner.state.value.session)
        val keys = owner.state.value.currentPage?.media.orEmpty().map { item -> item.key }
        owner.onAction(ViewerAction.MediaReady(session, keys[0], 0L))

        owner.onAction(queue(keys[1], keys[2], keys[3]))
        runCurrent()
        assertEquals(listOf(keys[1], keys[2]), started)
        owner.onAction(ViewerAction.SelectMedia(4))
        slotsHeld.complete(Unit)
        runCurrent()
        assertEquals(listOf(keys[1], keys[2]), started)
        owner.onAction(ViewerAction.MediaReady(session, keys[4], 0L))
        runCurrent()
        assertEquals(listOf(keys[1], keys[2], keys[3]), started)
    }

    @Test
    fun `a neighbor GIF already downloading keeps going when its page becomes current (ADR 0226)`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                if (media.kind == com.theoriacodex.app.viewer.state.ViewerMediaKind.GIF) {
                    try { finish.await() } catch (stop: CancellationException) { cancelled.complete(Unit); throw stop }
                }
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        owner.replaceSession(session("gif-current", listOf(post("gallery", media = listOf(image("0.jpg"), gif("1.gif"), image("2.jpg"))))))
        val session = requireNotNull(owner.state.value.session)
        val keys = owner.state.value.currentPage?.media.orEmpty().map { item -> item.key }
        owner.onAction(ViewerAction.MediaReady(session, keys[0], 0L))
        owner.onAction(queue(keys[1], keys[2]))
        runCurrent()

        owner.onAction(ViewerAction.SelectMedia(1))
        owner.onAction(queue(keys[2], keys[0]))
        runCurrent()

        assertFalse(cancelled.isCompleted)
        finish.complete(Unit)
        runCurrent()
        assertFalse(cancelled.isCompleted)
    }

    @Test
    fun `a neighbor GIF stops waiting once the hold limit passes, even if the current page never settles (ADR 0226)`() = runTest {
        val started = mutableListOf<com.theoriacodex.app.viewer.state.ViewerMediaKey>()
        val owner = testViewerOwner(
            savedStateHandle = SavedStateHandle(),
            mediaPrefetcher = ViewerMediaPrefetcher { _, media, _ ->
                started += media.key
                ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED)
            },
            scopeOverride = this,
        )
        owner.replaceSession(session("gif-limit", listOf(post("gallery", media = listOf(image("0.jpg"), gif("1.gif"))))))
        val keys = owner.state.value.currentPage?.media.orEmpty().map { item -> item.key }

        owner.onAction(queue(keys[1]))
        advanceTimeBy(VIEWER_GIF_HOLD_MAX_MS - 1)
        assertEquals(emptyList<Any>(), started)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(keys[1]), started)
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
