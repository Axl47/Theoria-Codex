package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.pixiv.PIXIV_UGOIRA_MIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMediaTest {
    @Test
    fun `media kind detects videos from mime and extension`() {
        assertEquals(PostMediaKind.VIDEO, mediaKind(mime = "video/mp4", location = null))
        assertEquals(PostMediaKind.VIDEO, mediaKind(mime = null, location = "https://x/y/file.webm?z=1"))
        assertEquals(PostMediaKind.IMAGE, mediaKind(mime = null, location = "https://x/y/file.jpg"))
        assertEquals(PostMediaKind.UNKNOWN, mediaKind(mime = null, location = "https://x/y/file"))
    }

    @Test
    fun `animated detection includes video and gif`() {
        val videoPost = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.mp4", localPath = null, mime = "video/mp4"),
        )
        val gifPost = samplePost(
            source = SourceKey.AIBOORU,
            preview = ImageRef(url = "https://aibooru.online/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://aibooru.online/file.gif", localPath = null, mime = "image/gif"),
        )
        val staticPost = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.jpg", localPath = null, mime = "image/jpeg"),
        )

        assertTrue(isAnimatedPost(videoPost))
        assertTrue(isAnimatedPost(gifPost))
        assertFalse(isAnimatedPost(staticPost))
    }

    @Test
    fun `animated image metadata distinguishes animated and static webp`() {
        val animatedWebP = ImageRef(
            url = "https://example.test/animated.webp",
            localPath = null,
            mime = "image/webp",
            isAnimated = true,
        )
        val staticWebP = animatedWebP.copy(
            url = "https://example.test/static.webp",
            isAnimated = false,
        )
        val animatedPost = samplePost(
            source = SourceKey.HITOMI,
            preview = staticWebP,
            full = animatedWebP,
        )
        val staticPost = animatedPost.copy(
            full = staticWebP,
            media = listOf(staticWebP),
        )

        assertTrue(isAnimatedImageMediaRef(animatedWebP))
        assertFalse(isGifMediaRef(animatedWebP))
        assertFalse(isVideoMediaRef(animatedWebP))
        assertFalse(isAnimatedImageMediaRef(staticWebP))
        assertTrue(isAnimatedPost(animatedPost))
        assertFalse(isAnimatedPost(staticPost))
        assertEquals(animatedWebP, postPreviewImageCandidate(animatedPost)?.ref)
        assertEquals(
            PostMediaSelectionReason.FULL_ANIMATED_IMAGE,
            postPreviewImageCandidate(animatedPost)?.reason,
        )
    }

    @Test
    fun `pixiv ugoira detection requires pixiv source`() {
        val pixivUgoira = samplePost(
            source = SourceKey.PIXIV,
            preview = ImageRef(url = "https://i.pximg.net/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://i.pximg.net/file.zip", localPath = null, mime = PIXIV_UGOIRA_MIME),
        )
        val nonPixiv = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.mp4", localPath = null, mime = PIXIV_UGOIRA_MIME),
        )

        assertTrue(isPixivUgoiraPost(pixivUgoira))
        assertFalse(isPixivUgoiraPost(nonPixiv))
    }

    @Test
    fun `iwara posts are treated as animated even before resolve`() {
        val unresolved = samplePost(
            source = SourceKey.IWARA,
            preview = ImageRef(url = "https://i.iwara.tv/thumb.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
        )

        assertTrue(isAnimatedPost(unresolved))
    }

    @Test
    fun `duration buckets map animated duration boundaries`() {
        assertEquals(0, durationBucketFor(4_999L))
        assertEquals(1, durationBucketFor(5_000L))
        assertEquals(1, durationBucketFor(9_999L))
        assertEquals(24, durationBucketFor(120_000L))
        assertEquals(25, durationBucketFor(120_001L))
        assertTrue(AnimatedDurationRange(minBucket = 1, maxBucket = 2).contains(7_500L))
        assertFalse(AnimatedDurationRange(minBucket = 1, maxBucket = 2).contains(15_000L))
    }

    @Test
    fun `preview image candidate prefers animated full images before static preview`() {
        val post = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/file.gif", localPath = null, mime = "image/gif"),
        )

        val candidate = postPreviewImageCandidate(post)

        assertEquals("https://gelbooru.com/file.gif", candidate?.url)
        assertEquals(PostMediaSelectionReason.FULL_ANIMATED_IMAGE, candidate?.reason)
        assertEquals(PostMediaKind.IMAGE, candidate?.kind)
    }

    @Test
    fun `playback and download candidates preserve shared ordering and source headers`() {
        val video = ImageRef(url = "https://video-cdn.gelbooru.com/file.mp4", localPath = null, mime = "video/mp4")
        val post = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(url = "https://gelbooru.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://gelbooru.com/full.jpg", localPath = null, mime = "image/jpeg"),
        ).copy(media = listOf(video))

        val playback = postPlaybackMediaCandidate(post)
        val download = postDownloadMediaCandidate(post)

        assertEquals(video, playback?.ref)
        assertEquals(PostMediaKind.VIDEO, playback?.kind)
        assertEquals("https://video-cdn.gelbooru.com/file.mp4", download?.url)
        assertEquals("https://gelbooru.com/", download?.requestHeaders?.get("Referer"))
    }

    @Test
    fun `legacy numbered gelbooru video cdn locations use the certificate valid host`() {
        val legacyVideo = ImageRef(
            url = "https://video-cdn4.gelbooru.com/videos/file.mp4",
            localPath = null,
            mime = "video/mp4",
        )
        val post = samplePost(
            source = SourceKey.GELBOORU,
            preview = ImageRef(
                url = "https://video-cdn4.gelbooru.com/preview.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
            full = legacyVideo,
        )

        assertEquals("https://gelbooru.com/videos/file.mp4", postPlaybackMediaCandidate(post)?.url)
        assertEquals("https://gelbooru.com/videos/file.mp4", postMediaItems(post).single().url)
        assertEquals("https://gelbooru.com/preview.jpg", postPreviewImageCandidate(post)?.url)
    }

    @Test
    fun `post media items fall back to full then preview when explicit media is absent`() {
        val full = ImageRef(url = "https://aibooru.online/full.jpg", localPath = null, mime = "image/jpeg")
        val withFull = samplePost(
            source = SourceKey.AIBOORU,
            preview = ImageRef(url = "https://aibooru.online/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = full,
        )
        val previewOnly = withFull.copy(full = null, media = emptyList())

        assertEquals(listOf(full), postMediaItems(withFull))
        assertEquals(listOf(previewOnly.preview), postMediaItems(previewOnly))
    }

    @Test
    fun `clipboard tag format deduplicates canonical and raw tags`() {
        val post = samplePost(
            source = SourceKey.PIXIV,
            preview = ImageRef(url = "https://i.pximg.net/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
        ).copy(
            canonicalTags = listOf("sky", "-lowres", " sky "),
            rawTags = listOf("cloud", "-lowres", "-sample"),
        )

        assertEquals("sky, cloud\n\n-lowres, -sample", formatPostTagsForClipboard(post))
    }

    private fun samplePost(
        source: SourceKey,
        preview: ImageRef,
        full: ImageRef?,
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = preview,
            full = full,
            media = full?.let { listOf(it) }.orEmpty(),
            pageUrl = null,
            width = null,
            height = null,
            canonicalTags = emptyList(),
            rawTags = emptyList(),
            authorName = null,
            createdAtEpochMs = null,
            title = null,
        )
    }
}
