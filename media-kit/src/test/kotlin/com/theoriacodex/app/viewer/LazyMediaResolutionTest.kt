package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.MediaStream
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class LazyMediaResolutionTest {
    private val video = ImageRef(url = "https://cdn.example.test/v.mp4", localPath = null, mime = "video/mp4")
    private val customSite = requireNotNull(SourceKey.parse("custom_booru_example"))
    private val sources = (SourceKey.builtIns + customSite).toSet()

    @Test
    fun `each source decides whether its remote video refreshes its link, and a saved copy never does (ADR 0105)`() {
        val refreshes = setOf(
            SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.HITOMI, SourceKey.IWARA,
            SourceKey.RULE34XXX, SourceKey.RULE34PAHEAL, SourceKey.RULE34VIDEO, SourceKey.RULE34GEN, customSite,
        )
        val keeps = setOf(SourceKey.PIXIV, SourceKey.NHENTAI)

        assertEquals("Every source needs a decision", sources, refreshes + keeps)
        assertEquals(emptySet<SourceKey>(), refreshes intersect keeps)
        assertEquals(
            sources.associateWith { it in refreshes },
            sources.associateWith { source -> requiresLazyMediaResolution(post(source, video)) },
        )
        assertEquals(
            sources.associateWith { false },
            sources.associateWith { source -> requiresLazyMediaResolution(post(source, video.copy(localPath = "/saved/v.mp4"))) },
        )
    }

    @Test
    fun `each source decides whether a post whose listing named no media address is resolved when opened (ADR 0105)`() {
        val unnamed = ImageRef(url = null, localPath = null, mime = "image/jpeg")
        val resolves = setOf(SourceKey.NHENTAI, SourceKey.HITOMI, SourceKey.IWARA, SourceKey.RULE34VIDEO, SourceKey.RULE34GEN)
        val keeps = setOf(
            SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.AIBOORU, SourceKey.DANBOORU,
            SourceKey.RULE34XXX, SourceKey.RULE34PAHEAL, customSite,
        )

        assertEquals("Every source needs a decision", sources, resolves + keeps)
        assertEquals(emptySet<SourceKey>(), resolves intersect keeps)
        assertEquals(
            sources.associateWith { it in resolves },
            sources.associateWith { source -> requiresLazyMediaResolution(post(source, unnamed)) },
        )
    }

    @Test
    fun `an episode is not resolved lazily (ADR 0105)`() {
        val episode = video.copy(streams = listOf(MediaStream(url = "https://cdn.example.test/e.m3u8", label = "1080p")))

        assertEquals(sources.associateWith { false }, sources.associateWith { source -> requiresLazyMediaResolution(post(source, episode)) })
    }

    @Test
    fun `a gallery whose listing named no pages resolves whatever its source (ADR 0105)`() {
        val pageless = ImageRef(url = null, localPath = null, mime = "image/jpeg")
        val sources = listOf(SourceKey.PIXIV, SourceKey.DANBOORU, customSite)

        assertEquals(
            sources.associateWith { true },
            sources.associateWith { source -> requiresLazyMediaResolution(post(source, pageless).copy(galleryLengthUnknown = true)) },
        )
        assertEquals(false, requiresLazyMediaResolution(post(SourceKey.PIXIV, pageless)))
    }

    private fun post(source: SourceKey, full: ImageRef) = Post(
        id = PostId(source, "1"),
        preview = ImageRef(url = "https://cdn.example.test/preview.jpg", localPath = null, mime = "image/jpeg"),
        full = full,
        media = listOf(full),
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
