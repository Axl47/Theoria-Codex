package com.theoriacodex.app.viewer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAwaitsMotionTest {
    @Test fun `a video-only source's unresolved post awaits motion even without a running time`() {
        assertTrue(testPost(source = SourceKey.RULE34VIDEO, full = null).awaitsMotion)
    }

    @Test fun `an unresolved gallery of stills does not`() {
        assertFalse(testPost(source = SourceKey.NHENTAI, full = null, mediaCount = 20).awaitsMotion)
    }
}
