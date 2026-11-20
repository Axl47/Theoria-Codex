package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.SearchContinuation
import com.theoriacodex.app.search.SearchExecutionResult
import com.theoriacodex.app.search.SearchPageResult
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A request from its start to each way it can end, as [SearchStateReducer] records it. */
class SearchRequestReducerTest {
    @Test
    fun `begin loading clears old error while retaining visible content`() {
        val existing = post("existing")
        val state = SearchUiState(
            content = SearchContentUiState(
                results = listOf(existing),
                error = SearchErrorUiState("old", SearchRequestKind.RETRY, retryable = true),
            ),
        )
        val submitted = query("new")

        val reduced = state.reduce(SearchStateChange.BeginRequest(root(10L, submitted)))

        assertEquals(listOf(existing), reduced.content.results)
        assertNull(reduced.content.error)
        assertEquals(10L, reduced.execution.activeRequestId)
        assertEquals(submitted, reduced.execution.submittedQuery)
        assertTrue(reduced.loading)
    }

    @Test
    fun `a new search drops what the old results continued from and a page request keeps it`() {
        val shown = root(1L, query("shown"))
        val state = showing(shown.query, listOf(post("one"))).let {
            it.copy(execution = it.execution.copy(continuation = continuation(shown, more = true), pageRetrySource = SourceKey.PIXIV))
        }

        val paging = state.reduce(SearchStateChange.BeginRequest(page(2L, state), retrySource = SourceKey.GELBOORU))
        val replacing = state.reduce(SearchStateChange.BeginRequest(root(2L, query("other"))))

        assertEquals(state.execution.continuation, paging.execution.continuation)
        assertEquals(SourceKey.GELBOORU, paging.execution.pageRetrySource)
        assertNull(replacing.execution.continuation)
        assertNull(replacing.execution.pageRetrySource)
    }

    @Test
    fun `replacement atomically applies query results statuses and paging state`() {
        val request = root(11L, query("replacement"))
        val loading = SearchUiState(query = SearchQueryUiState(draft = request.query))
            .reduce(SearchStateChange.BeginRequest(request))
        val status = SourceRunStatus(SourceKey.GELBOORU, SourceRunState.SUCCESS)
        val replacement = listOf(post("one"), post("two"))
        val answer = success(request, replacement, listOf(status), more = true)

        val completed = loading.reduce(SearchStateChange.RootResult(11L, answer))

        assertEquals(request.query, completed.query.applied)
        assertEquals(request.query, completed.query.draft)
        assertEquals(QueryHash.from(request.query), completed.query.appliedQueryHash)
        assertEquals(replacement, completed.content.results)
        assertEquals(listOf(status), completed.content.statuses)
        assertTrue(completed.content.canLoadMore)
        assertEquals(answer.continuation, completed.execution.continuation)
        assertTrue(completed.content.hasExecutedSearch)
        assertEquals(1, completed.content.displayVersion)
        assertEquals(11L, completed.execution.lastCompletedRequestId)
        assertFalse(completed.loading)
    }

    @Test
    fun `replacement does not overwrite draft edits made after submission`() {
        val request = root(12L, query("submitted"))
        val edited = query("edited while loading")
        val loading = SearchUiState(query = SearchQueryUiState(draft = request.query))
            .reduce(SearchStateChange.BeginRequest(request))
            .copy(query = SearchQueryUiState(draft = edited))

        val completed = loading.reduce(SearchStateChange.RootResult(12L, success(request, emptyList())))

        assertEquals(request.query, completed.query.applied)
        assertEquals(edited, completed.query.draft)
        assertTrue(completed.hasPendingChanges)
    }

