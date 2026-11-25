package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant
import com.theoriacodex.sources.http.THEORIA_SOURCE_USER_AGENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostMediaTest {
    @Test
    fun `motion reads the lightest video rendition with the source's headers, or the local copy (ADR 0105)`() {
        val video = ref("file.mp4", "video/mp4").copy(
            videoVariants = listOf(
                VideoVariant("https://example.test/1080.mp4", height = 1080, original = true),
                VideoVariant("https://example.test/480.mp4", height = 480),
                VideoVariant("https://example.test/720.mp4", height = 720),
            ),
        )
        val videoPost = post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg")).copy(media = listOf(video))

        val motion = postMotion(videoPost) as PostMotion.Video
        assertEquals("https://example.test/480.mp4", motion.location)
        assertEquals("https://gelbooru.com/", motion.headers["Referer"])
        assertEquals("Mozilla/5.0", motion.headers["User-Agent"])
        val customSite = requireNotNull(SourceKey.parse("custom_booru_example"))
        val customMotion = postMotion(post(customSite, ref("full.jpg", "image/jpeg")).copy(media = listOf(video))) as PostMotion.Video
        assertEquals("https://example.test/480.mp4", customMotion.location)
        val saved = videoPost.copy(media = listOf(video.copy(localPath = "/data/offline/file.mp4")))
        assertEquals("/data/offline/file.mp4", (postMotion(saved) as PostMotion.Video).location)
    }

    @Test
    fun `a Pixiv animation plays from its archive, while stills and animated images keep their still (ADR 0105)`() {
        val ugoira = post(SourceKey.PIXIV, ref("ugoira.jpg", PIXIV_UGOIRA_MIME))
        assertEquals(PostMotion.Ugoira(ugoira.id), postMotion(ugoira))
        assertNull(postMotion(post(SourceKey.DANBOORU, ref("full.gif", "image/gif"))))
        assertNull(postMotion(post(SourceKey.DANBOORU, ref("full.webp", "image/webp").copy(isAnimated = true))))
        assertNull(postMotion(post(SourceKey.DANBOORU, ref("full.png", "image/apng").copy(isAnimated = true))))
        assertNull(postMotion(post(SourceKey.DANBOORU, ref("full.avif", "image/avif").copy(isAnimated = true))))
        assertNull(postMotion(post(SourceKey.DANBOORU, ref("full.jpg", "image/jpeg"))))
    }

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
    fun `pixiv preview uses saved aspect preserving progressive image (ADR 0105)`() {
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
    fun `gelbooru preview upgrades to its sample but leaves the original to the Viewer (ADR 0105)`() {
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

        val withoutSample = post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg"))
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            previewMediaDeliveryPlan(withoutSample).candidates.map { it.activation },
        )
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.QUALITY_UPGRADE),
            previewMediaDeliveryPlan(withoutSample.copy(width = 800, height = 1_280)).candidates.map { it.activation },
        )
        assertEquals(
            listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.FAILURE_FALLBACK),
            previewMediaDeliveryPlan(withoutSample.copy(width = 800, height = 1_281)).candidates.map { it.activation },
        )
    }

    @Test
    fun `any source with a lighter rendition steps up to the original in the Viewer and on cards (ADR 0105)`() {
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
            val card = previewMediaDeliveryPlan(post).candidates
            assertEquals(MediaDeliveryActivation.QUALITY_UPGRADE, card.first { it.location.endsWith("sample.jpg") }.activation)
            assertEquals(MediaDeliveryActivation.FAILURE_FALLBACK, card.first { it.location.endsWith("full.jpg") }.activation)

            val page = ref("page.jpg", "image/jpeg").copy(progressiveUrls = listOf("https://example.test/page-sample.jpg"))
            val paged = post(source, ref("full.jpg", "image/jpeg")).copy(media = listOf(page))
            assertEquals(
                MediaDeliveryActivation.FAILURE_FALLBACK,
                previewMediaDeliveryPlan(paged).candidates.first { it.location.endsWith("page.jpg") }.activation,
            )

            val small = post(source, ref("full.jpg", "image/jpeg")).copy(width = 800, height = 1_280)
            assertEquals(
                listOf(MediaDeliveryActivation.PRIMARY, MediaDeliveryActivation.QUALITY_UPGRADE),
                previewMediaDeliveryPlan(small).candidates.map { it.activation },
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
    fun `rule34xxx keeps cards on the preview and shows the sample first in the Viewer (ADR 0105)`() {
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
    fun `remote gif paints its poster first and never upgrades to a static sample (ADR 0105)`() {
        listOf("sample.jpg", "sample.jpeg", "sample.png").forEach { sample ->
            val gif = ref("file.gif", "image/gif").copy(progressiveUrls = listOf("https://example.test/$sample"))

            val plan = previewMediaDeliveryPlan(post(SourceKey.GELBOORU, gif))

            assertEquals(
                sample,
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
    }

    @Test
    fun `animated mirrors are alternatives for a single upgrade (ADR 0105)`() {
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
    fun `media requests carry the source's own agent (ADR 0105)`() {
        val danbooru = previewMediaDeliveryPlan(post(SourceKey.DANBOORU, ref("full.jpg", "image/jpeg")))
        val gelbooru = previewMediaDeliveryPlan(post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg")))

        assertEquals(setOf(THEORIA_SOURCE_USER_AGENT), danbooru.candidates.mapTo(mutableSetOf()) { it.requestHeaders["User-Agent"] })
        assertEquals(setOf("Mozilla/5.0"), gelbooru.candidates.mapTo(mutableSetOf()) { it.requestHeaders["User-Agent"] })
        assertEquals(
            "Mozilla/5.0",
            postPreviewImageCandidate(post(SourceKey.GELBOORU, ref("full.jpg", "image/jpeg")))?.requestHeaders?.get("User-Agent"),
        )
        assertEquals(
            THEORIA_SOURCE_USER_AGENT,
            postDownloadMediaCandidate(post(SourceKey.DANBOORU, ref("full.jpg", "image/jpeg")))?.requestHeaders?.get("User-Agent"),
        )
    }

    @Test
    fun `a page shows its renditions lightest first, then its url, tries copies only on failure and never shows its thumbnail (ADR 0006)`() {
        val page = ref("original.jpg", "image/jpeg").copy(
            progressiveUrls = listOf("https://example.test/small.jpg", "https://example.test/medium.jpg"),
            fallbackUrls = listOf("https://mirror.test/original.jpg"),
            thumbnailUrl = "https://example.test/thumb.jpg",
        )
        val gallery = post(SourceKey.HITOMI, page).copy(media = listOf(ref("cover.jpg", "image/jpeg"), page))

        val plan = viewerMediaDeliveryPlan(gallery, page)

        assertEquals(
            listOf(
                "https://example.test/small.jpg" to MediaDeliveryActivation.PRIMARY,
                "https://example.test/medium.jpg" to MediaDeliveryActivation.QUALITY_UPGRADE,
                "https://example.test/original.jpg" to MediaDeliveryActivation.QUALITY_UPGRADE,
                "https://mirror.test/original.jpg" to MediaDeliveryActivation.FAILURE_FALLBACK,
            ),
            plan.candidates.map { it.location to it.activation },
        )
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
