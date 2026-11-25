package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.VisionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureTagsProgressTest {
    @Test
    fun `videos tagged from their preview count as tagged while their frames wait (ADR 0094)`() {
        assertEquals(
            LibraryProgress("765 of 765 Library posts tagged", "687 videos finish from their frames while charging and idle on Wi-Fi"),
            libraryProgress(VisionProgress(tagged = 765, awaitingFrames = 687, total = 765)),
        )
        assertEquals(
            LibraryProgress("765 of 768 Library posts tagged", "The rest are tagged while charging on Wi-Fi"),
            libraryProgress(VisionProgress(tagged = 765, awaitingFrames = 0, total = 768)),
        )
        assertEquals(
            LibraryProgress("10 of 11 Library posts tagged", "The rest are tagged while charging on Wi-Fi, and 1 video finishes from its frames once the phone is also idle"),
            libraryProgress(VisionProgress(tagged = 10, awaitingFrames = 1, total = 11)),
        )
        assertEquals(LibraryProgress("768 of 768 Library posts tagged", null), libraryProgress(VisionProgress(tagged = 768, awaitingFrames = 0, total = 768)))
        assertNull(libraryProgress(VisionProgress(tagged = 0, awaitingFrames = 0, total = 0)))
    }
}
