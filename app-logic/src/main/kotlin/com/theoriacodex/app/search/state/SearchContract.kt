package com.theoriacodex.app.search.state

import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.DateRangePreset
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

/** Platform-free state rendered by the future Search route. */
data class SearchUiState(
    val query: SearchQueryUiState = SearchQueryUiState(),
    val content: SearchContentUiState = SearchContentUiState(),
    val suggestions: SearchSuggestionsUiState = SearchSuggestionsUiState(),
    val execution: SearchExecutionUiState = SearchExecutionUiState(),
    val restoration: SearchRestorationUiState = SearchRestorationUiState.NotStarted,
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
    val history: SearchQueryHistory = SearchQueryHistory(),
) {
    val canUndoSearch: Boolean get() = history.canStepBack(appliedHistoryEntry())

    val canRedoSearch: Boolean get() = history.canStepForward

    val loading: Boolean
        get() = execution.activeKind != null && execution.activeKind != SearchRequestKind.PAGE

    /** Every search is live, so the results on screen stay until the next search's results replace them. */
    val retainsResultsWhileRefreshing: Boolean
        get() = loading && content.results.isNotEmpty()

    /** The search on screen is running again, so pulling to refresh shows its own indicator. */
    val refreshingAppliedSearch: Boolean
        get() = retainsResultsWhileRefreshing && execution.submittedQuery == query.applied &&
            execution.request?.sourceScope == query.appliedSourceScope

    /** A newer search is waiting out its live pause or loading while the results of the last one stay on screen. */
    val replacingResults: Boolean
        get() = (execution.liveSearchWaiting || loading) && !refreshingAppliedSearch

    val loadingMore: Boolean
        get() = execution.activeKind == SearchRequestKind.PAGE

    val hasPendingChanges: Boolean
        get() = query.draft != query.applied ||
            query.draftSourceScope != query.appliedSourceScope

    /** What the search field shows: an error in the typed input, else a notice about the draft. */
    val validationMessage: String?
        get() = suggestions.inputError ?: query.validationMessage

    /** Reset clears the current draft, including restored searches and uncommitted input. */
    val canResetDraft: Boolean
        get() = query.draft != emptySearchQuery(query.draft.mode) || suggestions.input.isNotEmpty()
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

/** The sources this scope searches among [available]: its own, or for Unified the [unified] ones. */
fun SearchSourceScope.sources(unified: Set<SourceKey>, available: Set<SourceKey>): Set<SourceKey> = when (this) {
    SearchSourceScope.GlobalUnified -> unified
    else -> explicitSources.toSet()
}.intersect(available)

/** This scope without the sources missing from [available]; a scope left with none becomes Unified. */
fun SearchSourceScope.reconciledWith(available: Set<SourceKey>): SearchSourceScope = when (this) {
    SearchSourceScope.GlobalUnified -> this
    is SearchSourceScope.Single -> SearchSourceScope.fromSources(listOf(source).filter { it in available })
    is SearchSourceScope.Temporary -> SearchSourceScope.fromSources(sources.filter { it in available })
}

/** This query in [scope]'s mode; a Unified scope keeps only the terms every source understands. */
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
    /** Every source Search can use, in the order it lists them. */
    val availableSources: List<SourceKey> = emptyList(),
    val unifiedSources: Set<SourceKey> = emptySet(),
    /** The faceted search scopes each available source offers, for those that offer any. */
    val scopesBySource: Map<SourceKey, List<FacetedSearchScope>> = emptyMap(),
    /** Sources whose searches match only exactly spelled tags, each with its name for the input error. */
    val exactTagSources: Map<SourceKey, String> = emptyMap(),
    val selectedScope: FacetedSearchScope = FacetedSearchScope.All,
    /** A notice about the draft itself, such as terms a source change removed; typing leaves it. */
    val validationMessage: String? = null,
    /** Per-request term caps of the available sources that have one. */
    val sourceTermLimits: Map<SourceKey, SearchTermLimit> = emptyMap(),
    /** The last search run in each mode, by [modeKey]; picking a mode starts from it. Multi-Search runs are not kept. */
    val appliedByMode: Map<String, Query> = emptyMap(),
) {
    /** Sources the draft would search: the picked source, a temporary mix, or every Unified source. */
    val draftSources: Set<SourceKey>
        get() = draftSourceScope.sources(unifiedSources, availableSources.toSet())

    val termLimitNotices: List<SearchTermLimitNotice>
        get() = searchTermLimitNotices(draft, draftSources.sorted(), sourceTermLimits)

    /** The faceted scopes the draft's source offers; Unified offers none. */
    val supportedScopes: List<FacetedSearchScope>
        get() = scopesFor(draft.mode)

    val nhentaiLanguageFilter: NhentaiLanguageFilter
        get() = draft.nhentaiLanguageFilter()

    val nhentaiFullColorFilter: Boolean
        get() = draft.nhentaiFullColorFilter()

    fun scopesFor(mode: QueryMode): List<FacetedSearchScope> =
        (mode as? QueryMode.Source)?.let { scopesBySource[it.source] }.orEmpty()

    /** The draft's source when it matches only exactly spelled tags, so a typed tag must come from suggestions. */
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
    /** Terms the user searched before that match [input] and its scope, shown ahead of [autocomplete]. */
    val searchedTags: List<SearchTerm> = emptyList(),
    val canCommitInput: Boolean = false,
    /** Why [input] cannot be searched as typed; replaced with each new answer for the input. */
    val inputError: String? = null,
)

