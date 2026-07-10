package com.theoriacodex.app.viewer

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSessionCoordinatorTest {
    @Test
    fun `requires lazy media resolution for unresolved gallery and video source posts`() {
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.NHENTAI, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.HITOMI, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.IWARA, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.RULE34VIDEO, full = null, media = emptyList())))
        assertTrue(requiresLazyMediaResolution(samplePost(source = SourceKey.RULE34GEN, full = null, media = emptyList())))
    }

    @Test
    fun `Hitomi search opens its animated preview before resolving the full gallery`() {
        val previewOnly = samplePost(source = SourceKey.HITOMI, full = null, media = emptyList())

        assertFalse(requiresPrelaunchViewerPostResolution(previewOnly, ViewerStreamSource.SEARCH))
        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.SEARCH))
        assertTrue(requiresPrelaunchViewerPostResolution(previewOnly, ViewerStreamSource.CODEX))
    }

    @Test
    fun `does not require lazy media resolution when playable media already exists`() {
        assertFalse(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.NHENTAI,
                    full = ImageRef(url = "https://i.nhentai.net/galleries/1/1.jpg", localPath = null, mime = "image/jpeg"),
                    media = emptyList(),
                ),
            ),
        )
        assertFalse(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.PIXIV,
                    full = null,
                    media = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `requires lazy media resolution for refreshable remote video posts`() {
        assertTrue(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.GELBOORU,
                    full = ImageRef(url = "https://video-cdn.gelbooru.com/video.mp4", localPath = null, mime = "video/mp4"),
                    media = emptyList(),
                ),
            ),
        )
        assertTrue(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.IWARA,
                    full = null,
                    media = listOf(
                        ImageRef(url = "https://files.iwara.tv/video.mp4?expires=123", localPath = null, mime = "video/mp4"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `does not require lazy media resolution for cached local video posts`() {
        assertFalse(
            requiresLazyMediaResolution(
                samplePost(
                    source = SourceKey.GELBOORU,
                    full = ImageRef(url = "https://video-cdn.gelbooru.com/video.mp4", localPath = "/cache/video.mp4", mime = "video/mp4"),
                    media = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun `codex remote source posts refresh without trusting stale media shape`() {
        val staleGelbooruPost = samplePost(
            source = SourceKey.GELBOORU,
            full = ImageRef(url = "https://video-cdn.gelbooru.com/stale-media", localPath = null, mime = "image/jpeg"),
            media = emptyList(),
        )

        assertTrue(requiresViewerPostResolution(staleGelbooruPost, ViewerStreamSource.CODEX))
        assertTrue(requiresViewerPostResolution(staleGelbooruPost, ViewerStreamSource.RECENTS))
        assertFalse(requiresViewerPostResolution(staleGelbooruPost, ViewerStreamSource.SEARCH))

        val staleHitomiPost = samplePost(
            source = SourceKey.HITOMI,
            full = ImageRef(url = "https://w1.gold-usergeneratedcontent.net/stale.webp", localPath = null, mime = "image/webp"),
            media = emptyList(),
        )
        assertTrue(requiresViewerPostResolution(staleHitomiPost, ViewerStreamSource.CODEX))
        assertTrue(requiresViewerPostResolution(staleHitomiPost, ViewerStreamSource.RECENTS))
    }

    @Test
    fun `codex preview only gelbooru posts refresh before viewer launch`() {
        val previewOnly = samplePost(
            source = SourceKey.GELBOORU,
            full = null,
            media = emptyList(),
        )

        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.CODEX))
        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.RECENTS))
    }

    @Test
    fun `codex local cached posts do not refresh by source id`() {
        val cachedGelbooruPost = samplePost(
            source = SourceKey.GELBOORU,
            full = ImageRef(url = "https://video-cdn.gelbooru.com/stale-media", localPath = "/cache/media", mime = "image/jpeg"),
            media = emptyList(),
        )

        assertFalse(requiresViewerPostResolution(cachedGelbooruPost, ViewerStreamSource.CODEX))
    }

    @Test
    fun `merges incoming viewer posts without duplicating existing ids`() {
        val first = samplePost(source = SourceKey.PIXIV, sourcePostId = "1")
        val duplicate = samplePost(source = SourceKey.PIXIV, sourcePostId = "1").copy(title = "updated")
        val second = samplePost(source = SourceKey.PIXIV, sourcePostId = "2")

        assertEquals(listOf(first, second), mergeViewerPosts(listOf(first), listOf(duplicate, second)))
    }

    @Test
    fun `merge preserves empty list instances for no-op updates`() {
        val current = listOf(samplePost(source = SourceKey.AIBOORU, sourcePostId = "1"))

        assertSame(current, mergeViewerPosts(current, emptyList()))
    }

    private fun samplePost(
        source: SourceKey,
        sourcePostId: String = "1",
        full: ImageRef? = null,
        media: List<ImageRef> = emptyList(),
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = sourcePostId),
            preview = ImageRef(url = "https://example.com/preview.jpg", localPath = null, mime = "image/jpeg"),
            full = full,
            media = media,
            pageUrl = "https://example.com/post/$sourcePostId",
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
