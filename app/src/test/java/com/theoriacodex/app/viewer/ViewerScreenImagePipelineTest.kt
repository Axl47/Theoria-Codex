package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME
import java.net.SocketException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerScreenImagePipelineTest {
    @Test
    fun `viewer media prefetch treats tls and socket failures as unavailable`() = runTest {
        assertFalse(
            nonFatalMediaPrefetch {
                throw SSLPeerUnverifiedException("video-cdn4 hostname mismatch")
            },
        )
        assertFalse(
            nonFatalMediaPrefetch {
                throw SocketException("connection aborted")
            },
        )
    }

    @Test
    fun `viewer media prefetch preserves coroutine cancellation`() = runTest {
        var cancelled = false
        try {
            nonFatalMediaPrefetch { throw CancellationException("stop") }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

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
    fun `hitomi viewer candidates try refreshed primary then alternate`() {
        val primary = "https://w1.gold-usergeneratedcontent.net/current/383/hash.webp"
        val alternate = "https://w2.gold-usergeneratedcontent.net/current/383/hash.webp"
        val media = ImageRef(
            url = primary,
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(primary, alternate),
        )
        val post = samplePost(
            sourceKey = SourceKey.HITOMI,
            preview = media,
            full = media,
            media = listOf(media),
        )

        assertEquals(listOf(primary, alternate), viewerImageCandidates(post, media))
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

    @Test
    fun `viewer gallery media keeps image media indices`() {
        val media = listOf(
            imageRef("https://example.com/1.jpg"),
            imageRef("https://example.com/2.jpg"),
            imageRef("https://example.com/3.jpg"),
        )
        val post = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = imageRef("https://example.com/preview.jpg"),
            full = media.first(),
            media = media,
        )

        assertEquals(
            listOf(0, 1, 2),
            viewerGalleryMediaItems(post).map { it.mediaIndex },
        )
    }

    @Test
    fun `viewer gallery excludes seekable media`() {
        val staticOne = imageRef("https://example.com/1.jpg")
        val gif = ImageRef(
            url = "https://example.com/2.gif",
            localPath = null,
            mime = "image/gif",
        )
        val video = ImageRef(
            url = "https://example.com/3.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        val ugoira = ImageRef(
            url = "https://example.com/4.zip",
            localPath = null,
            mime = PIXIV_UGOIRA_MIME,
        )
        val staticTwo = imageRef("https://example.com/5.jpg")
        val post = samplePost(
            sourceKey = SourceKey.PIXIV,
            preview = imageRef("https://example.com/preview.jpg"),
            full = null,
            media = listOf(staticOne, gif, video, ugoira, staticTwo),
        )

        assertEquals(
            listOf(0, 4),
            viewerGalleryMediaItems(post).map { it.mediaIndex },
        )
    }

    @Test
    fun `viewer gallery requires more than one image`() {
        val singleImagePost = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = imageRef("https://example.com/preview.jpg"),
            full = null,
            media = listOf(imageRef("https://example.com/1.jpg")),
        )
        val twoImagePost = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = imageRef("https://example.com/preview.jpg"),
            full = null,
            media = listOf(
                imageRef("https://example.com/1.jpg"),
                imageRef("https://example.com/2.jpg"),
            ),
        )

        assertFalse(viewerGalleryMediaItems(singleImagePost).size > 1)
        assertTrue(viewerGalleryMediaItems(twoImagePost).size > 1)
    }

    @Test
    fun `viewer gallery tile candidates use fast prefetch location`() {
        val pixivMedia = ImageRef(
            url = "https://i.pximg.net/original.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = listOf("https://i.pximg.net/medium.jpg"),
        )
        val gelbooruMedia = ImageRef(
            url = "https://gelbooru.com/full.jpg",
            localPath = null,
            mime = "image/jpeg",
            progressiveUrls = listOf("https://gelbooru.com/sample.jpg"),
        )
        val nhentaiMedia = ImageRef(
            url = "https://i.nhentai.net/galleries/3821534/1.webp",
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(
                "https://i.nhentai.net/galleries/3821534/1.webp",
                "https://i.nhentai.net/galleries/3821534/1.jpg",
            ),
        )
        val hitomiMedia = ImageRef(
            url = "https://a1.gold-usergeneratedcontent.net/current/1/hash.avif",
            localPath = null,
            mime = "image/avif",
            progressiveUrls = listOf(
                "https://a1.gold-usergeneratedcontent.net/current/1/hash.avif",
                "https://a2.gold-usergeneratedcontent.net/current/1/hash.avif",
            ),
        )
        val cases = listOf(
            SourceKey.PIXIV to pixivMedia,
            SourceKey.GELBOORU to gelbooruMedia,
            SourceKey.NHENTAI to nhentaiMedia,
            SourceKey.HITOMI to hitomiMedia,
        )

        cases.forEach { (sourceKey, media) ->
            val post = samplePost(
                sourceKey = sourceKey,
                preview = imageRef("https://example.com/preview.jpg"),
                full = media,
                media = listOf(media),
            )
            val galleryItem = viewerGalleryMediaItems(post).single()

            assertEquals(
                viewerPrefetchImageLocation(post, galleryItem.media),
                media.progressiveUrls.first(),
            )
        }
    }

    @Test
    fun `horizontal swipe advances media pages before moving to next post`() {
        val nextMedia = viewerHorizontalSwipeTarget(
            currentPostIndex = 0,
            currentMediaIndex = 1,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 5,
            direction = ViewerHorizontalSwipeDirection.Next,
        )
        val nextPost = viewerHorizontalSwipeTarget(
            currentPostIndex = 0,
            currentMediaIndex = 2,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 5,
            direction = ViewerHorizontalSwipeDirection.Next,
        )

        assertEquals(ViewerHorizontalSwipeTarget(postIndex = 0, mediaIndex = 2), nextMedia)
        assertEquals(ViewerHorizontalSwipeTarget(postIndex = 1, mediaIndex = 0), nextPost)
    }

    @Test
    fun `horizontal swipe moves to previous post first page only from current first media page`() {
        val previousMedia = viewerHorizontalSwipeTarget(
            currentPostIndex = 1,
            currentMediaIndex = 1,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 4,
            direction = ViewerHorizontalSwipeDirection.Previous,
        )
        val previousPost = viewerHorizontalSwipeTarget(
            currentPostIndex = 1,
            currentMediaIndex = 0,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 4,
            direction = ViewerHorizontalSwipeDirection.Previous,
        )

        assertEquals(ViewerHorizontalSwipeTarget(postIndex = 1, mediaIndex = 0), previousMedia)
        assertEquals(ViewerHorizontalSwipeTarget(postIndex = 0, mediaIndex = 0), previousPost)
    }

    @Test
    fun `horizontal swipe has no target beyond stream edges`() {
        val beforeFirstPost = viewerHorizontalSwipeTarget(
            currentPostIndex = 0,
            currentMediaIndex = 0,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 0,
            direction = ViewerHorizontalSwipeDirection.Previous,
        )
        val afterLastPost = viewerHorizontalSwipeTarget(
            currentPostIndex = 1,
            currentMediaIndex = 2,
            currentMediaCount = 3,
            postCount = 2,
            targetPostMediaCount = 0,
            direction = ViewerHorizontalSwipeDirection.Next,
        )

        assertEquals(null, beforeFirstPost)
        assertEquals(null, afterLastPost)
    }

    private fun imageRef(url: String): ImageRef {
        return ImageRef(
            url = url,
            localPath = null,
            mime = "image/jpeg",
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
