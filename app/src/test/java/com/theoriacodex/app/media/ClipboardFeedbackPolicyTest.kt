package com.theoriacodex.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardFeedbackPolicyTest {
    @Test
    fun `feature copy confirmation remains through Android 12L`() {
        assertEquals("Tags copied", appClipboardConfirmationMessage("Tags copied", sdkInt = 32))
    }

    @Test
    fun `Android 13 and newer rely on the single system confirmation`() {
        assertNull(appClipboardConfirmationMessage("Tags copied", sdkInt = 33))
        assertNull(appClipboardConfirmationMessage("Tags copied", sdkInt = 37))
    }
}
