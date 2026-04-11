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
    fun `parses iwara video deep links`() {
        assertDeepLink(
            url = "https://www.iwara.tv/video/KH2f7fca2MCgZ8/example-slug",
            source = SourceKey.IWARA,
            postId = "KH2f7fca2MCgZ8",
        )
        assertDeepLink(
            url = "https://iwara.tv/video/5ak7ohralkswrykwa",
            source = SourceKey.IWARA,
            postId = "5ak7ohralkswrykwa",
        )
    }

    @Test
    fun `parses pixiv posts with and without locale`() {
        assertDeepLink(
            url = "https://www.pixiv.net/en/artworks/111111111",
            source = SourceKey.PIXIV,
            postId = "111111111",
        )
        assertDeepLink(
            url = "https://www.pixiv.net/artworks/111111111",
            source = SourceKey.PIXIV,
            postId = "111111111",
        )
    }

    @Test
    fun `rejects unsupported iwara profile urls`() {
        assertNull(parseExternalPostDeepLink("https://www.iwara.tv/profile/mmdparadaise/videos"))
    }

    @Test
    fun `parses supported creator profile deep links`() {
        assertCreatorDeepLink(
            url = "https://www.pixiv.net/en/users/201823",
            source = SourceKey.PIXIV,
            creatorId = "201823",
            profileUrl = "https://www.pixiv.net/en/users/201823",
        )
        assertCreatorDeepLink(
            url = "https://www.pixiv.net/users/201823/illustrations",
            source = SourceKey.PIXIV,
            creatorId = "201823",
            profileUrl = "https://www.pixiv.net/en/users/201823",
        )
        assertCreatorDeepLink(
            url = "https://gelbooru.com/index.php?page=account&s=profile&id=179338",
            source = SourceKey.GELBOORU,
            creatorId = "179338",
            profileUrl = "https://gelbooru.com/index.php?page=account&s=profile&id=179338",
        )
    }

    @Test
    fun `returns null for unsupported host`() {
        assertNull(parseExternalPostDeepLink("https://example.com/video/8255/foo/"))
        assertNull(parseExternalCreatorDeepLink("https://example.com/users/201823"))
    }

    private fun assertDeepLink(url: String, source: SourceKey, postId: String) {
        val parsed = parseExternalPostDeepLink(url)

        requireNotNull(parsed)
        assertEquals(source, parsed.source)
        assertEquals(postId, parsed.postId)
        assertEquals(source.displayName(), parsed.sourceLabel)
    }

    private fun assertCreatorDeepLink(
        url: String,
        source: SourceKey,
        creatorId: String,
        profileUrl: String,
    ) {
        val parsed = parseExternalCreatorDeepLink(url)

        requireNotNull(parsed)
        assertEquals(source, parsed.source)
        assertEquals(creatorId, parsed.creatorId)
        assertEquals(profileUrl, parsed.profileUrl)
        assertEquals(source.displayName(), parsed.sourceLabel)
    }
}
