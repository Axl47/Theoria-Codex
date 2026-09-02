package com.theoriacodex.app.related

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedFeedProjectionTest {
    @Test
    fun `visible seed inserts shelf after seed without changing canonical indices`() {
        val posts = listOf(post("0"), post("1"), post("2"))
        val related = RelatedPostsUiState.Idle.begin(posts[1], 1, 1)

        val projection = buildRelatedFeedProjection(posts, posts, related)

        assertEquals(listOf("0", "1", "shelf", "2"), projection.labels())
        assertEquals(3, projection.gridIndexForCanonicalIndex(2))
        val latest = projection.canonicalPositionForGridIndex(3, 27)
        val shelfUpdate = projection.canonicalPositionForGridIndex(2, 50)
        assertEquals(CanonicalFeedPosition(2, 27), shelfUpdate ?: latest)
        assertEquals(null, shelfUpdate)
        assertEquals(2, projection.greatestVisibleCanonicalIndex(listOf(1, 2, 3)))
    }

    @Test
    fun `hidden seed anchors after nearest visible predecessor`() {
        val posts = listOf(post("0"), post("1"), post("2"), post("3"))
        val visible = listOf(posts[0], posts[3])
        val related = RelatedPostsUiState.Idle.begin(posts[2], 2, 1)

        val projection = buildRelatedFeedProjection(posts, visible, related)

        assertEquals(listOf("0", "shelf", "3"), projection.labels())
        assertEquals(0, projection.gridIndexForCanonicalIndex(2))
        assertEquals(null, projection.canonicalPositionForGridIndex(1, 80))
    }

    @Test
    fun `shelf precedes first visible post when no predecessor exists`() {
        val posts = listOf(post("0"), post("1"), post("2"))
        val related = RelatedPostsUiState.Idle.begin(posts[0], 0, 1)

        val projection = buildRelatedFeedProjection(posts, listOf(posts[2]), related)

        assertEquals(listOf("shelf", "2"), projection.labels())
        assertEquals(null, projection.canonicalPositionForGridIndex(0, 100))
        assertEquals(null, projection.greatestVisibleCanonicalIndex(listOf(0)))
    }

    private fun RelatedFeedProjection.labels(): List<String> = entries.map { entry ->
        when (entry) {
            is RelatedFeedEntry.PostEntry -> entry.post.id.sourcePostId
            is RelatedFeedEntry.ShelfEntry -> "shelf"
        }
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
