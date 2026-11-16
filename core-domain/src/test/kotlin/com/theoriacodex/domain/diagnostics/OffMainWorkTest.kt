package com.theoriacodex.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OffMainWorkTest {
    @Test
    fun `install hands back the checker it replaced so a test can restore it`() {
        val seen = mutableListOf<String>()
        val recorder = OffMainChecker { label -> seen += label }
        val previous = OffMainWork.install(recorder)
        try {
            OffMainWork.note(OffMainWork.SOURCE_PARSE)
            assertSame(recorder, OffMainWork.install(previous))
        } finally {
            OffMainWork.install(previous)
        }
        assertEquals(listOf(OffMainWork.SOURCE_PARSE), seen)
    }
}
