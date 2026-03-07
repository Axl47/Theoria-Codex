package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPostDeepLinksTest {
    @Test
    fun `parses rule34 family deep links`() {
        assertDeepLink(
            url = "https://rule34.xxx/index.php?page=post&s=view&id=12345",
            source = SourceKey.RULE34XXX,
            postId = "12345",
        )
        assertDeepLink(
            url = "https://rule34.paheal.net/post/view/5773878",
            source = SourceKey.RULE34PAHEAL,
            postId = "5773878",
        )
        assertDeepLink(
            url = "https://rule34video.com/video/3089604/gen-gen-gen-hmv-pmv-genshin-impact/",
            source = SourceKey.RULE34VIDEO,
            postId = "3089604",
        )
        assertDeepLink(
            url = "https://rule34gen.com/video/8255/claire-russell-futa-cumshot-kiyuxaai/",
            source = SourceKey.RULE34GEN,
            postId = "8255",
        )
    }

    @Test
    fun `returns null for unsupported host`() {
        assertNull(parseExternalPostDeepLink("https://example.com/video/8255/foo/"))
    }

    private fun assertDeepLink(url: String, source: SourceKey, postId: String) {
        val parsed = parseExternalPostDeepLink(url)

        requireNotNull(parsed)
        assertEquals(source, parsed.source)
        assertEquals(postId, parsed.postId)
        assertEquals(source.displayName(), parsed.sourceLabel)
    }
}
