package com.theoriacodex.app.search

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.app.media.MediaDeliveryActivation
import com.theoriacodex.app.media.previewMediaDeliveryPlan
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScreenMediaBehaviorTest {
    @Test
    fun `iwara and hitomi cards remain poster only in search`() {
        assertFalse(allowsInlineAutoplayInSearch(samplePost(SourceKey.IWARA)))
        assertFalse(allowsInlineAutoplayInSearch(samplePost(SourceKey.HITOMI)))
    }

    @Test
    fun `rule34 video family cards allow inline autoplay in search`() {
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.RULE34VIDEO)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.RULE34GEN)))
    }

    @Test
    fun `other image and gallery sources keep inline autoplay enabled in search`() {
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.PIXIV)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.GELBOORU)))
        assertTrue(allowsInlineAutoplayInSearch(samplePost(SourceKey.NHENTAI)))
    }

    @Test
    fun `search card media count uses declared lazy count`() {
        val post = samplePost(SourceKey.NHENTAI).copy(mediaCount = 12)

        assertEquals(12, postMediaCount(post))
    }

    @Test
    fun `search card aspect ratio falls back to square without dimensions`() {
        val post = samplePost(SourceKey.NHENTAI).copy(width = null, height = null)

        assertEquals(1f, previewAspectRatio(post), 0.001f)
    }

    @Test
    fun `search card tries refreshed primary then alternate without duplicates`() {
        val primary = "https://w1.gold-usergeneratedcontent.net/current/383/hash.webp"
        val alternate = "https://w2.gold-usergeneratedcontent.net/current/383/hash.webp"
        val ref = ImageRef(
            url = primary,
            localPath = null,
            mime = "image/webp",
            progressiveUrls = listOf(primary, alternate),
        )

        val plan = previewMediaDeliveryPlan(samplePost(SourceKey.HITOMI).copy(preview = ref))

        assertEquals(listOf(primary, alternate), plan.candidates.map { it.location })
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            plan.candidates.map { it.activation },
        )
    }

    @Test
    fun `search video semantics use a stable resource-safe post identity`() {
        val postId = PostId(SourceKey.RULE34VIDEO, "Benchmark Search/0")

        assertEquals(
            "search_video_rule34video_benchmark_search_0",
            searchVideoTestTag(postId),
        )
        assertEquals(
            "search_card_rule34video_benchmark_search_0",
            searchCardTestTag(postId),
        )
    }

    @Test
    fun `titleless cards omit the footer and overlay required context`() {
        assertEquals(
            SearchCardSupportingContent(
                showTitleFooter = false,
                showPreviewContext = false,
            ),
            searchCardSupportingContent(
                title = null,
                metadataLabel = null,
                showSourceBadge = false,
            ),
        )
        assertEquals(
            SearchCardSupportingContent(
                showTitleFooter = false,
                showPreviewContext = true,
            ),
            searchCardSupportingContent(
                title = null,
                metadataLabel = "2h",
                showSourceBadge = true,
            ),
        )
    }

    @Test
    fun `titled cards retain title footer while secondary context overlays the preview`() {
        assertEquals(
            SearchCardSupportingContent(
                showTitleFooter = true,
                showPreviewContext = true,
            ),
            searchCardSupportingContent(
                title = "A meaningful title",
                metadataLabel = "2h",
                showSourceBadge = true,
            ),
        )
    }

    private fun samplePost(source: SourceKey): Post {
        return Post(
            id = PostId(source = source, sourcePostId = "1"),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
            pageUrl = "https://example.com/post/1",
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