    @Test
    fun `each mode remembers its last search and Multi-Search does not`() {
        val pixiv = root(1L, emptySearchQuery(QueryMode.Source(SourceKey.PIXIV)))
        val mixed = root(2L, query("mixed")).copy(
            sourceScope = SearchSourceScope.Temporary(listOf(SourceKey.GELBOORU, SourceKey.PIXIV)),
        )

        val afterPixiv = SearchUiState().reduce(SearchStateChange.BeginRequest(pixiv))
            .reduce(SearchStateChange.RootResult(1L, success(pixiv, emptyList())))
        val afterMixed = afterPixiv.reduce(SearchStateChange.BeginRequest(mixed))
            .reduce(SearchStateChange.RootResult(2L, success(mixed, emptyList())))

        assertEquals(mapOf(modeKey(pixiv.query.mode) to pixiv.query), afterPixiv.query.appliedByMode)
        assertEquals(afterPixiv.query.appliedByMode, afterMixed.query.appliedByMode)
        assertEquals(mixed.query, afterMixed.query.applied)
    }

    @Test
    fun `an answer for another search cancels its request and shows nothing`() {
        val request = root(3L, query("asked"))
        val loading = showing(query("shown"), listOf(post("kept"))).reduce(SearchStateChange.BeginRequest(request))
        val other = success(root(3L, query("other")), listOf(post("wrong")))

        val reduced = loading.reduce(SearchStateChange.RootResult(3L, other))

        assertEquals(loading.content, reduced.content)
        assertEquals(loading.query, reduced.query)
        assertNull(reduced.execution.activeRequestId)
        assertEquals(3L, reduced.execution.lastCancelledRequestId)
    }

    @Test
    fun `a search that fails with an answer becomes the applied search and keeps its continuation`() {
        val request = root(4L, query("failing"))
        val loading = showing(query("shown"), listOf(post("old"))).reduce(SearchStateChange.BeginRequest(request))
        val status = SourceRunStatus(SourceKey.PIXIV, SourceRunState.FAILED, errorMessage = "offline")
        val answer = SearchExecutionResult.Failure(
            request.executionKey, request.query, request.sourceScope, listOf(status), "offline", continuation(request, more = true),
        )

        val failed = loading.reduce(SearchStateChange.RootResult(4L, answer))

        assertEquals(request.query, failed.query.applied)
        assertEquals(request.executionKey, failed.query.appliedQueryHash)
        assertEquals(request.query, failed.query.appliedByMode[modeKey(request.query.mode)])
        assertTrue(failed.content.results.isEmpty())
        assertEquals(listOf(status), failed.content.statuses)
        assertFalse(failed.content.canLoadMore)
        assertEquals(SearchErrorUiState("offline", SearchRequestKind.REPLACE, retryable = true), failed.content.error)
        assertEquals(answer.continuation, failed.execution.continuation)
        assertNull(failed.execution.activeRequestId)
    }

    @Test
    fun `a search that never answers becomes the applied search so Retry repeats it`() {
        val request = root(5L, query("timed out"))
        val loading = showing(query("shown"), listOf(post("old"))).reduce(SearchStateChange.BeginRequest(request))

        val failed = loading.reduce(SearchStateChange.RequestFailed(5L, "Search timed out"))

        assertEquals(request.query, failed.query.applied)
        assertEquals(request.executionKey, failed.query.appliedQueryHash)
        assertEquals(request.query, failed.query.appliedByMode[modeKey(request.query.mode)])
        assertTrue(failed.content.results.isEmpty())
        assertTrue(failed.content.hasExecutedSearch)
        assertEquals("Search timed out", failed.content.error?.message)
    }

    @Test
    fun `retry error retains prior results and records retry context`() {
        val existing = post("retained")
        val initial = showing(query("applied"), listOf(existing))
        val retrying = initial.reduce(
            SearchStateChange.BeginRequest(root(13L, initial.query.applied, SearchRequestKind.RETRY)),
        )

        val failed = retrying.reduce(SearchStateChange.RequestFailed(13L, "network unavailable"))

        assertEquals(listOf(existing), failed.content.results)
        assertEquals("network unavailable", failed.content.error?.message)
        assertEquals(SearchRequestKind.RETRY, failed.content.error?.requestKind)
        assertTrue(failed.content.error?.retryable == true)
        assertNull(failed.execution.activeRequestId)
    }

