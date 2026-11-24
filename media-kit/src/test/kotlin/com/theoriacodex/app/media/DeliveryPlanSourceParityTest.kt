package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryPlanSourceParityTest {
    private val customSite = requireNotNull(SourceKey.parse("custom_booru_example"))
    private val otherCustomSite = requireNotNull(SourceKey.parse("custom_example_org"))

    @Test
    fun `the Viewer plan for the same media is the same for a built-in, Pixiv and a custom source (ADR 0074)`() {
        listOf(SourceKey.GELBOORU, SourceKey.DANBOORU, SourceKey.AIBOORU, SourceKey.PIXIV, customSite, otherCustomSite).forEach { source ->
            val withRenditions = post(source, ref("full.jpg").copy(progressiveUrls = listOf("https://example.test/sample.jpg")))
            assertEquals(
                source.id,
                listOf(
                    "https://example.test/sample.jpg" to MediaDeliveryActivation.PRIMARY,
                    "https://example.test/full.jpg" to MediaDeliveryActivation.QUALITY_UPGRADE,
                    "https://example.test/preview.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                ),
                withRenditions.viewerPlan(),
            )

            val plain = post(source, ref("full.jpg"))
            assertEquals(
                source.id,
                listOf(
                    "https://example.test/full.jpg" to MediaDeliveryActivation.PRIMARY,
                    "https://example.test/preview.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                ),
                plain.viewerPlan(),
            )
        }
    }

    @Test
    fun `the card plan for the same media is the same for a built-in and a custom source, and for Pixiv without renditions (ADR 0074)`() {
        listOf(SourceKey.GELBOORU, SourceKey.DANBOORU, SourceKey.AIBOORU, customSite, otherCustomSite).forEach { source ->
            val withRenditions = post(source, ref("full.jpg").copy(progressiveUrls = listOf("https://example.test/sample.jpg")))
            assertEquals(
                source.id,
                listOf(
                    "https://example.test/preview.jpg" to MediaDeliveryActivation.PRIMARY,
                    "https://example.test/sample.jpg" to MediaDeliveryActivation.QUALITY_UPGRADE,
                    "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                ),
                withRenditions.previewPlan(),
            )
        }
        listOf(SourceKey.GELBOORU, SourceKey.PIXIV, customSite, otherCustomSite).forEach { source ->
            assertEquals(
                source.id,
                listOf(
                    "https://example.test/preview.jpg" to MediaDeliveryActivation.PRIMARY,
                    "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                ),
                post(source, ref("full.jpg")).previewPlan(),
            )
        }
    }

    private fun Post.viewerPlan() = viewerMediaDeliveryPlan(this, requireNotNull(full)).candidates
        .map { it.location to it.activation }

    private fun Post.previewPlan() = previewMediaDeliveryPlan(this).candidates
        .map { it.location to it.activation }

    private fun post(source: SourceKey, full: ImageRef): Post = Post(
        id = PostId(source, "1"),
        preview = ref("preview.jpg"),
        full = full,
        media = listOf(full),
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
        title = null,
    )

    private fun ref(path: String): ImageRef = ImageRef("https://example.test/$path", null, "image/jpeg")
}
