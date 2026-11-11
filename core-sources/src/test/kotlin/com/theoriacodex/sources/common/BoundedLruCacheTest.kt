package com.theoriacodex.sources.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruCacheTest {
    @Test
    fun `evicts the least recently used entry past capacity`() {
        val cache = BoundedLruCache<String, Int>(maxEntries = 2)
        cache.update("a") { 1 }
        cache.update("b") { 2 }
        cache["a"] // makes "b" the eldest
        cache.update("c") { 3 }

        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
    }

    @Test
    fun `update sees the current value`() {
        val cache = BoundedLruCache<String, List<Int>>(maxEntries = 2)
        cache.update("a") { (it ?: emptyList()) + 1 }
        cache.update("a") { (it ?: emptyList()) + 2 }

        assertEquals(listOf(1, 2), cache["a"])
    }
}
