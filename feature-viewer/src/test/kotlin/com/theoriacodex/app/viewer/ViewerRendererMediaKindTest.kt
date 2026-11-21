package com.theoriacodex.app.viewer

import com.theoriacodex.app.testing.testPost
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerRendererMediaKindTest {
    @Test fun `an animated webp plays without a seekable timeline whatever its source`() {
        val webp = ImageRef("https://example.test/a.webp", null, "image/webp", isAnimated = true)
        for (source in listOf(SourceKey.HITOMI, SourceKey.GELBOORU)) {
            val kind = viewerRendererMediaKind(testPost(source = source), webp, hasUgoiraClient = false)
            assertEquals(ViewerMediaKind.ANIMATED_WEBP, kind)
            assertTrue(kind.animated)
            assertFalse(kind.seekable)
        }
    }

    @Test fun `an ugoira is seekable only with a client to play it`() {
        val ugoira = ImageRef(null, null, PIXIV_UGOIRA_MIME)
        val post = testPost(source = SourceKey.PIXIV).copy(full = ugoira, media = listOf(ugoira))
        assertTrue(viewerRendererMediaKind(post, ugoira, hasUgoiraClient = true).seekable)
        assertEquals(ViewerMediaKind.IMAGE, viewerRendererMediaKind(post, ugoira, hasUgoiraClient = false))
    }

    @Test fun `video and gif are seekable and a still is neither`() {
        val post = testPost()
        assertTrue(viewerRendererMediaKind(post, ImageRef("https://example.test/a.mp4", null, "video/mp4"), false).seekable)
        assertTrue(viewerRendererMediaKind(post, ImageRef("https://example.test/a.gif", null, "image/gif"), false).seekable)
        val still = viewerRendererMediaKind(post, ImageRef("https://example.test/a.jpg", null, "image/jpeg"), false)
        assertFalse(still.animated)
    }
}
