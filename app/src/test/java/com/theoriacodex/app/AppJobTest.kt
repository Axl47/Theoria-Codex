package com.theoriacodex.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppJobTest {
    @Test
    fun `every job has its own ID`() {
        val shared = AppJob.entries.groupBy { it.id }.filterValues { it.size > 1 }
        assertEquals(emptyMap<Int, List<AppJob>>(), shared)
    }
}
