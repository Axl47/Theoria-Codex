package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.VisionProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureTagsProgressTest {
    @Test
    fun `videos tagged from their preview count as tagged while their frames wait (ADR 0094)`() {
        assertEquals(
            "765 of 768 Library posts tagged, 687 videos from their preview only. " +
                "The rest finish while charging on Wi-Fi, videos once the phone sits unused.",
            libraryProgress(VisionProgress(analysed = 78, awaitingFrames = 687, total = 768)),
        )
        assertEquals("768 of 768 Library posts tagged", libraryProgress(VisionProgress(analysed = 768, awaitingFrames = 0, total = 768)))
    }
}
