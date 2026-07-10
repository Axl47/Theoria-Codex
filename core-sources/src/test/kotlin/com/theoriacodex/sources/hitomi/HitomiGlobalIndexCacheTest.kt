package com.theoriacodex.sources.hitomi

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HitomiGlobalIndexCacheTest {
    @Test
    fun `byte weighted LRU evicts the least recently used indexes`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 12L)

        cache.getOrLoad("v1", "large") { intArrayOf(1, 2) }
        cache.getOrLoad("v1", "old-small") { intArrayOf(3) }
        assertArrayEquals(intArrayOf(1, 2), cache.get("large"))
        cache.getOrLoad("v1", "new-small") { intArrayOf(4) }

        assertArrayEquals(intArrayOf(1, 2), cache.get("large"))
        assertNull(cache.get("old-small"))
        assertArrayEquals(intArrayOf(4), cache.get("new-small"))
        assertEquals(12L, cache.snapshot().cachedBytes)
        assertEquals(listOf("large", "new-small"), cache.snapshot().keysInLruOrder)
    }

    @Test
    fun `an index larger than the total budget is returned without being cached`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 4L)

        val loaded = cache.getOrLoad("v1", "oversize") { intArrayOf(1, 2) }

        assertArrayEquals(intArrayOf(1, 2), loaded)
        assertNull(cache.get("oversize"))
        assertEquals(0L, cache.snapshot().cachedBytes)
    }

    @Test
    fun `activating a new provider version clears every cached index`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 64L)
        cache.getOrLoad("v1", "term-a") { intArrayOf(1) }
        cache.getOrLoad("v1", "term-b") { intArrayOf(2) }

        cache.getOrLoad("v2", "term-c") { intArrayOf(3) }

        assertNull(cache.get("term-a"))
        assertNull(cache.get("term-b"))
        assertArrayEquals(intArrayOf(3), cache.get("term-c"))
        assertEquals("v2", cache.snapshot().activeVersion)
        assertEquals(4L, cache.snapshot().cachedBytes)
    }

    @Test
    fun `concurrent callers share one load for the same version and key`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 64L)
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val loadCount = AtomicInteger(0)

        val first = async {
            cache.getOrLoad("v1", "shared") {
                loadCount.incrementAndGet()
                loadStarted.complete(Unit)
                releaseLoad.await()
                intArrayOf(7, 8)
            }
        }
        loadStarted.await()
        val second = async {
            cache.getOrLoad("v1", "shared") {
                loadCount.incrementAndGet()
                intArrayOf(9)
            }
        }
        runCurrent()
        assertEquals(1, loadCount.get())

        releaseLoad.complete(Unit)

        assertArrayEquals(intArrayOf(7, 8), first.await())
        assertArrayEquals(intArrayOf(7, 8), second.await())
        assertEquals(1, loadCount.get())
        assertEquals(0, cache.snapshot().inFlightLoads)
    }

    @Test
    fun `owner cancellation reaches waiters and leaves the key retryable`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 64L)
        val loadStarted = CompletableDeferred<Unit>()
        val loadCount = AtomicInteger(0)
        val owner = async {
            cache.getOrLoad("v1", "shared") {
                loadCount.incrementAndGet()
                loadStarted.complete(Unit)
                awaitCancellation()
            }
        }
        loadStarted.await()
        val waiter = async {
            runCatching {
                cache.getOrLoad("v1", "shared") {
                    loadCount.incrementAndGet()
                    intArrayOf(9)
                }
            }.exceptionOrNull()
        }
        runCurrent()

        owner.cancelAndJoin()

        assertTrue(waiter.await() is CancellationException)
        assertNull(cache.get("shared"))
        assertEquals(0, cache.snapshot().inFlightLoads)
        val retried = cache.getOrLoad("v1", "shared") {
            loadCount.incrementAndGet()
            intArrayOf(10)
        }
        assertArrayEquals(intArrayOf(10), retried)
        assertEquals(2, loadCount.get())
    }

    @Test
    fun `different versions never share an in flight load`() = runTest {
        val cache = HitomiGlobalIndexCache(maxBytes = 64L)
        val v1Started = CompletableDeferred<Unit>()
        val releaseV1 = CompletableDeferred<Unit>()
        val v1 = async {
            cache.getOrLoad("v1", "shared") {
                v1Started.complete(Unit)
                releaseV1.await()
                intArrayOf(1)
            }
        }
        v1Started.await()

        val v2 = cache.getOrLoad("v2", "shared") { intArrayOf(2) }
        releaseV1.complete(Unit)

        assertArrayEquals(intArrayOf(2), v2)
        assertArrayEquals(intArrayOf(1), v1.await())
        assertArrayEquals(intArrayOf(2), cache.get("shared"))
        assertFalse(cache.snapshot().keysInLruOrder.isEmpty())
        assertEquals("v2", cache.snapshot().activeVersion)
    }
}
