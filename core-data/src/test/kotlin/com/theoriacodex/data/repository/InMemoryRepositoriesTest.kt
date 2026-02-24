package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InMemoryRepositoriesTest {
    @Test
    fun `codex repository creates and tracks items`() = runTest {
        val repo = InMemoryCodexRepository()
        val codex = repo.createCodex("Favorites")
        repo.addItem(codex.codexId, samplePost("1"))

        val codices = repo.observeCodices().first()
        val items = repo.observeCodexItems(codex.codexId).first()

        assertEquals(1, codices.size)
        assertEquals("Favorites", codices.first().name)
        assertEquals(1, items.size)
    }

    @Test
    fun `query repository stores query and scroll offset`() = runTest {
        val repo = InMemoryQueryRepository()
        val query = Query(
            mode = QueryMode.Unified,
            includeTags = listOf("landscape"),
            excludeTags = emptyList(),
            sort = SortMode.NEWEST,
            dateRange = null,
            minScore = null,
        )

        repo.upsertAppliedQuery("unified", query)
        repo.upsertScrollOffset("hash-1", 420)

        assertNotNull(repo.observeAppliedQuery("unified").first())
        assertEquals(420, repo.getScrollOffset("hash-1"))
    }

    @Test
    fun `cache repository tracks thumbnail and full counts`() = runTest {
        val repo = InMemoryCacheRepository()

        repo.cacheThumbnail(samplePost("1"))
        repo.cacheFull(samplePost("2"))

        val snapshot = repo.observeSnapshot().first()

        assertEquals(1, snapshot.thumbnailCount)
        assertEquals(1, snapshot.fullImageCount)
    }

    private fun samplePost(id: String): Post {
        return Post(
            id = PostId(source = SourceKey.PIXIV, sourcePostId = id),
            preview = ImageRef(url = "https://example.com/$id.jpg", localPath = null, mime = "image/jpeg"),
            full = ImageRef(url = "https://example.com/full/$id.jpg", localPath = null, mime = "image/jpeg"),
            pageUrl = "https://example.com/post/$id",
            width = 1000,
            height = 1500,
            canonicalTags = listOf("landscape"),
            rawTags = listOf("landscape"),
            authorName = "artist",
            createdAtEpochMs = 1L,
        )
    }
}
