package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerScreenImagePipelineTest {
    @Test
    fun `pixiv viewer candidates prefer progressive urls before canonical url`() {
        val media = ImageRef(
            url = "https://i.pximg.net/original.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = listOf(
                "https://i.pximg.net/medium.jpg",
                "https://i.pximg.net/large.jpg",
            ),
        )
        val post = samplePost(
            sourceKey = SourceKey.PIXIV,
            preview = ImageRef(
                url = "https://i.pximg.net/square_medium.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = media,
            media = listOf(media),
        )

        assertEquals(
            listOf(
                "https://i.pximg.net/medium.jpg",
                "https://i.pximg.net/large.jpg",
                "https://i.pximg.net/original.jpg",
            ),
            viewerImageCandidates(post, media),
        )
        assertEquals(
            "https://i.pximg.net/medium.jpg",
            viewerPrefetchImageLocation(post, media),
        )
    }

    @Test
    fun `non pixiv viewer candidates preserve media full preview order`() {
        val media = ImageRef(
            url = "https://example.com/media.jpg",
            localPath = null,
            mime = "image/jpeg",
        )
        val full = ImageRef(
            url = "https://example.com/full.jpg",
            localPath = null,
            mime = "image/jpeg",
        )
        val preview = ImageRef(
            url = "https://example.com/preview.jpg",
            localPath = null,
            mime = "image/jpeg",
        )
        val post = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = preview,
            full = full,
            media = listOf(media),
        )

        assertEquals(
            listOf(
                "https://example.com/media.jpg",
                "https://example.com/full.jpg",
                "https://example.com/preview.jpg",
            ),
            viewerImageCandidates(post, media),
        )
        assertEquals(
            "https://example.com/media.jpg",
            viewerPrefetchImageLocation(post, media),
        )
    }

    @Test
    fun `gelbooru viewer candidates prefer sample before canonical url`() {
        val media = ImageRef(
            url = "https://gelbooru.com/full.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = listOf("https://gelbooru.com/sample.jpg"),
        )
        val post = samplePost(
            sourceKey = SourceKey.GELBOORU,
            preview = ImageRef(
                url = "https://gelbooru.com/preview.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = media,
            media = listOf(media),
        )

        assertEquals(
            listOf(
                "https://gelbooru.com/sample.jpg",
                "https://gelbooru.com/full.jpg",
            ),
            viewerImageCandidates(post, media),
        )
        assertEquals(
            "https://gelbooru.com/sample.jpg",
            viewerPrefetchImageLocation(post, media),
        )
    }

    @Test
    fun `nhentai viewer candidates try mirrored extension fallbacks`() {
        val media = ImageRef(
            url = "https://i.nhentai.net/galleries/3821534/1.webp",
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(
                "https://i.nhentai.net/galleries/3821534/1.webp",
                "https://i.nhentai.net/galleries/3821534/1.jpg",
                "https://i.nhentai.net/galleries/3821534/1.png",
            ),
        )
        val post = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = ImageRef(
                url = "https://t.nhentai.net/galleries/3821534/thumb.webp",
                localPath = null,
                mime = "image/webp",
            ),
            full = media,
            media = listOf(media),
        )

        assertEquals(
            listOf(
                "https://i.nhentai.net/galleries/3821534/1.webp",
                "https://i.nhentai.net/galleries/3821534/1.jpg",
                "https://i.nhentai.net/galleries/3821534/1.png",
            ),
            viewerImageCandidates(post, media),
        )
        assertEquals(
            "https://i.nhentai.net/galleries/3821534/1.webp",
            viewerPrefetchImageLocation(post, media),
        )
    }

    private fun samplePost(
        sourceKey: SourceKey,
        preview: ImageRef,
        full: ImageRef?,
        media: List<ImageRef>,
    ): Post {
        return Post(
            id = PostId(sourceKey, "123"),
            preview = preview,
            full = full,
            media = media,
            pageUrl = null,
            width = 1000,
            height = 800,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
            creatorProfile = null,
        )
    }
}