data class SearchExecutionUiState(
    /** The request in flight, if any. */
    val request: SearchRequest? = null,
    /** Where the results on screen continue from; absent while a new search is replacing them. */
    val continuation: SearchContinuation? = null,
    /** The one source the last page request retried, kept so Retry repeats it after a failure. */
    val pageRetrySource: SourceKey? = null,
    val lastCompletedRequestId: Long? = null,
    val lastCancelledRequestId: Long? = null,
    /** A draft edit is waiting out its pause before it runs live. */
    val liveSearchWaiting: Boolean = false,
) {
    val activeRequestId: Long? get() = request?.id
    val activeKind: SearchRequestKind? get() = request?.kind

    /** The active request's query, in the form its results carry once some are on screen. */
    val submittedQuery: Query? get() = request?.let { it.shownQuery ?: it.query }

    /** The active request already put results on screen, so it must not look like a blocking refresh. */
    val showingPartialResults: Boolean get() = request?.shownQuery != null
}

/**
 * One search or page request, from the moment it starts until it ends. [SearchStateReducer] decides
 * everything about its ending from this record and the source's answer.
 */
data class SearchRequest(
    val id: Long,
    val kind: SearchRequestKind,
    /** The query as asked. A source may answer with a normalized form, kept in [shownQuery]. */
    val query: Query,
    val sourceScope: SearchSourceScope = SearchSourceScope.fromQuery(query),
    /** Names the search this request belongs to; an answer carrying another key is not this request's. */
    val executionKey: String = QueryHash.from(query),
    /** Set once this request has put results on screen while still running. */
    val shownQuery: Query? = null,
    val cachedFirstPage: CachedFirstPage = CachedFirstPage.NONE,
) {
    val isPage: Boolean get() = kind == SearchRequestKind.PAGE
}

/** Whether the last run's first page is on screen while its search reruns, and whether the user has moved in it. */
enum class CachedFirstPage {
    NONE,

    /** Partial results would only make the page flicker, so they wait for the complete answer. */
    SHOWN,

    /** The user keeps their place: the shown posts stay put and new ones go below. */
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
        /** A resuming route only restores a grid that lost its place; a live grid is already right. */
        val keepLiveGrid: Boolean = false,
    ) : SearchRestorationUiState

    data class Failed(
        val message: String,
    ) : SearchRestorationUiState
}

