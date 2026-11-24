package com.theoriacodex.domain.orchestration

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAnswerPublicationTest {
    @Test
    fun `failures alone publish nothing and the first success publishes after the settle window (ADR 0011)`() = runTest {
        val sources = listOf(
            adapter(SourceKey.GELBOORU, delayMs = 0) { throw SourceAdapterException(SourceFailureReason.NETWORK, "down") },
            adapter(SourceKey.PIXIV, delayMs = 1_000) { listOf(post(SourceKey.PIXIV, "first")) },
            adapter(SourceKey.DANBOORU, delayMs = 10_000) { listOf(post(SourceKey.DANBOORU, "late")) },
        )
        val published = mutableListOf<Pair<Long, List<String>>>()

        testOrchestrator(sources.associateBy { it.sourceKey }).search(
            query(), sources.map { it.sourceKey }.toSet(), emptyMap(), emptyMap(),
            onPartial = { partial -> published += currentTime to partial.items.map { it.id.sourcePostId } },
        )

        assertEquals(listOf(1_400L to listOf("first")), published)
    }

    private fun query() = Query(
        mode = QueryMode.Unified, includeTerms = listOf(SearchTerm("landscape")), excludeTerms = emptyList(),
        sort = SortMode.NEWEST, dateRange = null, minScore = null,
    )

    private fun post(source: SourceKey, id: String) = Post(
        id = PostId(source, id), preview = ImageRef("https://example.com/$id.jpg", null, "image/jpeg"),
        full = null, pageUrl = null, width = null, height = null, canonicalTags = listOf("landscape"),
        rawTags = emptyList(), authorName = null, createdAtEpochMs = null,
    )

    private fun adapter(source: SourceKey, delayMs: Long, answer: () -> List<Post>) = object : SourceAdapter {
        override val sourceKey = source
        override val capabilities = SourceCapabilities(
            supportsSortNewest = true, supportsSortPopular = true, supportsSortTop = true,
            supportsSortRandom = true, supportsExcludeTagsServerSide = true,
        )
        override suspend fun search(query: Query, pageToken: String?): Page<Post> {
            delay(delayMs)
            return Page(answer(), null)
        }
        override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
        override suspend fun resolvePost(id: PostId): Post? = null
    }
}
