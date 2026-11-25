package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.VisionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureTagsProgressTest {
    @Test
    fun `videos tagged from their preview count as tagged while their frames wait (ADR 0094)`() {
        assertEquals(
            LibraryProgress("765 of 765 Library posts tagged", "687 videos waiting on their frames", "Finishes while the phone is charging and idle on Wi-Fi"),
            libraryProgress(VisionProgress(tagged = 765, awaitingFrames = 687, total = 765)),
        )
        assertEquals(
            LibraryProgress("765 of 768 Library posts tagged", null, "Finishes while the phone is charging on Wi-Fi"),
            libraryProgress(VisionProgress(tagged = 765, awaitingFrames = 0, total = 768)),
        )
        assertEquals(
            LibraryProgress("10 of 11 Library posts tagged", "1 video waiting on its frames", "Finishes while the phone is charging and idle on Wi-Fi"),
            libraryProgress(VisionProgress(tagged = 10, awaitingFrames = 1, total = 11)),
        )
        assertEquals(LibraryProgress("768 of 768 Library posts tagged", null, null), libraryProgress(VisionProgress(tagged = 768, awaitingFrames = 0, total = 768)))
        assertNull(libraryProgress(VisionProgress(tagged = 0, awaitingFrames = 0, total = 0)))
    }
}
