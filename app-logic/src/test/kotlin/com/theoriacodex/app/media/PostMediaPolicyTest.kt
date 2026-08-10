package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMediaPolicyTest {
    @Test
    fun `media kind detects videos images ugoira and unknown locations`() {
        assertEquals(PostMediaKind.VIDEO, mediaKind("video/mp4", null))
        assertEquals(PostMediaKind.VIDEO, mediaKind(null, "https://x/file.webm?z=1"))
        assertEquals(PostMediaKind.IMAGE, mediaKind(null, "https://x/file.jpg"))
        assertEquals(PostMediaKind.UGOIRA, mediaKind("image/ugoira", null))
        assertEquals(PostMediaKind.UNKNOWN, mediaKind(null, "https://x/file"))
    }

    @Test
    fun `animated policy covers videos gifs animated webp ugoira and unresolved video sources`() {
        val animatedWebp = ImageRef("https://x/a.webp", null, "image/webp", isAnimated = true)
        assertTrue(isAnimatedImageMediaRef(animatedWebp))
        assertFalse(isGifMediaRef(animatedWebp))
        assertTrue(isAnimatedPost(post(SourceKey.GELBOORU, full = ref("file.mp4", "video/mp4"))))
        assertTrue(isAnimatedPost(post(SourceKey.AIBOORU, full = ref("file.gif", "image/gif"))))
        assertTrue(isPixivUgoiraPost(post(SourceKey.PIXIV, full = ref("file.zip", "image/ugoira"))))
        assertFalse(isPixivUgoiraPost(post(SourceKey.GELBOORU, full = ref("file.zip", "image/ugoira"))))
        assertTrue(isAnimatedPost(post(SourceKey.IWARA, full = null)))
        assertFalse(isAnimatedPost(post(SourceKey.HITOMI, full = ref("file.webp", "image/webp"))))
    }

    @Test
    fun `duration range normalizes and maps exact bucket boundaries`() {
        assertEquals(0, durationBucketFor(4_999L))
        assertEquals(1, durationBucketFor(5_000L))
        assertEquals(24, durationBucketFor(120_000L))
        assertEquals(25, durationBucketFor(120_001L))
        assertTrue(AnimatedDurationRange(1, 2).contains(7_500L))
        assertTrue(AnimatedDurationRange(1, 2).contains(10_000L))
        assertFalse(AnimatedDurationRange(1, 2).contains(10_001L))
        assertTrue(AnimatedDurationRange(0, 0).contains(4_999L))
        assertFalse(AnimatedDurationRange(0, 0).contains(5_000L))
        assertTrue(AnimatedDurationRange(25, 25).contains(120_001L))
        assertFalse(AnimatedDurationRange(25, 25).contains(120_000L))
        assertEquals(AnimatedDurationRange.Full, AnimatedDurationRange(-4, 99).let {
            AnimatedDurationRange(it.normalizedMinBucket, it.normalizedMaxBucket)
        })
        assertEquals("5s - 10s", animatedDurationRangeLabel(AnimatedDurationRange(2, 1)))
    }

    @Test
    fun `animated duration label uses compact hours minutes and seconds`() {
        assertEquals("1s", animatedDurationLabel(animatedPost(durationMs = 999L)))
        assertEquals("21s", animatedDurationLabel(animatedPost(durationMs = 21_900L)))
        assertEquals("1m10s", animatedDurationLabel(animatedPost(durationMs = 70_000L)))
        assertEquals("1h27m10s", animatedDurationLabel(animatedPost(durationMs = 5_230_000L)))
        assertEquals("1h", animatedDurationLabel(animatedPost(durationMs = 3_600_000L)))
    }

    @Test
    fun `duration label is absent for static or unresolved media`() {
        val staticPost = post(
            source = SourceKey.GELBOORU,
            full = ref("file.jpg", "image/jpeg"),
        )

        assertEquals(null, animatedDurationLabel(staticPost))
        assertEquals(null, animatedDurationLabel(animatedPost(durationMs = null)))
    }

    @Test
    fun `resolved presentation keeps a duration acquired after card media resolution`() {
        val original = animatedPost(durationMs = 42_000L)
        val resolvedWithoutDuration = original.copy(
            full = ref("resolved.mp4", "video/mp4"),
            media = listOf(ref("resolved.mp4", "video/mp4")),
            durationMs = null,
        )

        assertEquals(
            resolvedWithoutDuration.copy(durationMs = 42_000L),
            mergeResolvedPostForPresentation(original, resolvedWithoutDuration),
        )
        assertEquals(
            resolvedWithoutDuration.copy(durationMs = 21_000L),
            mergeResolvedPostForPresentation(
                original,
                resolvedWithoutDuration.copy(durationMs = 21_000L),
            ),
        )
        assertEquals(
            original,
            mergeResolvedPostForPresentation(
                original,
                resolvedWithoutDuration.copy(id = PostId(SourceKey.GELBOORU, "other")),
            ),
        )
    }

    private fun animatedPost(durationMs: Long?): Post = post(
        source = SourceKey.GELBOORU,
        full = ref("file.mp4", "video/mp4"),
    ).copy(durationMs = durationMs)

    private fun post(source: SourceKey, full: ImageRef?): Post = Post(
        id = PostId(source, "1"),
        preview = ref("preview.jpg", "image/jpeg"),
        full = full,
        media = full?.let(::listOf).orEmpty(),
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = null,
    )

    private fun ref(path: String, mime: String): ImageRef =
        ImageRef("https://example.test/$path", null, mime)
}
