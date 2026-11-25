package com.theoriacodex.app.feed

import com.theoriacodex.domain.adapter.Page
import com.theoriacodex.domain.adapter.SourceAdapter
import com.theoriacodex.domain.adapter.SourceCapabilities
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchOrchestrator
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class FeedCursorTest {
    private val cursor = FeedCursor(query = Query.newest(SourceKey.GELBOORU))

    @Test
    fun `each source's token follows how it answered, and an unasked source keeps its own (ADR 0069)`() {
        val before = cursor.copy(
            pageTokens = mapOf(
                SourceKey.PIXIV to "p1",
                SourceKey.GELBOORU to "g1",
                SourceKey.DANBOORU to "d1",
                SourceKey.HITOMI to "h1",
                SourceKey.NHENTAI to "n1",
            ),
        )
        val page = UnifiedSearchResult(
            items = emptyList(),
            nextPageTokens = mapOf(
                SourceKey.PIXIV to "p2",
                SourceKey.GELBOORU to " ",
                SourceKey.DANBOORU to "d2",
                SourceKey.HITOMI to "h2",
            ),
            statuses = listOf(
                SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS),
                SourceRunStatus(SourceKey.GELBOORU, SourceRunState.SUCCESS),
                SourceRunStatus(SourceKey.DANBOORU, SourceRunState.EXCLUDED),
                SourceRunStatus(SourceKey.HITOMI, SourceRunState.FAILED),
            ),
        )

        val after = before.accept(page, attempted = setOf(SourceKey.PIXIV, SourceKey.GELBOORU, SourceKey.DANBOORU, SourceKey.HITOMI))

        assertEquals(mapOf(SourceKey.PIXIV to "p2", SourceKey.HITOMI to "h1", SourceKey.NHENTAI to "n1"), after.pageTokens)
    }

    @Test
    fun `a source whose first page failed keeps its null token, so its first page is asked again (ADR 0069)`() {
        val before = cursor.copy(pageTokens = mapOf(SourceKey.PIXIV to null))
        val page = UnifiedSearchResult(
            items = emptyList(),
            nextPageTokens = mapOf(SourceKey.PIXIV to "p2"),
            statuses = listOf(SourceRunStatus(SourceKey.PIXIV, SourceRunState.FAILED)),
        )

        assertEquals(mapOf(SourceKey.PIXIV to null), before.accept(page, attempted = setOf(SourceKey.PIXIV)).pageTokens)
    }

    @Test
    fun `a next page asks a source with a null token for its first page beside one with a saved continuation (ADR 0069)`() {
        val requested = linkedMapOf<SourceKey, String?>()
        fun adapter(source: SourceKey) = object : SourceAdapter {
            override val sourceKey = source
            override val capabilities = SourceCapabilities(
                supportsSortNewest = true,
                supportsSortPopular = true,
                supportsSortTop = true,
                supportsSortRandom = true,
                supportsExcludeTagsServerSide = true,
            )

            override suspend fun search(query: Query, pageToken: String?): Page<Post> {
                requested[source] = pageToken
                return Page(emptyList(), null)
            }

            override suspend fun trendingTags(limit: Int): List<TagSuggestion> = emptyList()
            override suspend fun autocompleteTags(prefix: String, limit: Int): List<TagSuggestion> = emptyList()
            override suspend fun resolvePost(id: PostId): Post? = null
        }
        val orchestrator = UnifiedSearchOrchestrator(
            adaptersBySource = listOf(SourceKey.PIXIV, SourceKey.GELBOORU).associateWith(::adapter),
            providerContext = EmptyCoroutineContext,
        )
        val before = cursor.copy(
            query = cursor.query.copy(mode = QueryMode.Unified),
            pageTokens = mapOf(SourceKey.PIXIV to null, SourceKey.GELBOORU to "g1"),
        )

        runBlocking { orchestrator.nextPage(before, setOf(SourceKey.PIXIV, SourceKey.GELBOORU)) }

        assertEquals(mapOf(SourceKey.PIXIV to null, SourceKey.GELBOORU to "g1"), requested)
    }
}