/** User intent accepted by the future SearchViewModel. */
sealed interface SearchAction {
    /** Actions that edit the draft query; each one runs the draft live after a short pause. */
    sealed interface DraftEdit : SearchAction

    data class SelectMode(val mode: QueryMode) : SearchAction
    data class ToggleTemporarySource(val source: SourceKey) : SearchAction
    data class SelectSort(val sort: SortMode) : DraftEdit
    data class SetMedia(val media: MediaFilter) : DraftEdit
    data class SetDateRange(val range: DateRange?) : DraftEdit
    data class SetDateRangePreset(val preset: DateRangePreset) : DraftEdit
    data class AddIncludeTerm(val term: SearchTerm) : DraftEdit
    data class AddExcludeTerm(val term: SearchTerm) : DraftEdit
    data class RemoveIncludeTerm(val term: SearchTerm) : DraftEdit
    data class RemoveExcludeTerm(val term: SearchTerm) : DraftEdit
    data class AddIncludeAlternative(val groupIndex: Int, val term: SearchTerm) : DraftEdit
    /** A saved tag joins a group directly: it was already validated when [source] offered it. */
    data class AddSavedTagAlternative(val source: SourceKey, val groupIndex: Int, val term: SearchTerm) : DraftEdit
    /** A saved "or" group joins the draft as one new required group. */
    data class AddSavedTagGroup(val terms: List<SearchTerm>) : DraftEdit
    data class SplitIncludeAlternative(val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class RemoveIncludeGroup(val groupIndex: Int) : DraftEdit
    /** Puts the draft's tags back exactly as they were, for undoing a removal. */
    data class RestoreDraftTerms(
        val includeTermGroups: List<SearchTermGroup>,
        val excludeTerms: List<SearchTerm>,
    ) : DraftEdit
    data class SelectSuggestionScope(val scope: FacetedSearchScope) : SearchAction
    data class AutocompleteChanged(val input: String) : SearchAction
    data object RefreshAutocomplete : SearchAction
    data object ClearAutocomplete : SearchAction
    /** Steps back through settled searches, restoring each one's place; see [SearchQueryHistory]. */
    data object UndoSearch : SearchAction
    /** Steps forward again after [UndoSearch]. */
    data object RedoSearch : SearchAction
    /** Runs the draft now, without waiting out the live pause, and settles it. */
    data object ApplyDraft : SearchAction
    /** The user is done with the search on screen (the composer closed): a waiting edit runs now, and it settles. */
    data object SettleSearch : SearchAction
    data class ApplyHistoricalQuery(
        val query: Query,
        val sourceScope: SearchSourceScope = SearchSourceScope.fromQuery(query),
    ) : SearchAction
    /** Replaces the draft with a complete tag query and runs it at once. */
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
    /**
     * Applies [edit] to the draft from a screen over Search (the Viewer) without running it there; the draft runs
     * live, after the usual pause, once Search is shown again ([Resume]).
     */
    data class StageDraftEdit(val edit: DraftEdit) : SearchAction
    data class AddPostIncludeTerm(val post: Post, val term: SearchTerm) : DraftEdit
    data class AddPostExcludeTerm(val post: Post, val term: SearchTerm) : DraftEdit
    data class AddPostIncludeAlternative(val post: Post, val groupIndex: Int, val term: SearchTerm) : DraftEdit
    data class SetNhentaiLanguage(val filter: NhentaiLanguageFilter) : DraftEdit
    data class SetNhentaiFullColor(val enabled: Boolean) : DraftEdit
    data class RememberResolvedPost(val post: Post) : SearchAction
    /** Opens a related-shelf post in a Viewer session that stays bound to this feed. */
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

/** One-shot work hosted outside Search rendering. */
sealed interface SearchEffect {
    /**
     * [fromTypedId] marks a Viewer opened from a typed gallery ID rather than from the results on
     * screen: Search's composer closes behind it, and the post is not part of the applied search.
     */
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