    @Test
    fun `early results show while the request keeps running`() {
        val request = root(6L, query("early"))
        val loading = SearchUiState(query = SearchQueryUiState(draft = request.query))
            .reduce(SearchStateChange.BeginRequest(request))
        val first = listOf(post("first"))

        val partial = loading.reduce(SearchStateChange.PartialResult(6L, success(request, first, more = true)))
        val stale = partial.reduce(SearchStateChange.PartialResult(5L, success(request, listOf(post("stale")))))

        assertEquals(first, partial.content.results)
        assertEquals(6L, partial.execution.activeRequestId)
        assertTrue(partial.execution.showingPartialResults)
        assertFalse(partial.content.canLoadMore)
        assertNull(partial.execution.continuation)
        assertEquals(request.query, partial.query.applied)
        assertSame(partial, stale)
    }

    @Test
    fun `a cached first page holds back early results and is not yet the mode's search`() {
        val request = root(7L, query("resumed"), SearchRequestKind.RETRY)
        val loading = SearchUiState(query = SearchQueryUiState(draft = request.query, applied = request.query))
            .reduce(SearchStateChange.BeginRequest(request))
        val cached = listOf(post("cached"))

        val painted = loading.reduce(SearchStateChange.ShowCachedFirstPage(7L, cached))
        val early = painted.reduce(SearchStateChange.PartialResult(7L, success(request, listOf(post("early")))))

        assertSame(loading, loading.reduce(SearchStateChange.ShowCachedFirstPage(7L, emptyList())))
        assertEquals(cached, painted.content.results)
        assertEquals(request.executionKey, painted.query.appliedQueryHash)
        assertTrue(painted.execution.showingPartialResults)
        assertTrue(painted.query.appliedByMode.isEmpty())
        assertSame(painted, early)
    }

    @Test
    fun `a scrolled cached page keeps its posts in place when the fresh ones arrive`() {
        val request = root(8L, query("resumed"), SearchRequestKind.RETRY)
        val first = post("first")
        val second = post("second")
        val painted = SearchUiState().reduce(SearchStateChange.BeginRequest(request))
            .reduce(SearchStateChange.ShowCachedFirstPage(8L, listOf(first, second)))
        val newest = post("newest")
        val fresh = success(request, listOf(newest, first.copy(title = "Updated")))

        val atTop = painted.reduce(SearchStateChange.RootResult(8L, fresh))
        val scrolled = painted.reduce(SearchStateChange.CachedFirstPageScrolled)
            .reduce(SearchStateChange.RootResult(8L, fresh))

        assertEquals(fresh.posts, atTop.content.results)
        assertEquals(listOf(first.copy(title = "Updated"), second, newest), scrolled.content.results)
        assertSame(atTop, atTop.reduce(SearchStateChange.CachedFirstPageScrolled))
    }

    @Test
    fun `paging appends unique posts and preserves ordered existing results`() {
        val first = post("first")
        val duplicate = post("duplicate")
        val next = post("next")
        val initial = showing(query("paged"), listOf(first, duplicate))
        val request = page(14L, initial)
        val paging = initial.reduce(SearchStateChange.BeginRequest(request))

        val completed = paging.reduce(SearchStateChange.PageResult(14L, nextPage(request, listOf(duplicate, next))))

        assertEquals(listOf(first.id, duplicate.id, next.id), completed.content.results.map(Post::id))
        assertFalse(completed.content.canLoadMore)
        assertEquals(14L, completed.execution.lastCompletedRequestId)
        assertFalse(completed.loadingMore)
    }

