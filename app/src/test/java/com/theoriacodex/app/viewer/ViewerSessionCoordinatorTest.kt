package com.theoriacodex.app.viewer

import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `any route opens a Hitomi preview before resolving the full gallery`() {
        val previewOnly = samplePost(source = SourceKey.HITOMI, full = null, media = emptyList())

        assertFalse(requiresPrelaunchViewerPostResolution(previewOnly))
        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.SEARCH))
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
    fun `codex preview only gelbooru posts open first and refresh inside Viewer`() {
        val previewOnly = samplePost(
            source = SourceKey.GELBOORU,
            full = null,
            media = emptyList(),
        )

        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.CODEX))
        assertTrue(requiresViewerPostResolution(previewOnly, ViewerStreamSource.RECENTS))
        assertFalse(requiresPrelaunchViewerPostResolution(previewOnly))
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

    @Test
    fun `usable media bypasses a delayed provider while Viewer refresh remains required`() = runTest {
        val viable = samplePost(
            source = SourceKey.GELBOORU,
            full = ImageRef(
                url = "https://video-cdn.gelbooru.com/known.mp4",
                localPath = null,
                mime = "video/mp4",
            ),
        )
        val posts = listOf(viable)
        var providerCalls = 0

        val prepared = prepareViewerPostsForLaunch(
            posts = posts,
            context = launchContext(ViewerStreamSource.CODEX),
        ) {
            providerCalls += 1
            error("Viable media must not enter the prelaunch provider path")
        }

        assertSame(posts, prepared)
        assertEquals(0, providerCalls)
        assertTrue(requiresViewerPostResolution(viable, ViewerStreamSource.CODEX))
    }

    @Test
    fun `genuinely unresolved selected post is the only prelaunch blocking path`() = runTest {
        val unresolved = samplePost(
            source = SourceKey.NHENTAI,
            preview = ImageRef(url = null, localPath = null, mime = "image/jpeg"),
            full = null,
            media = emptyList(),
        )
        val untouched = samplePost(source = SourceKey.PIXIV, sourcePostId = "other")
        val resolved = unresolved.copy(
            full = ImageRef(
                url = "https://i.nhentai.net/galleries/1/1.jpg",
                localPath = null,
                mime = "image/jpeg",
            ),
        )
        val providerResult = CompletableDeferred<Post?>()
        val prepared = async {
            prepareViewerPostsForLaunch(
                posts = listOf(untouched, unresolved),
                context = launchContext(ViewerStreamSource.SEARCH, startIndex = 1),
            ) { providerResult.await() }
        }

        runCurrent()
        assertFalse(prepared.isCompleted)
        providerResult.complete(resolved)

        assertEquals(listOf(untouched, resolved), prepared.await())
        assertTrue(requiresPrelaunchViewerPostResolution(unresolved))
    }

    @Test
    fun `local url progressive and preview locations all count as attemptable Viewer media`() {
        val blank = ImageRef(url = null, localPath = null, mime = "image/jpeg")
        val local = samplePost(SourceKey.PIXIV, preview = blank, full = blank.copy(localPath = "/cache/p.jpg"))
        val remote = samplePost(SourceKey.PIXIV, preview = blank, full = blank.copy(url = "https://example.test/p.jpg"))
        val progressive = samplePost(
            SourceKey.PIXIV,
            preview = blank,
            full = blank.copy(progressiveUrls = listOf("https://example.test/progressive.jpg")),
        )
        val preview = samplePost(SourceKey.PIXIV)

        assertTrue(listOf(local, remote, progressive, preview).all(::hasUsableViewerMedia))
        assertFalse(hasUsableViewerMedia(samplePost(SourceKey.PIXIV, preview = blank, full = null)))
    }

    @Test
    fun `known media bypasses prelaunch resolution for the four audited providers`() {
        val gelbooruVideo = samplePost(
            SourceKey.GELBOORU,
            full = ImageRef("https://gelbooru.test/video.mp4", null, "video/mp4"),
        )
        val nhentaiGallery = samplePost(
            SourceKey.NHENTAI,
            media = listOf(ImageRef("https://nhentai.test/1.jpg", null, "image/jpeg")),
        )
        val hitomiSparse = samplePost(SourceKey.HITOMI)
        val pixivStatic = samplePost(
            SourceKey.PIXIV,
            full = ImageRef("https://pixiv.test/original.jpg", null, "image/jpeg"),
        )

        assertTrue(
            listOf(gelbooruVideo, nhentaiGallery, hitomiSparse, pixivStatic).all { post ->
                !requiresPrelaunchViewerPostResolution(post)
            },
        )
    }

    private fun launchContext(
        source: ViewerStreamSource,
        startIndex: Int = 0,
    ) = ViewerLaunchContext(
        queryHash = "test",
        startIndex = startIndex,
        streamSource = source,
        scrollOffsetHint = 0,
    )

    private fun samplePost(
        source: SourceKey,
        sourcePostId: String = "1",
        preview: ImageRef = ImageRef(
            url = "https://example.com/preview.jpg",
            localPath = null,
            mime = "image/jpeg",
        ),
        full: ImageRef? = null,
        media: List<ImageRef> = emptyList(),
    ): Post {
        return Post(
            id = PostId(source = source, sourcePostId = sourcePostId),
            preview = preview,
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
