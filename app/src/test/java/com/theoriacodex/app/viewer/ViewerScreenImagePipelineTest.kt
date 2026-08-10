package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                "https://i.pximg.net/square_medium.jpg",
            ),
            viewerMediaDeliveryPlan(post, media).candidates.map { it.location },
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

        assertEquals(
            listOf(primary, alternate),
            viewerMediaDeliveryPlan(post, media).candidates.map { it.location },
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
            viewerMediaDeliveryPlan(post, media).candidates.map { it.location },
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
                "https://gelbooru.com/preview.jpg",
            ),
            viewerMediaDeliveryPlan(post, media).candidates.map { it.location },
        )
        assertEquals(
            "https://gelbooru.com/sample.jpg",
            viewerPrefetchImageLocation(post, media),
        )
    }

    @Test
    fun `GIF viewer retains local remote and progressive fallbacks`() {
        val media = ImageRef(
            url = "https://gelbooru.com/images/full.gif",
            localPath = "/missing/cache.gif",
            mime = "image/gif",
            progressiveUrls = listOf(
                "https://gelbooru.com/images/sample.gif",
                "https://gelbooru.com/images/full.gif",
            ),
        )
        val preview = ImageRef(
            url = "https://gelbooru.com/images/preview.gif",
            localPath = null,
            mime = "image/gif",
        )
        val post = samplePost(
            sourceKey = SourceKey.GELBOORU,
            preview = preview,
            full = media,
            media = listOf(media),
        )

        assertEquals(
            listOf(
                "/missing/cache.gif",
                "https://gelbooru.com/images/sample.gif",
                "https://gelbooru.com/images/full.gif",
                "https://gelbooru.com/images/preview.gif",
            ),
            viewerGifLocations(post, media),
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
                "https://t.nhentai.net/galleries/3821534/thumb.webp",
            ),
            viewerMediaDeliveryPlan(post, media).candidates.map { it.location },
        )
        assertEquals(
            "https://i.nhentai.net/galleries/3821534/1.webp",
            viewerPrefetchImageLocation(post, media),
        )
    }

    @Test
    fun `media overview maps every ordered media item with exact indices and kinds`() {
        val preview = imageRef("https://example.com/preview.jpg")
        val staticImage = imageRef("https://example.com/1.jpg")
        val animatedImage = ImageRef(
            url = "https://example.com/2.webp",
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(
                "https://example.com/2.webp",
                "https://example.com/2.avif",
            ),
            isAnimated = true,
        )
        val gif = ImageRef(
            url = "https://example.com/3.gif",
            localPath = null,
            mime = "image/gif",
        )
        val ugoira = ImageRef(
            url = "https://example.com/4.zip",
            localPath = null,
            mime = PIXIV_UGOIRA_MIME,
        )
        val video = ImageRef(
            url = "https://example.com/5.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        val post = samplePost(
            sourceKey = SourceKey.PIXIV,
            preview = preview,
            full = null,
            media = listOf(staticImage, animatedImage, gif, ugoira, video),
        )
        val items = viewerMediaOverviewItems(post)

        assertEquals(listOf(0, 1, 2, 3, 4), items.map { it.mediaIndex })
        assertEquals(
            listOf(
                ViewerMediaOverviewKind.STILL_IMAGE,
                ViewerMediaOverviewKind.ANIMATED_IMAGE,
                ViewerMediaOverviewKind.GIF,
                ViewerMediaOverviewKind.UGOIRA,
                ViewerMediaOverviewKind.VIDEO,
            ),
            items.map { it.kind },
        )
        assertEquals(
            listOf(
                staticImage.url,
                "https://example.com/2.webp",
                preview.url,
                preview.url,
                preview.url,
            ),
            items.map { it.posterLocation },
        )
    }

    @Test
    fun `media overview selects animated webp and leaves it animated`() {
        val animatedOnly = ImageRef(
            url = "https://example.com/page.webp",
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(
                "https://example.com/page.webp",
                "https://mirror.example.com/page.webp",
            ),
            isAnimated = true,
        )
        val post = samplePost(
            sourceKey = SourceKey.HITOMI,
            preview = animatedOnly,
            full = animatedOnly,
            media = listOf(animatedOnly),
        )

        val item = viewerMediaOverviewItems(post).single()

        assertEquals(ViewerMediaOverviewKind.ANIMATED_IMAGE, item.kind)
        assertEquals("https://example.com/page.webp", item.posterLocation)
        assertFalse(shouldDecodeStaticOverviewFrame(item.kind))
    }

    @Test
    fun `Hitomi overview prefers webp for non animated AVIF first media`() {
        val media = ImageRef(
            url = "https://a1.gold-usergeneratedcontent.net/current/1/hash.avif",
            localPath = null,
            mime = "image/avif",
            progressiveUrls = listOf(
                "https://a1.gold-usergeneratedcontent.net/current/1/hash.avif",
                "https://w1.gold-usergeneratedcontent.net/current/1/hash.webp",
                "https://1.gold-usergeneratedcontent.net/images/current/1/hash.jpg",
            ),
        )
        val post = samplePost(
            sourceKey = SourceKey.HITOMI,
            preview = media,
            full = media,
            media = listOf(media),
        )

        assertEquals(
            "https://w1.gold-usergeneratedcontent.net/current/1/hash.webp",
            viewerMediaOverviewItems(post).single().posterLocation,
        )
    }

    @Test
    fun `media overview availability depends only on ordered media count`() {
        val singleItemPost = samplePost(
            sourceKey = SourceKey.NHENTAI,
            preview = imageRef("https://example.com/preview.jpg"),
            full = null,
            media = listOf(imageRef("https://example.com/1.jpg")),
        )
        val seekableMultiItemPost = samplePost(
            sourceKey = SourceKey.PIXIV,
            preview = imageRef("https://example.com/preview.jpg"),
            full = null,
            media = listOf(
                ImageRef(
                    url = "https://example.com/1.mp4",
                    localPath = null,
                    mime = "video/mp4",
                ),
                imageRef("https://example.com/2.jpg"),
            ),
        )

        assertFalse(viewerMediaOverviewAvailable(viewerMediaOverviewItems(singleItemPost)))
        assertTrue(viewerMediaOverviewAvailable(viewerMediaOverviewItems(seekableMultiItemPost)))
    }

    @Test
    fun `still media overview posters use fast prefetch locations`() {
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
            val overviewItem = viewerMediaOverviewItems(post).single()

            assertEquals(
                viewerPrefetchImageLocation(post, overviewItem.media),
                overviewItem.posterLocation,
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
