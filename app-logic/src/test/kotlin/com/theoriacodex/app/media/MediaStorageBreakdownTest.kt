package com.theoriacodex.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorageBreakdownTest {
    @Test
    fun `offline and cache categories share one byte accurate total`() {
        val breakdown = mediaStorageBreakdown(500, 200, 150, 100, 50)

        assertEquals(1_000L, breakdown.totalBytes)
        assertEquals(MediaStorageCategory.entries, breakdown.categories.map(MediaStorageUsage::category))
        assertEquals(listOf(500L, 200L, 150L, 100L, 50L), breakdown.categories.map(MediaStorageUsage::bytes))
        assertEquals(listOf(50.0, 20.0, 15.0, 10.0, 5.0), breakdown.categories.map(breakdown::percentage))
        assertEquals(100.0, breakdown.categories.sumOf(breakdown::percentage), 0.0001)
    }

    @Test
    fun `empty storage retains every legend category without invalid percentages`() {
        val breakdown = mediaStorageBreakdown(0, 0, 0, 0, 0)

        assertEquals(0L, breakdown.totalBytes)
        assertEquals(5, breakdown.categories.size)
        assertTrue(breakdown.categories.all { breakdown.percentage(it) == 0.0 })
    }

    @Test
    fun `sizes above integer range retain exact bytes and small categories retain a share`() {
        val breakdown = mediaStorageBreakdown(5_000_000_000, 1, 0, 0, 0)

        assertEquals(5_000_000_001L, breakdown.totalBytes)
        assertTrue(breakdown.percentage(breakdown.categories[1]) > 0.0)
    }
}
