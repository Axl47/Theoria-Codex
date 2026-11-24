package com.theoriacodex.app.feed

import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.orchestration.UnifiedSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
