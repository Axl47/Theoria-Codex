package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PostDownloadServiceTest {
    @Test
    fun `download file name uses sanitized title and url extension`() {
        val post = samplePost(title = "Blue Sky / Sunset?", source = SourceKey.GELBOORU)
        val media = ImageRef(
            url = "https://gelbooru.com/file.jpeg?download=1",
            localPath = null,
            mime = "image/jpeg",
        )

        val fileName = PostDownloadService.buildDownloadFileName(
            post = post,
            media = media,
            fallbackUrl = requireNotNull(media.url),
            pageIndex = null,
            totalPages = 1,
        )

        assertEquals("Blue_Sky_Sunset_gelbooru_123.jpg", fileName)
    }

    @Test
    fun `viewer download file name adds page suffix for multi image posts`() {
        val post = samplePost(title = "Gallery", source = SourceKey.NHENTAI)
        val media = ImageRef(
            url = "https://i.nhentai.net/galleries/1/2.webp",
            localPath = null,
            mime = "image/webp",
        )

        val fileName = PostDownloadService.buildDownloadFileName(
            post = post,
            media = media,
            fallbackUrl = requireNotNull(media.url),
            pageIndex = 1,
            totalPages = 3,
        )

        assertEquals("Gallery_nhentai_123_p2.webp", fileName)
    }

    @Test
    fun `download file name falls back to source id and mime extension`() {
        val post = samplePost(title = null, source = SourceKey.PIXIV)
        val media = ImageRef(
            url = "https://i.pximg.net/protected/original",
            localPath = null,
            mime = "image/png",
        )

        val fileName = PostDownloadService.buildDownloadFileName(
            post = post,
            media = media,
            fallbackUrl = requireNotNull(media.url),
            pageIndex = null,
            totalPages = 1,
        )

        assertEquals("pixiv_123.png", fileName)
    }

    @Test
    fun `hitomi anime download keeps the single mp4 and source headers`() {
        val video = ImageRef(
            url = "https://streaming.gold-usergeneratedcontent.net/videos/example.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        val post = samplePost(title = "Anime Episode", source = SourceKey.HITOMI).copy(
            full = video,
            media = listOf(video),
            mediaCount = 1,
        )

        val candidate = postDownloadMediaCandidate(post)
        assertNotNull(candidate)
        assertEquals(video, candidate?.ref)
        assertEquals("video/mp4", candidate?.ref?.mime)
        assertEquals("https://hitomi.la/", candidate?.requestHeaders?.get("Referer"))
        assertEquals(
            "Anime_Episode_hitomi_123.mp4",
            PostDownloadService.buildDownloadFileName(
                post = post,
                media = video,
                fallbackUrl = requireNotNull(video.url),
                pageIndex = 0,
                totalPages = 1,
            ),
        )
    }

    @Test
    fun `Unicode titles retain their text while canonical identities prevent collisions`() {
        val media = ImageRef("https://example.test/file.jpg", null, "image/jpeg")
        fun name(title: String, id: String) = PostDownloadService.buildDownloadFileName(
            samplePost(title, SourceKey.PIXIV).copy(id = PostId(SourceKey.PIXIV, id)), media, media.url!!, null, 1,
        )
        assertEquals("猫_pixiv_1.jpg", name("猫", "1"))
        assertEquals("犬_pixiv_2.jpg", name("犬", "2"))
        assertEquals("Same_pixiv_1.jpg", name("Same", "1"))
        assertEquals("Same_pixiv_2.jpg", name("Same", "2"))
        assertEquals("pixiv_1.jpg", name(".../", "1"))
        val longName = name("猫".repeat(200), "id".repeat(100))
        org.junit.Assert.assertTrue(longName.toByteArray(Charsets.UTF_8).size < 255)
        org.junit.Assert.assertFalse(longName.contains('/'))
    }

    @Test
    fun `gallery requests preserve every resolved page and reject incomplete provider payloads`() {
        val media = List(3) { ImageRef("https://example.test/$it.jpg", null, "image/jpeg") }
        val post = samplePost("Gallery", SourceKey.PIXIV).copy(media = media, full = media.first(), mediaCount = 3)
        val pages = galleryDownloadPages(post)
        assertEquals(listOf(0, 1, 2), pages.map { it.selectedMedia?.pageIndex })
        assertEquals(media, pages.map { it.selectedMedia?.media })
        assertEquals(listOf(3, 3, 3), pages.map { it.selectedMedia?.totalPages })
        org.junit.Assert.assertTrue(runCatching { galleryDownloadPages(post.copy(mediaCount = 4)) }.isFailure)
    }

    private fun samplePost(title: String?, source: SourceKey): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "123"),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = title,
        )
    }
}
