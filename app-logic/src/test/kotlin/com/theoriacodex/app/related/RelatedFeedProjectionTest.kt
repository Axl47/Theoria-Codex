package com.theoriacodex.app.related

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedFeedProjectionTest {
    @Test
    fun `visible seed inserts shelf after seed without changing canonical indices (ADR 0077)`() {
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
    fun `hidden seed anchors after nearest visible predecessor (ADR 0077)`() {
        val posts = listOf(post("0"), post("1"), post("2"), post("3"))
        val visible = listOf(posts[0], posts[3])
        val related = RelatedPostsUiState.Idle.begin(posts[2], 2, 1)

        val projection = buildRelatedFeedProjection(posts, visible, related)

        assertEquals(listOf("0", "shelf", "3"), projection.labels())
        assertEquals(0, projection.gridIndexForCanonicalIndex(2))
        assertEquals(null, projection.canonicalPositionForGridIndex(1, 80))
    }

    @Test
    fun `a late arrival below its neighbours does not pull the shelf away from its seed (ADR 0077)`() {
        val posts = listOf(post("0"), post("1"), post("2"), post("3"), post("4"))
        val related = RelatedPostsUiState.Idle.begin(posts[1], 1, 1)
        val lateFirst = listOf(posts[1], posts[2], posts[0], posts[4])

        val projection = buildRelatedFeedProjection(posts, lateFirst, related)

        assertEquals(listOf("1", "shelf", "2", "0", "4"), projection.labels())
        assertEquals(0, projection.gridIndexForCanonicalIndex(1))
        assertEquals(2, projection.gridIndexForCanonicalIndex(3))
    }

    @Test
    fun `shelf follows its seed when the canonical order changes under it (ADR 0077)`() {
        val posts = listOf(post("0"), post("1"), post("2"))
        val related = RelatedPostsUiState.Idle.begin(posts[2], 2, 1)
        val reordered = listOf(posts[2], posts[0], posts[1])

        val projection = buildRelatedFeedProjection(reordered, reordered, related)

        assertEquals(listOf("2", "shelf", "0", "1"), projection.labels())
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

    @Test
    fun `each post entry carries its index in the filtered list, past posts the feed no longer has`() {
        val posts = listOf(post("0"), post("2"))
        val visible = listOf(post("0"), post("1"), post("2"))

        val projection = buildRelatedFeedProjection(posts, visible, RelatedPostsUiState.Idle)

        assertEquals(
            listOf("0" to 0, "2" to 2),
            projection.entries.map { (it as RelatedFeedEntry.PostEntry).let { entry -> entry.post.id.sourcePostId to entry.visibleIndex } },
        )
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
