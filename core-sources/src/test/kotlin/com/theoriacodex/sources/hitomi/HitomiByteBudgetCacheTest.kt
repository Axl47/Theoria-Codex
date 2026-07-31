package com.theoriacodex.sources.hitomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HitomiByteBudgetCacheTest {
    @Test
    fun `LRU exposes exact weights and evicts by byte budget`() {
        val cache = HitomiByteBudgetCache<String, IntArray>(maxBytes = 18L) { key, ids ->
            key.hitomiUtf8ByteWeight() + ids.hitomiByteWeight()
        }
        cache.put("a", intArrayOf(1, 2))
        cache.put("bb", intArrayOf(3))
        cache.get("a")
        cache.put("ccc", intArrayOf(4))

        val snapshot = cache.snapshot()
        assertNull(cache.get("bb"))
        assertEquals(listOf("a", "ccc"), snapshot.keysInLruOrder)
        assertEquals(listOf("a" to 9L, "ccc" to 7L), snapshot.weightsInLruOrder)
        assertEquals(16L, snapshot.cachedBytes)
    }

    @Test
    fun `oversize values are returned to the caller but never retained`() {
        val cache = HitomiByteBudgetCache<String, IntArray>(maxBytes = 4L) { key, ids ->
            key.hitomiUtf8ByteWeight() + ids.hitomiByteWeight()
        }

        assertFalse(cache.put("large", intArrayOf(1)))
        assertNull(cache.get("large"))
        assertTrue(cache.snapshot().keysInLruOrder.isEmpty())
    }
}
