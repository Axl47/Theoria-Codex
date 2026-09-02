package com.theoriacodex.app.related

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RelatedPostsStateTest {
    @Test
    fun `latest request rejects stale completion and filters canonical duplicates`() {
        val seedA = post("a")
        val seedB = post("b")
        val first = RelatedPostsUiState.Idle.begin(seedA, anchorCanonicalIndex = 2, generation = 1)
        val second = first.begin(seedB, anchorCanonicalIndex = 4, generation = 2)

        val stale = second.complete(
            request = first.request,
            incoming = listOf(post("old")),
            canonicalPostIds = emptySet(),
        )
        val completed = stale.complete(
            request = second.request,
            incoming = listOf(seedB, post("canonical"), post("new"), post("new")),
            canonicalPostIds = setOf(PostId(SourceKey.PIXIV, "canonical")),
        )

        assertSame(second, stale)
        assertEquals(listOf("new"), completed.loadedPosts.map { post -> post.id.sourcePostId })
    }

    @Test
    fun `empty failed and seed clear transitions retain request identity`() {
        val loading = RelatedPostsUiState.Idle.begin(post("seed"), 0, 1)
        val empty = loading.complete(loading.request, emptyList(), emptySet())
        val failed = loading.fail(loading.request, "  ")

        assertEquals(loading.request, empty.requestOrNull)
        assertEquals("Could not load related posts", (failed as RelatedPostsUiState.Failed).message)
        assertEquals(RelatedPostsUiState.Idle, failed.clearIfSeed(PostId(SourceKey.PIXIV, "seed")))
        assertSame(failed, failed.clearIfSeed(PostId(SourceKey.PIXIV, "other")))
    }

    private fun post(id: String): Post = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null,
        pageUrl = null,
        width = null,
        height = null,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
