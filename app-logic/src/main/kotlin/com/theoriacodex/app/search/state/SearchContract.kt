package com.theoriacodex.app.search.state

import com.theoriacodex.app.search.NhentaiLanguageFilter
import com.theoriacodex.app.search.DateRangePreset
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus

/** Platform-free state rendered by the future Search route. */
data class SearchUiState(
    val query: SearchQueryUiState = SearchQueryUiState(),
    val content: SearchContentUiState = SearchContentUiState(),
    val suggestions: SearchSuggestionsUiState = SearchSuggestionsUiState(),
    val execution: SearchExecutionUiState = SearchExecutionUiState(),
    val restoration: SearchRestorationUiState = SearchRestorationUiState.NotStarted,
) {
    val loading: Boolean
        get() = execution.activeKind != null && execution.activeKind != SearchRequestKind.PAGE

    val loadingMore: Boolean
        get() = execution.activeKind == SearchRequestKind.PAGE

    val hasPendingChanges: Boolean
        get() = query.draft != query.applied ||
            query.draftSourceScope != query.appliedSourceScope
}

/** Route-scoped source selection; it is intentionally separate from the durable QueryMode. */
sealed interface SearchSourceScope {
    val explicitSources: List<SourceKey>

    data object GlobalUnified : SearchSourceScope {
        override val explicitSources: List<SourceKey> = emptyList()
    }

    data class Single(
        val source: SourceKey,
    ) : SearchSourceScope {
        override val explicitSources: List<SourceKey> = listOf(source)
    }

    data class Temporary(
        val sources: List<SourceKey>,
    ) : SearchSourceScope {
        init {
            require(sources.size >= 2) { "Temporary source scope requires at least two sources" }
            require(sources == sources.distinct().sortedBy { it.name }) {
                "Temporary source scope sources must be canonical and distinct"
            }
        }

        override val explicitSources: List<SourceKey> = sources
    }

    companion object {
        fun fromQuery(query: Query): SearchSourceScope {
            return when (val mode = query.mode) {
                QueryMode.Unified -> GlobalUnified
                is QueryMode.Source -> Single(mode.source)
            }
        }

        fun fromSources(sources: Iterable<SourceKey>): SearchSourceScope {
            val canonical = sources.distinct().sortedBy { it.name }
            return when (canonical.size) {
                0 -> GlobalUnified
                1 -> Single(canonical.single())
                else -> Temporary(canonical)
            }
        }
    }
}

data class SearchQueryUiState(
    val draft: Query = emptySearchQuery(),
    val applied: Query = emptySearchQuery(),
    val draftSourceScope: SearchSourceScope = SearchSourceScope.GlobalUnified,
    val appliedSourceScope: SearchSourceScope = SearchSourceScope.GlobalUnified,
    val appliedQueryHash: String = "",
    val availableSources: List<SourceKey> = emptyList(),
    val modeOptions: List<QueryMode> = listOf(QueryMode.Unified),
    val enabledSourceCount: Int = 0,
    val supportedScopes: List<FacetedSearchScope> = emptyList(),
    val selectedScope: FacetedSearchScope = FacetedSearchScope.All,
    val validationMessage: String? = null,
    val nhentaiLanguageFilter: NhentaiLanguageFilter = NhentaiLanguageFilter.ANY,
    val nhentaiFullColorFilter: Boolean = false,
)

data class SearchContentUiState(
    val results: List<Post> = emptyList(),
    val statuses: List<SourceRunStatus> = emptyList(),
    val canLoadMore: Boolean = false,
    val hasExecutedSearch: Boolean = false,
    val error: SearchErrorUiState? = null,
    val displayVersion: Int = 0,
)

data class SearchSuggestionsUiState(
    val input: String = "",
    val trending: List<TagSuggestion> = emptyList(),
    val autocomplete: List<TagSuggestion> = emptyList(),
    val facetedAutocomplete: List<FacetedTagSuggestion> = emptyList(),
    val canCommitInput: Boolean = false,
)

data class SearchExecutionUiState(
    val activeRequestId: Long? = null,
    val activeKind: SearchRequestKind? = null,
    val submittedQuery: Query? = null,
    val lastCompletedRequestId: Long? = null,
    val lastCancelledRequestId: Long? = null,
)

