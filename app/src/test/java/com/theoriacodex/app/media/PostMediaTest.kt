package com.theoriacodex.app.media

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PostMediaTest {
    @Test
    fun `preview candidate prefers animated full image and playback keeps source headers`() {
        val animated = post(SourceKey.GELBOORU, ref("file.gif", "image/gif"))
        assertEquals(PostMediaSelectionReason.FULL_ANIMATED_IMAGE, postPreviewImageCandidate(animated)?.reason)

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
