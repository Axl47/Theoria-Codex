package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingDateRangeTest {
    private val day = DateRange.DAY_MS

    private val source = adapter(post("chosen-week", 95 * day), post("this-week", 108 * day))
    private val orchestrator = testOrchestrator(mapOf(SourceKey.DANBOORU to source), clock = { 110 * day })

    @Test
    fun `a trailing date range is measured from each run, not from when it was chosen (ADR 0084)`() = runTest {
        val result = orchestrator.search(lastWeek(QueryMode.Unified), setOf(SourceKey.DANBOORU), emptyMap(), emptyMap())

        assertEquals(listOf("this-week"), result.items.map { it.id.sourcePostId })
    }

    @Test
    fun `a single-source search drops posts outside its date range too (ADR 0084)`() = runTest {
        val page = orchestrator.searchSource(source, lastWeek(QueryMode.Source(SourceKey.DANBOORU)), pageToken = null)

        assertEquals(listOf("this-week"), page.items.map { it.id.sourcePostId })
    }

    private fun lastWeek(mode: QueryMode) = Query(
        mode = mode, includeTerms = listOf(SearchTerm("landscape")), excludeTerms = emptyList(),
        sort = SortMode.NEWEST, dateRange = DateRange.LastDays(7), minScore = null,
    )

    private fun post(id: String, createdAtEpochMs: Long) = Post(
        id = PostId(SourceKey.DANBOORU, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = listOf("landscape"),
        rawTags = emptyList(), authorName = null, createdAtEpochMs = createdAtEpochMs,
    )

    private fun adapter(vararg posts: Post) = object : SourceAdapter {
        override val sourceKey = SourceKey.DANBOORU
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true, supportsSortPopular = true, supportsSortTop = true,
            supportsSortRandom = true, supportsExcludeTagsServerSide = true,
        )
        override suspend fun search(query: Query, pageToken: String?): Page<Post> = Page(posts.toList(), null)
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun resolvePost(id: PostId): Post? = null
    }
}
