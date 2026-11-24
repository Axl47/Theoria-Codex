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
    private val boorus = listOf(SourceKey.AIBOORU, SourceKey.DANBOORU, SourceKey.GELBOORU, SourceKey.RULE34XXX, SourceKey.RULE34PAHEAL)

    @Test
    fun `remote video on a built-in booru or a custom site refreshes its link, a saved copy does not (ADR 0105)`() {
        val sources = boorus + customSite + SourceKey.PIXIV

        assertEquals(
            sources.associateWith { it != SourceKey.PIXIV },
            sources.associateWith { source -> requiresLazyMediaResolution(post(source, video)) },
        )
        assertEquals(false, requiresLazyMediaResolution(post(SourceKey.GELBOORU, video.copy(localPath = "/saved/v.mp4"))))
    }

    @Test
    fun `an episode's streams are fetched as it opens, not resolved lazily (ADR 0105)`() {
        val episode = video.copy(streams = listOf(MediaStream(url = "https://cdn.example.test/e.m3u8", label = "1080p")))

        assertEquals(false, requiresLazyMediaResolution(post(SourceKey.GELBOORU, episode)))
        assertEquals(false, requiresLazyMediaResolution(post(customSite, episode)))
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
