package com.theoriacodex.app.codex

import com.theoriacodex.app.recommend.forYouSourceQuery
import com.theoriacodex.app.search.SearchContinuation
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchExecutionResult
import com.theoriacodex.app.search.SearchPageResult
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SavedSearchBranch(
    val entry: SavedSearchEntry,
    val parts: List<SavedSearchQueryBranch> = emptyList(),
    val requestError: String? = null,
) : LiveCodexBranch {
    override val id get() = entry.id
    override val posts get() = parts.flatMap { it.posts }.distinctBy(Post::id)
    override val canLoadMore get() = requestError == null && parts.any { it.canLoadMore }
    override val error get() = requestError ?: parts.mapNotNull { it.error }.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

internal data class SavedSearchQueryBranch(
    val query: Query,
    val scope: SearchSourceScope,
    val posts: List<Post> = emptyList(),
    val continuation: SearchContinuation? = null,
    val started: Boolean = false,
    val failures: Map<SourceKey, String> = emptyMap(),
    val requestError: String? = null,
) {
    val canLoadMore get() = requestError == null && continuation?.canLoadMore == true
    val error get() = requestError ?: failures.values.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** Read-only query execution: never persists applied Search, Recents, statistics, or new recommendation seeds. */
internal class SavedSearchFeedLoader(
    private val coordinator: SearchCoordinator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var availableSources = coordinator.availableSources.toSet()
    fun updateAvailableSources(sources: Set<SourceKey>) { availableSources = sources }

    suspend fun load(branch: SavedSearchBranch, retry: Boolean): SavedSearchBranch = withContext(dispatcher) {
        try {
            withTimeoutOrNull(15_000L) {
                val parts = branch.parts.ifEmpty { plans(branch.entry) }
                branch.copy(parts = parts.mapConcurrent(concurrency = 2) { part ->
                    when {
                        retry && (part.error != null || branch.requestError != null) -> retry(part)
                        !retry && (!part.started || part.canLoadMore) -> advance(part)
                        else -> part
                    }
                }, requestError = null)
            } ?: branch.copy(requestError = "Saved search timed out")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            branch.copy(requestError = error.message ?: "Could not load saved search")
        }
    }

    private fun plans(entry: SavedSearchEntry): List<SavedSearchQueryBranch> {
        val search = entry.search
        return if (search.kind == RecentSearchKind.FYP) {
            check(search.sources.isNotEmpty()) { "Saved For You search has no sources" }
            search.sources.map { source ->
                val tags = checkNotNull(search.sourceTags[source]) { "Saved For You query is missing" }
                SavedSearchQueryBranch(forYouSourceQuery(source, tags, search.query.sort), SearchSourceScope.Single(source))
            }
        } else listOf(SavedSearchQueryBranch(search.query,
            if (search.kind == RecentSearchKind.MULTI_SEARCH) SearchSourceScope.fromSources(search.sources)
            else SearchSourceScope.fromQuery(search.query),
        ))
    }

    private suspend fun advance(part: SavedSearchQueryBranch): SavedSearchQueryBranch {
        if (!availableSources.containsAll(part.scope.explicitSources)) return part.copy(requestError = "Saved search sources are unavailable")
        return if (!part.started) {
            when (val result = coordinator.executeInitial(part.query, part.scope)) {
                is SearchExecutionResult.Success -> part.copy(
                    posts = result.posts.distinctBy(Post::id), continuation = result.continuation,
                    started = true, failures = failures(emptyMap(), result.statuses), requestError = null,
                )
                is SearchExecutionResult.Failure -> part.copy(
                    continuation = result.continuation, started = result.continuation != null,
                    failures = failures(part.failures, result.statuses), requestError = result.message,
                )
            }
        } else part.continuation?.let { part.accept(coordinator.executePage(it)) } ?: part
    }

    private suspend fun retry(part: SavedSearchQueryBranch): SavedSearchQueryBranch {
        if (!part.started || part.continuation == null) return advance(part.copy(started = false))
        val failed = part.failures.keys + part.continuation.failedSources
        if (failed.isEmpty()) return advance(part)
        val attempts = failed.toList().mapConcurrent(concurrency = 2) { source ->
            source to coordinator.retrySource(part.query, part.scope, part.continuation, source)
        }
        return attempts.fold(part.copy(requestError = null)) { current, (source, result) -> current.accept(result, source) }
    }

    private fun SavedSearchQueryBranch.accept(result: SearchPageResult, retriedSource: SourceKey? = null): SavedSearchQueryBranch =
        when (result) {
            is SearchPageResult.Failure -> {
                val failed = failures(failures, result.statuses)
                val next = continuation?.copy(failedSources = continuation.failedSources + failed.keys)
                copy(failures = failed, continuation = next,
                    requestError = result.message.takeUnless { scope !is SearchSourceScope.Single && next?.canLoadMore == true })
            }
            is SearchPageResult.Success -> {
                val next = if (retriedSource == null || scope is SearchSourceScope.Single) result.continuation else {
                    val before = checkNotNull(continuation)
                    before.copy(
                        unifiedPageTokens = before.unifiedPageTokens +
                            (retriedSource to result.continuation.unifiedPageTokens[retriedSource]),
                        unifiedQueryOverrides = before.unifiedQueryOverrides +
                            result.continuation.unifiedQueryOverrides.filterKeys { it == retriedSource },
                        failedSources = before.failedSources - retriedSource,
                    )
                }
                copy(posts = (posts + result.posts).distinctBy(Post::id), continuation = next,
                    failures = failures(failures, result.statuses), requestError = null)
            }
        }

    private fun failures(previous: Map<SourceKey, String>, statuses: List<SourceRunStatus>): Map<SourceKey, String> {
        val successful = statuses.filter { it.state == SourceRunState.SUCCESS }.map { it.source }.toSet()
        return (previous - successful) + statuses.filter { it.state == SourceRunState.FAILED }.associate {
            it.source to (it.errorMessage ?: "${it.source.name} could not load")
        }
    }
}