    @Test
    fun `paging leaves out another site's copy of a file already in the results`() {
        val shown = post("shown").copy(contentHash = "0123456789abcdef0123456789abcdef")
        val copy = post("copy").let { it.copy(id = it.id.copy(source = SourceKey.DANBOORU), contentHash = shown.contentHash) }
        val next = post("next")
        val initial = showing(query("paged"), listOf(shown))
        val request = page(15L, initial)

        val completed = initial.reduce(SearchStateChange.BeginRequest(request))
            .reduce(SearchStateChange.PageResult(15L, nextPage(request, listOf(copy, next), more = true)))

        assertEquals(listOf(shown.id, next.id), completed.content.results.map(Post::id))
        assertTrue(completed.content.canLoadMore)
    }

    @Test
    fun `paging merges provider statuses by source without losing exhausted providers`() {
        val pixiv = SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS)
        val exhausted = SourceRunStatus(SourceKey.GELBOORU, SourceRunState.EXCLUDED, errorMessage = "exhausted")
        val initial = showing(query("paged"), emptyList(), listOf(pixiv, exhausted))
        val request = page(22L, initial)
        val refreshedPixiv = SourceRunStatus(SourceKey.PIXIV, SourceRunState.FAILED, errorMessage = "network")

        val completed = initial.reduce(SearchStateChange.BeginRequest(request))
            .reduce(SearchStateChange.PageResult(22L, nextPage(request, emptyList(), listOf(refreshedPixiv))))

