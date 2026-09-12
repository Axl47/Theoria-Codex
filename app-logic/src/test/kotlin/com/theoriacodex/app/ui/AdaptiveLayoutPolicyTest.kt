package com.theoriacodex.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test fun `compact windows keep phone geometry and wide panes add bounded lanes`() {
        assertFalse(useNavigationRail(599f))
        assertTrue(useNavigationRail(600f))
        assertEquals(2, adaptiveFeedColumns(320f))
        assertEquals(2, adaptiveFeedColumns(599f))
        assertEquals(3, adaptiveFeedColumns(700f))
        assertEquals(4, adaptiveFeedColumns(1200f))
        assertEquals(4, adaptiveFeedColumns(3000f))
        assertFalse(useViewerInfoSidePanel(999f))
        assertTrue(useViewerInfoSidePanel(1000f))
    }
}
