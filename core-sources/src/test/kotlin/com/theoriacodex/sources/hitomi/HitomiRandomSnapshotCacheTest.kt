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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HitomiRandomSnapshotCacheTest {
    @Test
    fun `initial walks reuse only fresh snapshots while continuations retain exact snapshots`() = runTest {
        var now = 1_000L
        val cache = HitomiRandomSnapshotCache(initialReuseTtlMillis = 100L, nowMillis = { now })
        val loads = AtomicInteger(0)
        val first = cache.getOrLoad("url", null) {
            loads.incrementAndGet()
            snapshot(1, 2)
        }
        now += 99L
        assertArrayEquals(first.ids, cache.getOrLoad("url", null) { error("fresh cache miss") }.ids)
        now += 1L
        val refreshed = cache.getOrLoad("url", null) {
            loads.incrementAndGet()
            snapshot(3, 4)
        }
        now += 10_000L
        val continuation = cache.getOrLoad("url", first.fingerprint) {
            error("continuation should reuse its fingerprinted snapshot")
        }

        assertEquals(2, loads.get())
        assertArrayEquals(intArrayOf(3, 4), refreshed.ids)
        assertArrayEquals(first.ids, continuation.ids)
    }

    @Test
    fun `URL single flight waiter rechecks its expected fingerprint`() = runTest {
        val cache = HitomiRandomSnapshotCache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            cache.getOrLoad("url", null) {
                started.complete(Unit)
                release.await()
                snapshot(1, 2)
            }
        }
        started.await()
        val waiter = async {
            runCatching {
                cache.getOrLoad("url", snapshot(9).fingerprint) { error("must share URL flight") }
            }.exceptionOrNull()
        }
        runCurrent()
        release.complete(Unit)

        owner.await()
        assertTrue(waiter.await() is HitomiRandomSnapshotMismatchException)
    }

    @Test
    fun `owner cancellation reaches waiters and leaves no retained or in flight result`() = runTest {
        val cache = HitomiRandomSnapshotCache()
        val started = CompletableDeferred<Unit>()
        val owner = async {
            cache.getOrLoad("url", null) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val waiter = async {
            runCatching { cache.getOrLoad("url", null) { snapshot(9) } }.exceptionOrNull()
        }
        runCurrent()
        owner.cancelAndJoin()

        assertTrue(waiter.await() is CancellationException)
        assertEquals(0L, cache.snapshot().cachedBytes)
        assertArrayEquals(intArrayOf(9), cache.getOrLoad("url", null) { snapshot(9) }.ids)
    }

    @Test
    fun `byte budget evicts LRU snapshots and skips oversize snapshots`() = runTest {
        val sample = snapshot(1, 2)
        val oneEntryWeight = "a".hitomiUtf8ByteWeight() + sample.fingerprint.hitomiUtf8ByteWeight() +
            sample.ids.hitomiByteWeight() + Long.SIZE_BYTES
        val cache = HitomiRandomSnapshotCache(maxBytes = oneEntryWeight)

        cache.getOrLoad("a", null) { sample }
        cache.getOrLoad("b", null) { snapshot(3, 4) }
        val snapshot = cache.snapshot()

        assertEquals(oneEntryWeight, snapshot.cachedBytes)
        assertEquals(listOf("b"), snapshot.keysInLruOrder.map(HitomiRandomSnapshotKey::url))

        val oversize = HitomiRandomSnapshotCache(maxBytes = oneEntryWeight - 1L)
        oversize.getOrLoad("a", null) { sample }
        assertNull(oversize.snapshot().keysInLruOrder.singleOrNull())
    }

    private fun snapshot(vararg ids: Int): HitomiNozomiSnapshot {
        val values = ids
        return HitomiNozomiSnapshot(values, values.hitomiSha256Hex())
    }
}