        assertEquals(refreshedPixiv, completed.content.statuses.first { it.source == SourceKey.PIXIV })
        assertEquals(exhausted, completed.content.statuses.first { it.source == SourceKey.GELBOORU })
    }

    @Test
    fun `a page succeeding moves the continuation on and forgets the retried source`() {
        val initial = showing(query("paged"), listOf(post("first")))
        val request = page(24L, initial)
        val answer = nextPage(request, listOf(post("second")), more = true)

        val completed = initial.reduce(SearchStateChange.BeginRequest(request, retrySource = SourceKey.PIXIV))
            .reduce(SearchStateChange.PageResult(24L, answer))

        assertEquals(answer.continuation, completed.execution.continuation)
        assertNull(completed.execution.pageRetrySource)
    }

    @Test
    fun `page failure merges provider status and leaves the search and its retry in place`() {
        val pixiv = SourceRunStatus(SourceKey.PIXIV, SourceRunState.SUCCESS)
        val exhausted = SourceRunStatus(SourceKey.GELBOORU, SourceRunState.EXCLUDED, errorMessage = "exhausted")
        val shown = root(1L, query("paged"))
        val initial = showing(shown.query, emptyList(), listOf(pixiv, exhausted)).let {
            it.copy(execution = it.execution.copy(continuation = continuation(shown, more = true)))
        }
        val request = page(23L, initial)
        val loading = initial.reduce(SearchStateChange.BeginRequest(request, retrySource = SourceKey.PIXIV))
        val failedPixiv = SourceRunStatus(SourceKey.PIXIV, SourceRunState.FAILED, errorMessage = "offline")

        val failed = loading.reduce(
            SearchStateChange.PageResult(23L, SearchPageResult.Failure(request.executionKey, listOf(failedPixiv), "page failed")),
        )
        val threw = loading.reduce(SearchStateChange.RequestFailed(23L, "page threw"))

        assertEquals(failedPixiv, failed.content.statuses.first { it.source == SourceKey.PIXIV })
        assertEquals(exhausted, failed.content.statuses.first { it.source == SourceKey.GELBOORU })
        assertEquals(SearchRequestKind.PAGE, failed.content.error?.requestKind)
        for (ended in listOf(failed, threw)) {
            assertTrue(ended.content.canLoadMore)
            assertEquals(initial.query, ended.query)
            assertEquals(initial.execution.continuation, ended.execution.continuation)
            assertEquals(SourceKey.PIXIV, ended.execution.pageRetrySource)
        }
        assertEquals(listOf(pixiv, exhausted).sortedBy { it.source.id }, threw.content.statuses.sortedBy { it.source.id })
    }

    @Test
    fun `a page for a search no longer on screen is cancelled`() {
        val initial = showing(query("paged"), listOf(post("first")))
        val request = page(25L, initial)
        val replaced = initial.reduce(SearchStateChange.BeginRequest(request))
            .let { it.copy(query = it.query.copy(appliedQueryHash = "another search")) }

        val reduced = replaced.reduce(SearchStateChange.PageResult(25L, nextPage(request, listOf(post("late")))))

        assertEquals(replaced.content, reduced.content)
        assertEquals(25L, reduced.execution.lastCancelledRequestId)
    }

    @Test
    fun `stale completion and cancellation cannot replace the active generation`() {
        val old = root(20L, query("old"))
        val current = SearchUiState().reduce(SearchStateChange.BeginRequest(old))
            .reduce(SearchStateChange.BeginRequest(root(21L, query("current"))))

        val afterStaleResult = current.reduce(SearchStateChange.RootResult(20L, success(old, listOf(post("stale")))))
        val afterStaleCancel = afterStaleResult.reduce(SearchStateChange.RequestCancelled(20L))

        assertSame(current, afterStaleResult)
        assertSame(afterStaleResult, afterStaleCancel)
        assertSame(current, current.reduce(SearchStateChange.BeginRequest(old)))
        assertEquals(21L, afterStaleCancel.execution.activeRequestId)

        val cancelled = afterStaleCancel.reduce(SearchStateChange.RequestCancelled(21L))
        assertNull(cancelled.execution.activeRequestId)
        assertEquals(21L, cancelled.execution.lastCancelledRequestId)
        assertNull(cancelled.content.error)
    }

    private fun SearchUiState.reduce(change: SearchStateChange): SearchUiState = SearchStateReducer.reduce(this, change).state

    private fun root(id: Long, query: Query, kind: SearchRequestKind = SearchRequestKind.REPLACE) =
        SearchRequest(id, kind, query)

    /** A page request for the results [state] shows. */
    private fun page(id: Long, state: SearchUiState) = SearchRequest(
        id, SearchRequestKind.PAGE, state.query.applied, state.query.appliedSourceScope, state.query.appliedQueryHash,
    )

    /** [query]'s completed search with [posts] on screen. */
    private fun showing(query: Query, posts: List<Post>, statuses: List<SourceRunStatus> = emptyList()) = SearchUiState(
        query = SearchQueryUiState(draft = query, applied = query, appliedQueryHash = QueryHash.from(query)),
        content = SearchContentUiState(results = posts, statuses = statuses, hasExecutedSearch = true, canLoadMore = true),
    )

    private fun continuation(request: SearchRequest, more: Boolean) = SearchContinuation(
        executionKey = request.executionKey,
        query = request.query,
        sourceScope = request.sourceScope,
        enabledSources = emptySet(),
        availableSources = emptyList(),
        weights = emptyMap(),
        sourcePageToken = "next".takeIf { more },
    )

    private fun success(
        request: SearchRequest,
        posts: List<Post>,
        statuses: List<SourceRunStatus> = emptyList(),
        more: Boolean = false,
    ) = SearchExecutionResult.Success(
        request.executionKey, request.query, request.sourceScope, posts, statuses, continuation(request, more),
    )

    private fun nextPage(
        request: SearchRequest,
        posts: List<Post>,
        statuses: List<SourceRunStatus> = emptyList(),
        more: Boolean = false,
    ) = SearchPageResult.Success(request.executionKey, posts, statuses, continuation(request, more))

    private fun query(tag: String): Query =
        emptySearchQuery().withIncludeTermsAsRequired(listOf(SearchTerm(tag)))

    private fun post(id: String): Post = Post(
        id = PostId(SourceKey.PIXIV, id),
        preview = ImageRef(url = "https://example.test/$id.jpg", localPath = null, mime = "image/jpeg"),
        full = null,
        pageUrl = null,
        width = 100,
        height = 100,
        canonicalTags = emptyList(),
        rawTags = emptyList(),
        authorName = null,
        createdAtEpochMs = null,
    )
}
