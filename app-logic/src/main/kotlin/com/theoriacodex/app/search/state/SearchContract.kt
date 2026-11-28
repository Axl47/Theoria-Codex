package com.theoriacodex.app.search.state

import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.NhentaiLanguageFilter
import com.theoriacodex.app.search.SearchContinuation
import com.theoriacodex.app.search.SearchTermLimitNotice
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.searchTermLimitNotices
import com.theoriacodex.data.repository.SearchScrollState
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SearchTermGroup
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.query.QueryHash
import com.theoriacodex.domain.query.SearchTermLimit

data class SearchUiState(
    val query: SearchQueryUiState = SearchQueryUiState(),
    val content: SearchContentUiState = SearchContentUiState(),
    val suggestions: SearchSuggestionsUiState = SearchSuggestionsUiState(),
    val execution: SearchExecutionUiState = SearchExecutionUiState(),
    val restoration: SearchRestorationUiState = SearchRestorationUiState.NotStarted,
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
    val history: SearchQueryHistory = SearchQueryHistory(),
    val editingSavedSearchId: String? = null,
) {
    val canUndoSearch: Boolean get() = history.canStepBack(appliedHistoryEntry())

    val canRedoSearch: Boolean get() = history.canStepForward

    val loading: Boolean
        get() = execution.activeKind != null && execution.activeKind != SearchRequestKind.PAGE

    val retainsResultsWhileRefreshing: Boolean
        get() = loading && content.results.isNotEmpty()

    val refreshingAppliedSearch: Boolean
        get() = retainsResultsWhileRefreshing && execution.submittedQuery == query.applied &&
            execution.request?.sourceScope == query.appliedSourceScope

    val replacingResults: Boolean
        get() = (execution.liveSearchWaiting || loading) && !refreshingAppliedSearch

    val loadingMore: Boolean
        get() = execution.activeKind == SearchRequestKind.PAGE

    val hasPendingChanges: Boolean
        get() = query.draft != query.applied ||
            query.draftSourceScope != query.appliedSourceScope

    val validationMessage: String?
        get() = suggestions.inputError ?: query.validationMessage

    val canResetDraft: Boolean
        get() = query.draft != emptySearchQuery(query.draft.mode) || suggestions.input.isNotEmpty()
}

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
            require(sources == sources.distinct().sortedBy { it.id }) {
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
            val canonical = sources.distinct().sortedBy { it.id }
            return when (canonical.size) {
                0 -> GlobalUnified
                1 -> Single(canonical.single())
                else -> Temporary(canonical)
            }
        }
    }
}

fun SearchSourceScope.sources(unified: Set<SourceKey>, available: Set<SourceKey>): Set<SourceKey> = when (this) {
    SearchSourceScope.GlobalUnified -> unified
    else -> explicitSources.toSet()
}.intersect(available)

fun SearchSourceScope.reconciledWith(available: Set<SourceKey>): SearchSourceScope = when (this) {
    SearchSourceScope.GlobalUnified -> this
    is SearchSourceScope.Single -> SearchSourceScope.fromSources(listOf(source).filter { it in available })
    is SearchSourceScope.Temporary -> SearchSourceScope.fromSources(sources.filter { it in available })
}

fun Query.reconciledWith(scope: SearchSourceScope): Query = when (val mode = scope.queryMode()) {
    QueryMode.Unified -> withIncludeTermGroups(effectiveIncludeTermGroups.filter(SearchTermGroup::isPortableGeneralTagGroup))
        .copy(mode = mode, excludeTerms = excludeTerms.filter(SearchTerm::isPortableGeneralTag))
    is QueryMode.Source -> copy(mode = mode)
}

data class SearchQueryUiState(
    val draft: Query = emptySearchQuery(),
    val applied: Query = emptySearchQuery(),
    val draftSourceScope: SearchSourceScope = SearchSourceScope.GlobalUnified,
    val appliedSourceScope: SearchSourceScope = SearchSourceScope.GlobalUnified,
    val appliedQueryHash: String = "",
    val availableSources: List<SourceKey> = emptyList(),
    val unifiedSources: Set<SourceKey> = emptySet(),
    val scopesBySource: Map<SourceKey, List<FacetedSearchScope>> = emptyMap(),
    val exactTagSources: Map<SourceKey, String> = emptyMap(),
    val selectedScope: FacetedSearchScope = FacetedSearchScope.All,
    val validationMessage: String? = null,
    val sourceTermLimits: Map<SourceKey, SearchTermLimit> = emptyMap(),
    val appliedByMode: Map<String, Query> = emptyMap(),
) {
    val draftSources: Set<SourceKey>
        get() = draftSourceScope.sources(unifiedSources, availableSources.toSet())

    val termLimitNotices: List<SearchTermLimitNotice>
        get() = searchTermLimitNotices(draft, draftSources.sorted(), sourceTermLimits)

    val supportedScopes: List<FacetedSearchScope>
        get() = scopesFor(draft.mode)

    val nhentaiLanguageFilter: NhentaiLanguageFilter
        get() = draft.nhentaiLanguageFilter()

    val nhentaiFullColorFilter: Boolean
        get() = draft.nhentaiFullColorFilter()

    fun scopesFor(mode: QueryMode): List<FacetedSearchScope> =
        (mode as? QueryMode.Source)?.let { scopesBySource[it.source] }.orEmpty()

    val draftExactTagSource: SourceKey?
        get() = (draft.mode as? QueryMode.Source)?.source?.takeIf { it in exactTagSources }
}

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
    val autocomplete: List<TagSuggestion> = emptyList(),
    val facetedAutocomplete: List<FacetedTagSuggestion> = emptyList(),
    val searchedTags: List<SearchTerm> = emptyList(),
    val canCommitInput: Boolean = false,
    val inputError: String? = null,
)

