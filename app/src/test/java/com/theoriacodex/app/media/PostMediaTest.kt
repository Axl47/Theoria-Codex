package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PostMediaTest {
    @Test
    fun `playback keeps source headers`() {
        val video = ref("file.mp4", "video/mp4")
        val videoPost = post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg")).copy(media = listOf(video))
        assertEquals(video, postPlaybackMediaCandidate(videoPost)?.ref)
        assertEquals("https://gelbooru.com/", postDownloadMediaCandidate(videoPost)?.requestHeaders?.get("Referer"))
    }

    @Test
    fun `gelbooru normalization and media fallback remain app-owned`() {
        val legacy = ref("https://video-cdn4.gelbooru.com/videos/file.mp4", "video/mp4", absolute = true)
        val legacyPost = post(SourceKey.GELBOORU, legacy)
        assertEquals("https://gelbooru.com/videos/file.mp4", postPlaybackMediaCandidate(legacyPost)?.url)
        assertEquals("https://gelbooru.com/videos/file.mp4", postMediaItems(legacyPost).single().url)

        val full = ref("full.jpg", "image/jpeg")
        val withFull = post(SourceKey.AIBOORU, full).copy(media = emptyList())
        assertEquals(listOf(full), postMediaItems(withFull))
        assertEquals(listOf(withFull.preview), postMediaItems(withFull.copy(full = null)))
    }

    @Test
    fun `pixiv preview uses saved aspect preserving progressive image`() {
        val full = ref("original.jpg", "image/jpeg").copy(
            progressiveUrls = listOf(
                "https://example.test/medium.jpg",
                "https://example.test/large.jpg",
            ),
        )
        val pixivPost = post(SourceKey.PIXIV, full)

        val preview = requireNotNull(postPreviewImageCandidate(pixivPost))

        assertEquals("https://example.test/medium.jpg", preview.ref.url)
        assertEquals(
            listOf(
                "https://example.test/large.jpg",
                "https://example.test/original.jpg",
            ),
            preview.ref.progressiveUrls,
        )

        val plan = previewMediaDeliveryPlan(pixivPost)
        assertEquals(
            listOf(
                "https://example.test/medium.jpg",
                "https://example.test/large.jpg",
                "https://example.test/original.jpg",
                "https://example.test/preview.jpg",
            ),
            plan.candidates.map { it.location },
        )
        assertEquals(
            listOf(
                MediaDeliveryActivation.PRIMARY,
                MediaDeliveryActivation.QUALITY_UPGRADE,
                MediaDeliveryActivation.FAILURE_FALLBACK,
                MediaDeliveryActivation.FAILURE_FALLBACK,
            ),
            plan.candidates.map { it.activation },
        )
        assertEquals(listOf(1), plan.upgradeIndicesAfter(0))
        assertEquals(emptyList<Int>(), plan.upgradeIndicesAfter(1))
        assertEquals(
            listOf(MediaDeliveryActivation.QUALITY_UPGRADE),
            viewerMediaDeliveryPlan(pixivPost, full).candidates
                .filter { it.location == "https://example.test/original.jpg" }
                .map { it.activation },
        )
    }

    @Test
    fun `nhentai extension mirrors are failure fallbacks and higher pages never use the cover`() {
        val first = ref("1.webp", "image/webp").copy(
            fallbackUrls = listOf(
                "https://example.test/1.webp",
                "https://example.test/1.jpg",
            ),
        )
        val second = ref("2.webp", "image/webp").copy(
            fallbackUrls = listOf(
                "https://example.test/2.webp",
                "https://example.test/2.jpg",
            ),
        )
        val gallery = post(SourceKey.NHENTAI, first).copy(media = listOf(first, second))

        val firstPlan = viewerMediaDeliveryPlan(gallery, first)
        val secondPlan = viewerMediaDeliveryPlan(gallery, second)

        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            firstPlan.candidates.take(2).map { it.activation },
        )
        assertEquals(
            listOf("https://example.test/2.webp", "https://example.test/2.jpg"),
            secondPlan.candidates.map { it.location },
        )
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            secondPlan.candidates.map { it.activation },
        )
    }

    @Test
    fun `gelbooru preview upgrades to its sample but leaves the original to the Viewer`() {
        val full = ref("full.jpg", "image/jpeg").copy(
            progressiveUrls = listOf("https://example.test/sample.jpg"),
        )
        val post = post(SourceKey.GELBOORU, full)

        val plan = previewMediaDeliveryPlan(post)

        assertEquals(
            listOf(
                "https://example.test/preview.jpg",
                "https://example.test/sample.jpg",
                "https://example.test/full.jpg",
            ),
            plan.candidates.map { it.location },
        )
        assertEquals(
            listOf(
                MediaDeliveryActivation.PRIMARY,
                MediaDeliveryActivation.QUALITY_UPGRADE,
                MediaDeliveryActivation.FAILURE_FALLBACK,
            ),
            plan.candidates.map { it.activation },
        )

        // Without a lighter rendition there is nothing to step through; the original stays a fallback.
        val withoutSample = previewMediaDeliveryPlan(post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg")))
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            withoutSample.candidates.map { it.activation },
        )
    }

    @Test
    fun `any source with a lighter rendition steps up to the original in the Viewer and on cards`() {
        val customSite = requireNotNull(SourceKey.parse("custom_booru_example"))
        listOf(SourceKey.DANBOORU, SourceKey.RULE34PAHEAL, customSite).forEach { source ->
            val full = ref("full.jpg", "image/jpeg").copy(progressiveUrls = listOf("https://example.test/sample.jpg"))
            val post = post(source, full)

            val viewer = viewerMediaDeliveryPlan(post, full)
            assertEquals(
                listOf("https://example.test/sample.jpg", "https://example.test/full.jpg"),
                viewer.candidates.take(2).map { it.location },
            )
            assertEquals(MediaDeliveryActivation.QUALITY_UPGRADE, viewer.candidates[1].activation)
            assertEquals(
                MediaDeliveryActivation.QUALITY_UPGRADE,
                previewMediaDeliveryPlan(post).candidates.first { it.location.endsWith("sample.jpg") }.activation,
            )
        }
    }

    @Test
    fun `equivalent copies never upgrade the Viewer and a page without renditions has no step`() {
        val page = ref("1.webp", "image/webp").copy(fallbackUrls = listOf("https://mirror.test/1.webp"))
        val gallery = post(SourceKey.HITOMI, page)

        val plan = viewerMediaDeliveryPlan(gallery, page)

        assertEquals(
            listOf("https://example.test/1.webp", "https://mirror.test/1.webp"),
            plan.candidates.take(2).map { it.location },
        )
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            plan.candidates.take(2).map { it.activation },
        )
        assertEquals(emptyList<Int>(), plan.upgradeIndicesAfter(0))
    }

    @Test
    fun `rule34xxx keeps cards on the preview and shows the sample first in the Viewer`() {
        val full = ref("full.jpg", "image/jpeg").copy(
            progressiveUrls = listOf("https://example.test/sample.jpg"),
        )
        val post = post(SourceKey.RULE34XXX, full)

        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK, MediaDeliveryActivation.FAILURE_FALLBACK),
            previewMediaDeliveryPlan(post).candidates.map { it.activation },
        )
        val viewer = viewerMediaDeliveryPlan(post, full)
        assertEquals(
            listOf("https://example.test/sample.jpg", "https://example.test/full.jpg"),
            viewer.candidates.take(2).map { it.location },
        )
        assertEquals(MediaDeliveryActivation.QUALITY_UPGRADE, viewer.candidates[1].activation)
    }

    @Test
    fun `remote gif paints its poster first and never upgrades to a static sample`() {
        val gif = ref("file.gif", "image/gif").copy(progressiveUrls = listOf("https://example.test/sample.jpg"))

        val plan = previewMediaDeliveryPlan(post(SourceKey.GELBOORU, gif))

        assertEquals(
            listOf("https://example.test/preview.jpg", "https://example.test/file.gif"),
            plan.candidates.map { it.location },
        )
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.ANIMATION_UPGRADE),
            plan.candidates.map { it.activation },
        )
        assertEquals(listOf(1), plan.upgradeIndicesAfter(0))
        assertEquals(emptyList<Int>(), plan.upgradeIndicesAfter(1))
    }

    @Test
    fun `animated mirrors are alternatives for a single upgrade`() {
        val animated = ref("a.webp", "image/webp").copy(
            isAnimated = true,
            fallbackUrls = listOf("https://example.test/a.webp", "https://mirror.test/a.webp"),
        )

        val plan = previewMediaDeliveryPlan(post(SourceKey.HITOMI, animated))

        assertEquals(
            listOf(
                "https://example.test/preview.jpg",
                "https://example.test/a.webp",
                "https://mirror.test/a.webp",
            ),
            plan.candidates.map { it.location },
        )
        assertEquals(listOf(1, 2), plan.upgradeIndicesAfter(0))
        assertEquals(emptyList<Int>(), plan.upgradeIndicesAfter(1))
    }

    @Test
    fun `saved gif plays directly without waiting on a remote poster`() {
        val saved = ref("file.gif", "image/gif").copy(localPath = "/saved/file.gif")

        val plan = previewMediaDeliveryPlan(post(SourceKey.GELBOORU, saved))

        assertEquals("/saved/file.gif", plan.primary?.location)
        assertEquals(emptyList<Int>(), plan.upgradeIndicesAfter(0))
    }

    @Test
    fun `clipboard tag format deduplicates canonical and raw tags`() {
        val post = post(SourceKey.PIXIV, null).copy(
            canonicalTags = listOf("sky", "-lowres", " sky "),
            rawTags = listOf("cloud", "-lowres", "-sample"),
        )
        assertEquals("sky, cloud\n\n-lowres, -sample", formatPostTagsForClipboard(post))
    }

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

    private fun ref(path: String, mime: String, absolute: Boolean = false): ImageRef = ImageRef(
        url = if (absolute) path else "https://example.test/$path",
        localPath = null,
        mime = mime,
    )
}
