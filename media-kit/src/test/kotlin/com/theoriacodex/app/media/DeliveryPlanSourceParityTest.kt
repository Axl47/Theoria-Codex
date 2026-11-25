package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryPlanSourceParityTest {
    private val customSite = requireNotNull(SourceKey.parse("custom_booru_example"))
    private val otherCustomSite = requireNotNull(SourceKey.parse("custom_example_org"))
    private val allSources = SourceKey.builtIns + customSite + otherCustomSite
    private val cardsLikeEveryoneElse = allSources.filterNot { it == SourceKey.PIXIV || it == SourceKey.RULE34XXX }

    @Test
    fun `the Viewer plan for the same media is the same for every source (ADR 0074)`() {
        assertTrue(allSources.size > 2)
        allSources.forEach { source ->
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

            assertEquals(
                source.id,
                listOf(
                    "https://example.test/full.jpg" to MediaDeliveryActivation.PRIMARY,
                    "https://example.test/preview.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                ),
                post(source, ref("full.jpg")).viewerPlan(),
            )
        }
    }

    @Test
    fun `the card plan for the same media is the same for every source but Pixiv and rule34xxx (ADR 0074)`() {
        assertTrue(cardsLikeEveryoneElse.size > 2)
        cardsLikeEveryoneElse.forEach { source ->
            val withRenditions = post(source, ref("full.jpg").copy(progressiveUrls = listOf("https://example.test/sample.jpg")))
            assertEquals(source.id, steppedUpCardPlan, withRenditions.previewPlan())
        }
    }

    @Test
    fun `a card without lighter renditions keeps its preview and falls back to the original for every source (ADR 0074)`() {
        allSources.forEach { source ->
            assertEquals(source.id, previewThenOriginal, post(source, ref("full.jpg")).previewPlan())
        }
    }

    @Test
    fun `Pixiv cards start from the medium rendition, which keeps the artwork's framing (ADR 0074)`() {
        val withRenditions = post(SourceKey.PIXIV, ref("full.jpg").copy(progressiveUrls = listOf("https://example.test/sample.jpg")))
        assertEquals(
            listOf(
                "https://example.test/sample.jpg" to MediaDeliveryActivation.PRIMARY,
                "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                "https://example.test/preview.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
            ),
            withRenditions.previewPlan(),
        )
    }

    @Test
    fun `a source whose cards stay on the preview never steps up, though its Viewer does (ADR 0074)`() {
        val withRenditions = post(SourceKey.RULE34XXX, ref("full.jpg").copy(progressiveUrls = listOf("https://example.test/sample.jpg")))
        assertEquals(
            listOf(
                "https://example.test/preview.jpg" to MediaDeliveryActivation.PRIMARY,
                "https://example.test/sample.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
                "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
            ),
            withRenditions.previewPlan(),
        )
    }

    private val steppedUpCardPlan = listOf(
        "https://example.test/preview.jpg" to MediaDeliveryActivation.PRIMARY,
        "https://example.test/sample.jpg" to MediaDeliveryActivation.QUALITY_UPGRADE,
        "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
    )

    private val previewThenOriginal = listOf(
        "https://example.test/preview.jpg" to MediaDeliveryActivation.PRIMARY,
        "https://example.test/full.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
    )

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
