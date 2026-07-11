package com.theoriacodex.app.source

import com.theoriacodex.domain.model.SourceKey
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPostDeepLinksTest {
    @Test
    fun `parses booru query links through the shared route contract`() {
        listOf(
            Triple(
                "https://gelbooru.com/index.php?page=post&s=view&id=9876",
                SourceKey.GELBOORU,
                "9876",
            ),
            Triple(
                "http://www.gelbooru.com/?s=VIEW&page=POST&id=9877",
                SourceKey.GELBOORU,
                "9877",
            ),
            Triple(
                "https://rule34.xxx/index.php?page=post&s=view&id=12345",
                SourceKey.RULE34XXX,
                "12345",
            ),
            Triple(
                "http://www.rule34.xxx/?s=VIEW&id=12346&page=POST",
                SourceKey.RULE34XXX,
                "12346",
            ),
        ).forEach { (url, source, postId) ->
            assertDeepLink(url = url, source = source, postId = postId)
        }
    }

    @Test
    fun `rejects malformed or lookalike booru query links`() {
        listOf(
            "https://gelbooru.com/posts?page=post&s=view&id=12",
            "https://gelbooru.example.com/index.php?page=post&s=view&id=12",
            "https://gelbooru.com/index.php?page=post&s=view&id=abc",
            "https://gelbooru.com/index.php?page=post&s=view&id=%FF",
            "https://rule34.xxx/index.php?page=post&s=list&id=12",
            "https://rule34.xxx/index.php?page=post&s=view&id=%ZZ",
            "https://rule34.xxx.example.com/index.php?page=post&s=view&id=12",
        ).forEach { url ->
            assertNull(url, parseExternalPostDeepLink(url))
        }
    }

    @Test
    fun `parses rule34 family deep links`() {
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
    fun `parses nhentai gallery and page deep links`() {
        assertDeepLink(
            url = "https://nhentai.net/g/527662/",
            source = SourceKey.NHENTAI,
            postId = "527662",
        )
        assertDeepLink(
            url = "https://nhentai.net/g/527662/1/",
            source = SourceKey.NHENTAI,
            postId = "527662",
        )
    }

    @Test
    fun `parses exact hitomi reader and gallery routes across supported areas`() {
        assertDeepLink(
            url = "http://www.hitomi.la/reader/4042375.html?ignored=true#17",
            source = SourceKey.HITOMI,
            postId = "4042375",
        )
        listOf(
            "anime",
            "cg",
            "doujinshi",
            "manga",
            "artistcg",
            "gamecg",
            "imageset",
        ).forEachIndexed { index, area ->
            val postId = (7_231 + index).toString()
            assertDeepLink(
                url = "https://hitomi.la/$area/example-%E6%97%A5%E6%9C%AC%E8%AA%9E-$postId.html#1",
                source = SourceKey.HITOMI,
                postId = postId,
            )
        }
    }

    @Test
    fun `rejects lookalike or unsupported hitomi post routes`() {
        listOf(
            "ftp://hitomi.la/reader/4042375.html",
            "https://hitomi.la.example.com/reader/4042375.html",
            "https://hitomi.la/reader/not-a-number.html",
            "https://hitomi.la/reader/4042375.htm",
            "https://hitomi.la/reader/4042375.html/extra",
            "https://hitomi.la/tag/example-4042375.html",
            "https://hitomi.la/cg/-4042375.html",
            "https://hitomi.la/cg/example-4042375.htm",
            "https://hitomi.la/cg/example-4042375.html/extra",
            "https://hitomi.la/cg/example-4042375.html.bak",
        ).forEach { url ->
            assertNull(url, parseExternalPostDeepLink(url))
        }
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
    fun `parses hitomi artists into normalized canonical creator identities`() {
        assertCreatorDeepLink(
            url = "https://hitomi.la/artist/%20Arisue%20%20Tsukasa%20-all.html#works",
            source = SourceKey.HITOMI,
            creatorId = "arisue tsukasa",
            profileUrl = "https://hitomi.la/artist/arisue%20tsukasa-all.html",
        )
        assertCreatorDeepLink(
            url = "http://www.hitomi.la/artist/C++-all.html?ignored=true",
            source = SourceKey.HITOMI,
            creatorId = "c++",
            profileUrl = "https://hitomi.la/artist/c%2B%2B-all.html",
        )
        assertCreatorDeepLink(
            url = "https://hitomi.la/artist/%E3%81%82%E3%81%84%E3%81%86-all.html",
            source = SourceKey.HITOMI,
            creatorId = "あいう",
            profileUrl = "https://hitomi.la/artist/%E3%81%82%E3%81%84%E3%81%86-all.html",
        )
    }

    @Test
    fun `hitomi artist identity limit counts unicode code points`() {
        val supplementaryCharacter = "\uD83D\uDE00"
        val maximumIdentity = supplementaryCharacter.repeat(256)
        val maximumUrl = "https://hitomi.la/artist/${encodePathSegment(maximumIdentity)}-all.html"

        assertCreatorDeepLink(
            url = maximumUrl,
            source = SourceKey.HITOMI,
            creatorId = maximumIdentity,
            profileUrl = maximumUrl,
        )

        val overLimitIdentity = supplementaryCharacter.repeat(257)
        assertNull(
            parseExternalCreatorDeepLink(
                "https://hitomi.la/artist/${encodePathSegment(overLimitIdentity)}-all.html",
            ),
        )
    }

    @Test
    fun `rejects unsafe malformed or empty hitomi artist identities`() {
        listOf(
            "https://hitomi.la/artist/%20-all.html",
            "https://hitomi.la/artist/name%2Falias-all.html",
            "https://hitomi.la/artist/name%5Calias-all.html",
            "https://hitomi.la/artist/name%0Aalias-all.html",
            "https://hitomi.la/artist/%C3%28-all.html",
            "https://hitomi.la/artist/%ED%A0%80-all.html",
            "https://hitomi.la/artist/%FF-all.html",
            "https://hitomi.la/artist/name%ZZ-all.html",
            "https://hitomi.la/artist/name.html",
            "https://hitomi.la/artist/name-all.html/extra",
            "https://hitomi.la.example.com/artist/name-all.html",
            "content://hitomi.la/artist/name-all.html",
            "https://hitomi.la/artist/${"a".repeat(257)}-all.html",
        ).forEach { url ->
            assertNull(url, parseExternalCreatorDeepLink(url))
        }
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

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
