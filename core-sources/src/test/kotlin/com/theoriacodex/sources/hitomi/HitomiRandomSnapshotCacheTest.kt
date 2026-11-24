package com.theoriacodex.sources.hitomi

import java.util.concurrent.atomic.AtomicInteger
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
    fun `initial walks reuse only fresh snapshots while continuations retain exact snapshots (ADR 0043)`() = runTest {
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
    fun `a waiter keeps the shared load alive after the first caller is cancelled`() = runTest {
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
        val waiter = async { cache.getOrLoad("url", null) { error("must share URL flight") } }
        runCurrent()

        owner.cancelAndJoin()
        release.complete(Unit)

        assertArrayEquals(intArrayOf(1, 2), waiter.await().ids)
        assertArrayEquals(intArrayOf(1, 2), cache.getOrLoad("url", null) { error("fresh cache miss") }.ids)
    }

    @Test
    fun `the load stops when every caller leaves and nothing is retained (ADR 0043)`() = runTest {
        val cache = HitomiRandomSnapshotCache()
        val started = CompletableDeferred<Unit>()
        var loadCancelled = false
        val callers = List(2) {
            async {
                cache.getOrLoad("url", null) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { loadCancelled = true }
                }
            }
        }
        started.await()
        runCurrent()

        callers.forEach { caller -> caller.cancelAndJoin() }
        runCurrent()

        assertTrue(loadCancelled)
        assertEquals(0L, cache.snapshot().cachedBytes)
        assertArrayEquals(intArrayOf(9), cache.getOrLoad("url", null) { snapshot(9) }.ids)
    }

    @Test
    fun `byte budget evicts LRU snapshots and skips oversize snapshots (ADR 0043)`() = runTest {
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
