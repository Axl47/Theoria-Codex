package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
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

        assertEquals("Blue_Sky_Sunset.jpg", fileName)
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

        assertEquals("Gallery_p2.webp", fileName)
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
