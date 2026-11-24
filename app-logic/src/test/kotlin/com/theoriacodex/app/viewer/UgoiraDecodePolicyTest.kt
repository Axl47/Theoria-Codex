package com.theoriacodex.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class UgoiraDecodePolicyTest {
    @Test
    fun `a long animation decodes smaller to fit its playback budget while short ones and exports keep resolution (ADR 0074)`() {
        assertEquals(16, ugoiraFrameSampleSize(1200, 1200, 400, UgoiraSizeBucket.CARD))
        assertEquals(8, ugoiraFrameSampleSize(1200, 1200, 400, UgoiraSizeBucket.VIEWER))
        assertEquals(2, ugoiraFrameSampleSize(1200, 1200, 10, UgoiraSizeBucket.CARD))
        assertEquals(1, ugoiraFrameSampleSize(1200, 1200, 10, UgoiraSizeBucket.VIEWER))
        assertEquals(1, ugoiraFrameSampleSize(1200, 1200, 400, UgoiraSizeBucket.EXPORT))
        assertEquals(2, ugoiraFrameSampleSize(219, 219, 400, UgoiraSizeBucket.VIEWER))
        assertEquals(1, ugoiraFrameSampleSize(219, 219, 300, UgoiraSizeBucket.VIEWER))
    }
}