enum class SearchRequestKind {
    INITIAL,
    REPLACE,
    RETRY,
    PAGE,
}

data class SearchErrorUiState(
    val message: String,
    val requestKind: SearchRequestKind?,
    val retryable: Boolean,
)

sealed interface SearchRestorationUiState {
    data object NotStarted : SearchRestorationUiState
    data object Restoring : SearchRestorationUiState

    data class Restored(
        val restoredQuery: Boolean,
        val scrollState: SearchScrollState? = null,
        val scrollRequestId: Long = 0L,
    ) : SearchRestorationUiState

    data class Failed(
        val message: String,
    ) : SearchRestorationUiState
}

/** User intent accepted by the future SearchViewModel. */
sealed interface SearchAction {
    data class SelectMode(val mode: QueryMode) : SearchAction
    data class ToggleTemporarySource(val source: SourceKey) : SearchAction
    data class SelectSort(val sort: SortMode) : SearchAction
    data class SetDateRange(val range: DateRange?) : SearchAction
    data class SetMinimumScore(val score: Int?) : SearchAction
    data class SetDateRangePreset(val preset: DateRangePreset) : SearchAction
    data class AddIncludeTerm(val term: SearchTerm) : SearchAction
    data class AddExcludeTerm(val term: SearchTerm) : SearchAction
    data class RemoveIncludeTerm(val term: SearchTerm) : SearchAction
    data class RemoveExcludeTerm(val term: SearchTerm) : SearchAction
    data class SelectSuggestionScope(val scope: FacetedSearchScope) : SearchAction
    data class AutocompleteChanged(val input: String) : SearchAction
    data class IncludeSuggestion(val suggestion: FacetedTagSuggestion) : SearchAction
    data class ExcludeSuggestion(val suggestion: FacetedTagSuggestion) : SearchAction
    data object ClearAutocomplete : SearchAction
    data object ApplyDraft : SearchAction
    data class ApplyHistoricalQuery(
        val query: Query,
        val sourceScope: SearchSourceScope = SearchSourceScope.fromQuery(query),
    ) : SearchAction
    data class ApplyTagSearch(
        val includeTags: List<String>,
        val mode: QueryMode = QueryMode.Unified,
    ) : SearchAction
    data object ResetDraft : SearchAction
    data object ClearDraft : SearchAction
    data object Retry : SearchAction
    data object LoadNextPage : SearchAction
    data object CancelActiveRequest : SearchAction
    data object DismissError : SearchAction
    data object DismissValidation : SearchAction
    data object Restore : SearchAction
    data object Resume : SearchAction
    data class CommitTagInput(val input: String) : SearchAction
    data class AddPostIncludeTerm(val post: Post, val term: SearchTerm) : SearchAction
    data class AddPostExcludeTerm(val post: Post, val term: SearchTerm) : SearchAction
    data class SetNhentaiLanguage(val filter: NhentaiLanguageFilter) : SearchAction
    data class SetNhentaiFullColor(val enabled: Boolean) : SearchAction
    data object ResetFilters : SearchAction
    data class RememberResolvedPost(val post: Post) : SearchAction
    data class ScrollChanged(
        val firstVisibleItemIndex: Int,
        val firstVisibleItemOffsetPx: Int,
    ) : SearchAction

    data class ScrollRestorationApplied(
        val requestId: Long,
    ) : SearchAction

    data class OpenResult(
        val postId: PostId,
        val visibleResults: List<Post>,
        val scrollOffsetHint: Int,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
    ) : SearchAction
}

/** One-shot work hosted outside Search rendering. */
sealed interface SearchEffect {
    data class OpenViewer(
        val posts: List<Post>,
        val context: ViewerLaunchContext,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
        val liveSearchBinding: Boolean = true,
    ) : SearchEffect

    data class ShowMessage(
        val message: String,
    ) : SearchEffect
}

data class SearchReduction(
    val state: SearchUiState,
    val effects: List<SearchEffect> = emptyList(),
)

fun emptySearchQuery(mode: QueryMode = QueryMode.Unified): Query {
    return Query(
        mode = mode,
        includeTerms = emptyList(),
        excludeTerms = emptyList(),
        sort = SortMode.NEWEST,
        dateRange = null,
        minScore = null,
    )
}
