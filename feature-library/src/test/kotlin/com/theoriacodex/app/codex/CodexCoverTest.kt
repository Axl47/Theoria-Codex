package com.theoriacodex.app.codex

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexCoverTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `cover candidates include page one fallback and later posts in display order`() {
        val newest = post(
            source = SourceKey.PIXIV,
            id = "newest",
            previewUrl = "https://example.test/broken-preview.jpg",
            media = listOf(
                image("https://example.test/page-1.jpg"),
                image("https://example.test/page-2.jpg"),
            ),
        )
        val older = post(
            source = SourceKey.GELBOORU,
            id = "older",
            previewUrl = "https://example.test/older-preview.jpg",
        )

        val urls = resolveCodexCoverCandidates(tempFolder.root, listOf(newest, older))
            .filterIsInstance<CodexCoverCandidate.RemoteImage>()
            .map(CodexCoverCandidate.RemoteImage::url)

        assertEquals("https://example.test/broken-preview.jpg", urls.first())
        assertTrue(urls.indexOf("https://example.test/page-1.jpg") in 1 until urls.lastIndex)
        assertTrue("https://example.test/page-2.jpg" !in urls)
        assertEquals("https://example.test/older-preview.jpg", urls.last())
    }

    @Test
    fun `downloaded thumbnail precedes remote candidates`() {
        val post = post(
            source = SourceKey.AIBOORU,
            id = "saved",
            previewUrl = "https://example.test/preview.jpg",
        )
        val thumbnailDirectory = tempFolder.newFolder("cache", "thumbnails")
        val thumbnail = thumbnailDirectory.resolve("AIBOORU_saved.webp")
        thumbnail.writeBytes(byteArrayOf(1, 2, 3))

        val candidates = resolveCodexCoverCandidates(tempFolder.root, listOf(post))

        assertEquals(CodexCoverCandidate.LocalFile(thumbnail), candidates.first())
        assertTrue(
            candidates.contains(
                CodexCoverCandidate.RemoteImage(
                    source = SourceKey.AIBOORU,
                    url = "https://example.test/preview.jpg",
                ),
            ),
        )
    }

    private fun post(
        source: SourceKey,
        id: String,
        previewUrl: String,
        media: List<ImageRef> = emptyList(),
    ): Post = Post(
        id = PostId(source = source, sourcePostId = id),
        preview = image(previewUrl),
        full = null,
        media = media,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )

    private fun image(url: String): ImageRef = ImageRef(
        url = url,
        localPath = null,
        mime = "image/jpeg",
    )
}
