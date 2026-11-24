package com.theoriacodex.app.viewer

import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewerVideoPrefetchTest {
    @Test
    fun `warmed bytes are explicit and skipped or failed work cannot claim cache data`() {
        assertEquals(
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
            ViewerPrefetchResult(ViewerPrefetchOutcome.WARMED, bytesCached = 4_096L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED, bytesCached = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED, bytesCached = 1L)
        }
    }
}