data class SearchExecutionUiState(
    val request: SearchRequest? = null,
    val continuation: SearchContinuation? = null,
    val pageRetrySource: SourceKey? = null,
    val lastCompletedRequestId: Long? = null,
    val lastCancelledRequestId: Long? = null,
    val liveSearchWaiting: Boolean = false,
) {
    val activeRequestId: Long? get() = request?.id
    val activeKind: SearchRequestKind? get() = request?.kind

    val submittedQuery: Query? get() = request?.let { it.shownQuery ?: it.query }

    val showingPartialResults: Boolean get() = request?.shownQuery != null
}

data class SearchRequest(
    val id: Long,
    val kind: SearchRequestKind,
    val query: Query,
    val sourceScope: SearchSourceScope = SearchSourceScope.fromQuery(query),
    val executionKey: String = QueryHash.from(query),
    val shownQuery: Query? = null,
    val cachedFirstPage: CachedFirstPage = CachedFirstPage.NONE,
) {
    val isPage: Boolean get() = kind == SearchRequestKind.PAGE
}

enum class CachedFirstPage {
    NONE,

    SHOWN,

    SCROLLED,
}

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
        val resetToTop: Boolean = false,
        val keepLiveGrid: Boolean = false,
    ) : SearchRestorationUiState

    data class Failed(
        val message: String,
    ) : SearchRestorationUiState
}

sealed interface SearchAction {
    sealed interface DraftEdit : SearchAction

    data class SelectMode(val mode: QueryMode) : SearchAction
    data class ToggleTemporarySource(val source: SourceKey) : SearchAction
    data class SelectSort(val sort: SortMode) : DraftEdit
    data class SetMedia(val media: MediaFilter) : DraftEdit
    data class SetDateRange(val range: DateRange?) : DraftEdit
    data class AddIncludeTerm(val term: SearchTerm) : DraftEdit
    data class AddExcludeTerm(val term: SearchTerm) : DraftEdit
    data class RemoveIncludeTerm(val term: SearchTerm) : DraftEdit
    data class RemoveExcludeTerm(val term: SearchTerm) : DraftEdit
    data class AddIncludeAlternative(val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class AddSavedTagAlternative(val source: SourceKey, val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class AddSavedTagGroup(val terms: List<SearchTerm>) : DraftEdit
    data class SplitIncludeAlternative(val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class RemoveIncludeGroup(val groupIndex: Int) : DraftEdit
    data class RestoreDraftTerms(
        val includeTermGroups: List<SearchTermGroup>,
        val excludeTerms: List<SearchTerm>,
    ) : DraftEdit
    data class SelectSuggestionScope(val scope: FacetedSearchScope) : SearchAction
    data class AutocompleteChanged(val input: String) : SearchAction
    data object RefreshAutocomplete : SearchAction
    data object ClearAutocomplete : SearchAction
    data object UndoSearch : SearchAction
    data object RedoSearch : SearchAction
    data object ApplyDraft : SearchAction
    data object SettleSearch : SearchAction
    data class ApplyHistoricalQuery(
        val query: Query,
        val sourceScope: SearchSourceScope = SearchSourceScope.fromQuery(query),
    ) : SearchAction
    data class EditSavedSearch(
        val id: String,
        val query: Query,
        val sourceScope: SearchSourceScope,
    ) : SearchAction
    data object EndSavedSearchEdit : SearchAction
    data class ApplyTagSearch(
        val includeTermGroups: List<SearchTermGroup>,
        val excludeTerms: List<SearchTerm> = emptyList(),
        val mode: QueryMode = QueryMode.Unified,
    ) : SearchAction
    data object ClearDraft : DraftEdit
    data class RetrySource(val source: SourceKey) : SearchAction
    data object Retry : SearchAction
    data object LoadNextPage : SearchAction
    data object CancelActiveRequest : SearchAction
    data object Restore : SearchAction
    data object Resume : SearchAction
    data class CommitTagInput(val input: String) : SearchAction
    data class StageDraftEdit(val edit: DraftEdit) : SearchAction
    data class AddPostIncludeTerm(val post: Post, val term: SearchTerm) : DraftEdit
    data class AddPostExcludeTerm(val post: Post, val term: SearchTerm) : DraftEdit
    data class AddPostIncludeAlternative(val post: Post, val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class SetNhentaiLanguage(val filter: NhentaiLanguageFilter) : DraftEdit
    data class SetNhentaiFullColor(val enabled: Boolean) : DraftEdit
    data class RememberResolvedPost(val post: Post) : SearchAction
    data class OpenRelatedResult(
        val index: Int,
        val visibleResults: List<Post>,
    ) : SearchAction
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

sealed interface SearchEffect {
    data class OpenViewer(
        val posts: List<Post>,
        val context: ViewerLaunchContext,
        val visibilityFilters: SearchVisibilityFilters = SearchVisibilityFilters(),
        val liveSearchBinding: Boolean = true,
        val fromTypedId: Boolean = false,
    ) : SearchEffect

    data class ShowMessage(
        val message: String,
    ) : SearchEffect

    data object OpenComposer : SearchEffect
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
