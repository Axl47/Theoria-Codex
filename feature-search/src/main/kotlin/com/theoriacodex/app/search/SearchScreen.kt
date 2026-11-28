package com.theoriacodex.app.search

import com.theoriacodex.app.search.omnibox.OmniboxEntry
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.components.FeedDurations
import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import com.theoriacodex.app.ui.components.SeenPostsEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchQueryUiState
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.CollapsingSearchChrome
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.triage.triageSeed
import com.theoriacodex.app.ui.navigation.rememberTriageAction
import com.theoriacodex.app.ui.components.FeedPageDemandControl
import com.theoriacodex.app.ui.components.FeedScrollSignal
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.replaysAs
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.recents.recentSearchPresentation
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH
import com.theoriacodex.data.repository.defaultRecentSearchSources
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.DateRange
import com.theoriacodex.domain.model.MediaFilter
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    related: RelatedShelfBinding,
    onAction: (SearchAction) -> Unit,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    marks: FeedMarks = FeedMarks.NONE,
    favoriteTags: List<FavoriteTagEntry> = emptyList(),
    durations: FeedDurations = FeedDurations.NONE,
    onToggleLike: ((Post) -> Unit)? = null,
    onRequestSaveToCodex: (Post) -> Unit,
    onOpenSourceAccounts: () -> Unit = {},
    onAddFavoriteTag: (SourceKey, List<String>, String?) -> Unit = { _, _, _ -> },
    onRemoveFavoriteTag: (SourceKey, List<String>) -> Unit = { _, _ -> },
    onRenameFavoriteTag: (SourceKey, List<String>, String) -> Unit = { _, _, _ -> },
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
    oldSearchBar: Boolean = false,
    composerOpen: MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },
    savedSearches: List<SavedSearchEntry> = emptyList(),
    onOpenSavedSearch: (SavedSearchEntry) -> Unit = {},
    onSaveSearch: (String, RecentSearchEntry) -> Unit = { _, _ -> },
    onUpdateSavedSearch: (SavedSearchEntry, RecentSearchEntry) -> Unit = { _, _ -> },
    onManageSavedSearches: () -> Unit = {},
    omnibox: @Composable () -> List<OmniboxEntry> = { emptyList() },
    sourceMix: SearchSourceMix? = null,
    newPostIds: Set<PostId> = emptySet(),
    onNewPostsSeen: (suspend (Set<PostId>) -> Unit)? = null,
) {
    val chrome = rememberSearchChromeState(composerOpen)
    val actionPost = remember { SearchActionPostState() }
    val inputFocus = rememberSearchInputFocus()
    val scope = rememberCoroutineScope()
    val gridState = rememberRetainedFeedGridState("search")
    onNewPostsSeen?.let { SeenPostsEffect(gridState, newPostIds, it) }
    SearchTabReselectEffect(state.restoration, gridState, onAction)
    val feed = rememberSearchFeed(state, related.state, fabRestoreState, marks, durations)
    val results by rememberUpdatedState(state.content.results)
    val serverFilters = state.query.serverFilters()
    val filterScroll = rememberFeedScrollSignal()
    LaunchedEffect(feed.animatedDurationFilterActive) { durations.onFilterChanged(feed.animatedDurationFilterActive) }
    DurationRouteEnvironmentEffect(gridState, durations.onEnvironmentChanged)
    val pageDemand = rememberSearchScrollAndPaging(state, feed, gridState, durations.resolveInBackground, onAction)
    SearchScrollDismissEffect(gridState, chrome, inputFocus)
    val applyDraft = { chrome.applyDraft(inputFocus, onAction) }
    val filterActions = SearchFilterActions(state, fabRestoreState, rememberUpdatedState(serverFilters), onAction, onFabRestoreStateChange)
    val sourcePicker: @Composable () -> Unit = { SearchScreenSourcePicker(state, chrome, inputFocus, sourceMix != null, onAction) }
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            SearchFilterFab(searchFilterLabels(serverFilters, feed, fabRestoreState), filterScroll, { filterActions.clearFilters(serverFilters) }) {
                inputFocus.clear()
                chrome.showFilterSheet = true
            }
        }
    ) { padding ->
        SearchChromeLayout(
            padding, state, related, feed, fabRestoreState, chrome, oldSearchBar, sourcePicker, gridState, pageDemand, inputFocus,
            pixivUgoiraClient, marks.liked, durations, onToggleLike, resolvePostById, recoverPostMedia, onAction,
        ) { post ->
            inputFocus.clear()
            actionPost.open(post, results, scope, resolvePostById)
        }
    }
    actionPost.post?.let { post ->
        SearchPostSheet(post, actionPost, state, related, onAction, onRequestSaveToCodex, onAddFavoriteTag, onToggleLike)
    }
    if (chrome.composerOpen) {
        val update = { saved: SavedSearchEntry, search: RecentSearchEntry -> onUpdateSavedSearch(saved, search); chrome.closeComposer(onAction) }
        val saved = composerSavedSearches(state, savedSearches, onSaveSearch, update, onManageSavedSearches) { chrome.closeComposer(onAction); onOpenSavedSearch(it) }
        SearchComposerDialog(state, feed, chrome, gridState, sourcePicker, applyDraft, onAction, onOpenSourceAccounts, saved,
            favoriteTags, onAddFavoriteTag, onRemoveFavoriteTag, onRenameFavoriteTag, omnibox() + sourceMixCommands(state, chrome, sourceMix, onAction))
    }
    SearchSideSheets(state, chrome, favoriteTags, sourceMix, onAction, onRemoveFavoriteTag, onRenameFavoriteTag)
    if (chrome.showFilterSheet) {
        val onTriage = rememberTriageAction(
            ViewerStreamSource.SEARCH, state.query.appliedQueryHash.takeIf(String::isNotBlank), feed.view.triageSeed(),
        )
        SearchFilterSheet(state, feed, fabRestoreState, onAction, onFabRestoreStateChange, filterActions::resetFilterDraft, onTriage) {
            chrome.showFilterSheet = false
            onAction(SearchAction.SettleSearch)
        }
    }
}

@Composable
private fun SearchSideSheets(
    state: SearchUiState,
    chrome: SearchChromeState,
    favoriteTags: List<FavoriteTagEntry>,
    sourceMix: SearchSourceMix?,
    onAction: (SearchAction) -> Unit,
    onRemoveFavoriteTag: (SourceKey, List<String>) -> Unit,
    onRenameFavoriteTag: (SourceKey, List<String>, String) -> Unit,
) {
    if (chrome.showFavoriteTagSheet) {
        SearchFavoriteTags(state, favoriteTags, onAction, onRemoveFavoriteTag, onRenameFavoriteTag) { chrome.showFavoriteTagSheet = false }
    }
    SearchSourceMixSheet(state, chrome, sourceMix)
}

@Composable
private fun SearchScrollDismissEffect(gridState: LazyStaggeredGridState, chrome: SearchChromeState, inputFocus: SearchInputFocus) {
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                chrome.searchBarDismissedByScroll = true
                inputFocus.clear()
            }
        }
    }
}

@Composable
private fun SearchFilterFab(
    labels: List<String>,
    scrollSignal: FeedScrollSignal,
    onClear: () -> () -> Unit,
    onClick: () -> Unit,
) {
    FeedFilterFab(
        modifier = Modifier.padding(bottom = 8.dp),
        labels = labels,
        contentDescription = "Filter and sort",
        onClick = onClick,
        onClear = onClear,
        scrollSignal = scrollSignal,
    )
}

@Composable
private fun SearchChromeLayout(
    padding: PaddingValues,
    state: SearchUiState,
    related: RelatedShelfBinding,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    chrome: SearchChromeState,
    oldSearchBar: Boolean,
    sourcePicker: @Composable () -> Unit,
    gridState: LazyStaggeredGridState,
    pageDemand: FeedPageDemandControl,
    inputFocus: SearchInputFocus,
    pixivUgoiraClient: PixivUgoiraClient?,
    likedPostIds: Set<PostId>,
    durations: FeedDurations,
    onToggleLike: ((Post) -> Unit)?,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    onAction: (SearchAction) -> Unit,
    openPostActions: (Post) -> Unit,
) {
    CollapsingSearchChrome(
        expanded = !chrome.searchBarDismissedByScroll,
        legacy = oldSearchBar,
        onExpand = { chrome.composerOpen = true },
        trailingControl = sourcePicker,
        horizontalPadding = 16.dp,
        modifier = Modifier.fillMaxSize().padding(padding)
            .padding(top = if (oldSearchBar) 6.dp else 0.dp, bottom = 8.dp),
        inputField = { SearchChromeInput(state, feed, fabRestoreState, chrome, oldSearchBar, sourcePicker, onAction) },
    ) {
        val cards = rememberFeedCardEnvironment(
            pixivUgoiraClient, likedPostIds, feed.durationMetadata.knownDurationMsByPostId, durations, onToggleLike,
        )
        SearchResultsBody(
            state, related, feed, cards, gridState, pageDemand, inputFocus, resolvePostById, recoverPostMedia, onAction,
            openPostActions, composerOpen = chrome.composerOpen,
        )
    }
}

@Composable
internal fun rememberSearchChromeState(composerOpen: MutableState<Boolean>): SearchChromeState {
    val barDismissed = rememberSaveable { mutableStateOf(false) }
    return remember(composerOpen, barDismissed) { SearchChromeState(composerOpen, barDismissed) }
}

@Stable
internal class SearchChromeState(composerOpen: MutableState<Boolean>, searchBarDismissedByScroll: MutableState<Boolean>) {
    var composerOpen by composerOpen
    var searchBarDismissedByScroll by searchBarDismissedByScroll
    var showFilterSheet by mutableStateOf(false)
    var showFavoriteTagSheet by mutableStateOf(false)
    var showSourceMix by mutableStateOf(false)

    fun closeComposer(onAction: (SearchAction) -> Unit) {
        onAction(SearchAction.EndSavedSearchEdit)
        onAction(SearchAction.ClearAutocomplete)
        onAction(SearchAction.SettleSearch)
        composerOpen = false
        searchBarDismissedByScroll = false
    }

    fun applyDraft(inputFocus: SearchInputFocus, onAction: (SearchAction) -> Unit) {
        inputFocus.clear(force = true)
        onAction(SearchAction.EndSavedSearchEdit)
        onAction(SearchAction.ApplyDraft)
        composerOpen = false
        searchBarDismissedByScroll = false
    }
}

@Composable
private fun SearchComposerDialog(
    state: SearchUiState,
    feed: SearchFeed,
    chrome: SearchChromeState,
    gridState: LazyStaggeredGridState,
    sourcePicker: @Composable () -> Unit,
    applyDraft: () -> Unit,
    onAction: (SearchAction) -> Unit,
    onOpenSourceAccounts: () -> Unit,
    savedSearches: ComposerSavedSearches,
    favoriteTags: List<FavoriteTagEntry>,
    onAddFavoriteTag: (SourceKey, List<String>, String?) -> Unit,
    onRemoveFavoriteTag: (SourceKey, List<String>) -> Unit,
    onRenameFavoriteTag: (SourceKey, List<String>, String) -> Unit,
    omnibox: List<OmniboxEntry>,
) {
    val scope = rememberCoroutineScope()
    val favoriteSections = rememberFavoriteTagSections(state, favoriteTags)
    val resetScroll = { scope.launch { resetSearchScroll(feed.visibleResults, gridState, state.restoration, onAction) } }
    SearchComposer(
        state, onAction, sourcePicker, applyDraft,
        onReset = {
            onAction(SearchAction.ClearDraft)
            chrome.showFilterSheet = false
            resetScroll()
        },
        onUndo = {
            onAction(SearchAction.UndoSearch)
            chrome.showFilterSheet = false
        },
        onManageSavedTags = { chrome.showFavoriteTagSheet = true },
        onOpenSourceAccounts = onOpenSourceAccounts,
        onDismiss = { chrome.closeComposer(onAction) },
        savedSearches = savedSearches,
        savedTags = ComposerSavedTags(
            entries = favoriteSections.flatMap(FavoriteTagSection::entries).distinctBy(FavoriteTagEntry::key),
            saveSource = savedTagSource(state),
            onSave = onAddFavoriteTag,
            onRemove = onRemoveFavoriteTag,
            onRename = onRenameFavoriteTag,
        ),
        omnibox = omnibox,
        onOpenFilters = { chrome.showFilterSheet = true },
    )
}

@Composable
private fun SearchScreenSourcePicker(
    state: SearchUiState,
    chrome: SearchChromeState,
    inputFocus: SearchInputFocus,
    mixable: Boolean,
    onAction: (SearchAction) -> Unit,
) {
    SearchSourcePicker(
        draftSourceScope = state.query.draftSourceScope,
        sources = state.query.availableSources,
        unifiedSources = state.query.unifiedSources,
        onOpen = { inputFocus.clear() },
        onOpenSourceMix = { chrome.showSourceMix = true }.takeIf { mixable && state.searchesUnified },
        onModeSelected = { mode ->
            chrome.searchBarDismissedByScroll = false
            onAction(SearchAction.SelectMode(mode))
        },
        onTemporarySourceToggled = { source ->
            chrome.searchBarDismissedByScroll = false
            onAction(SearchAction.ToggleTemporarySource(source))
        },
    )
}

@Composable
private fun SearchChromeInput(
    state: SearchUiState,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    chrome: SearchChromeState,
    oldSearchBar: Boolean,
    sourcePicker: @Composable () -> Unit,
    onAction: (SearchAction) -> Unit,
) {
    SearchSummaryBar(
        summary = collapsedSearchContextSummary(state.query.draft, activeSearchFilterCount(state, feed, fabRestoreState)),
        validationMessage = state.validationMessage,
        history = SearchBarHistory(state.canUndoSearch, state.canRedoSearch) { back ->
            onAction(if (back) SearchAction.UndoSearch else SearchAction.RedoSearch)
        },
        onOpen = { chrome.composerOpen = true },
        trailing = { if (oldSearchBar) sourcePicker() },
    )
}

@Composable
private fun SearchPostSheet(
    post: Post,
    actionPost: SearchActionPostState,
    state: SearchUiState,
    related: RelatedShelfBinding,
    onAction: (SearchAction) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onAddFavoriteTag: (SourceKey, List<String>, String?) -> Unit,
    onToggleLike: ((Post) -> Unit)?,
) {
    PostActionSheet(
        post = post,
        onDismiss = actionPost::dismiss,
        onToggleLike = onToggleLike,
        onSaveToCodex = onRequestSaveToCodex,
        saveToCodexQuery = state.query.applied,
        onShowRelated = { related.controller.show(post) },
        tagContent = {
            PostTagActionSection(
                draftQuery = state.query.draft,
                loading = actionPost.resolving && !post.hasActionableTags(),
                post = post,
                onAddIncludeTerm = { term ->
                    onAction(SearchAction.AddPostIncludeTerm(post, term))
                    true
                },
                onAddExcludeTerm = { term ->
                    onAction(SearchAction.AddPostExcludeTerm(post, term))
                    true
                },
                onRemoveIncludeTerm = { term -> onAction(SearchAction.RemoveIncludeTerm(term)) },
                onRemoveExcludeTerm = { term -> onAction(SearchAction.RemoveExcludeTerm(term)) },
                onFavoriteTagLongPress = { source, tag -> onAddFavoriteTag(source, listOf(tag), null) },
                onSearch = {
                    actionPost.dismiss()
                    onAction(SearchAction.SettleSearch)
                },
            )
        },
    )
}

@Stable
private class SearchActionPostState {
    var post by mutableStateOf<Post?>(null)
        private set
    var resolving by mutableStateOf(false)
        private set

    fun open(
        requested: Post,
        results: List<Post>,
        scope: CoroutineScope,
        resolvePostById: suspend (PostId) -> Post?,
    ) {
        val displayPost = results.firstOrNull { candidate -> candidate.id == requested.id } ?: requested
        post = displayPost
        resolving = false
        if (displayPost.hasActionableTags()) return
        resolving = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation { resolvePostById(requested.id) }.getOrNull()
            if (post?.id == requested.id) {
                post = resolved ?: displayPost
                resolving = false
            }
        }
    }

    fun dismiss() {
        post = null
        resolving = false
    }
}

private class SearchFilterActions(
    private val state: SearchUiState,
    private val fabRestoreState: FeedFabRestoreState,
    private val currentServerFilters: State<SearchServerFilters>,
    private val onAction: (SearchAction) -> Unit,
    private val onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
) {
    private fun applyServerFilters(filters: SearchServerFilters) {
        onAction(SearchAction.SelectSort(filters.sort))
        onAction(SearchAction.SetMedia(filters.media))
        onAction(SearchAction.SetDateRange(filters.dateRange))
        if (filters.fullColor != currentServerFilters.value.fullColor) onAction(SearchAction.SetNhentaiFullColor(filters.fullColor))
        if (filters.language != currentServerFilters.value.language) onAction(SearchAction.SetNhentaiLanguage(filters.language))
    }

    fun resetFilterDraft() {
        applyServerFilters(DEFAULT_SERVER_FILTERS)
        onFabRestoreStateChange(
            fabRestoreState.copy(hideLiked = false, hideSaved = false, hideWatched = false)
                .withSearchAnimationFilter(state.query.draft.mode) { restoreState ->
                    restoreState.copy(
                        durationMinBucket = AnimatedDurationRange.Full.normalizedMinBucket,
                        durationMaxBucket = AnimatedDurationRange.Full.normalizedMaxBucket,
                    )
                },
        )
    }

    fun clearFilters(previousServerFilters: SearchServerFilters): () -> Unit {
        val previousFab = fabRestoreState
        resetFilterDraft()
        onAction(SearchAction.SettleSearch)
        return {
            onFabRestoreStateChange(previousFab)
            applyServerFilters(previousServerFilters)
            onAction(SearchAction.SettleSearch)
        }
    }
}

private fun searchFilterLabels(
    serverFilters: SearchServerFilters,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
): List<String> = listOfNotNull(
    sortModeLabel(serverFilters.sort).takeIf { serverFilters.sort != SortMode.NEWEST },
    serverFilters.dateRange?.let(::dateRangeLabel),
    "Animated".takeIf { feed.animatedFilterActive },
    animatedDurationRangeLabel(feed.animatedDurationRange).takeIf { feed.animatedDurationFilterActive },
    "Full color".takeIf { serverFilters.fullColor },
    nhentaiLanguageLabel(serverFilters.language).takeIf { serverFilters.language != NhentaiLanguageFilter.ANY },
    "Hide liked".takeIf { fabRestoreState.hideLiked },
    "Hide saved".takeIf { fabRestoreState.hideSaved },
    "Hide watched".takeIf { fabRestoreState.hideWatched },
)

private fun activeSearchFilterCount(state: SearchUiState, feed: SearchFeed, fabRestoreState: FeedFabRestoreState): Int =
    activeSearchFilterCount(
        appliedQuery = state.query.applied,
        animatedOnly = feed.animatedFilterActive,
        animatedDurationActive = feed.animatedDurationFilterActive,
        hideLiked = fabRestoreState.hideLiked,
        hideSaved = fabRestoreState.hideSaved,
        hideWatched = fabRestoreState.hideWatched,
        fullColor = state.query.nhentaiFullColorFilter,
        language = state.query.nhentaiLanguageFilter,
    )

internal fun composerSavedSearches(
    state: SearchUiState,
    entries: List<SavedSearchEntry>,
    onSave: (String, RecentSearchEntry) -> Unit,
    onUpdate: (SavedSearchEntry, RecentSearchEntry) -> Unit,
    onManage: () -> Unit,
    onOpen: (SavedSearchEntry) -> Unit,
): ComposerSavedSearches {
    val applied = appliedSearchEntry(state)
    val current = applied?.takeIf { it.query.includeTermGroups.isNotEmpty() }
    val editing = state.editingSavedSearchId?.let { id -> entries.firstOrNull { it.id == id } }
    val action = when {
        editing != null -> savedSearchUpdate(state, entries, editing, applied, onUpdate)
        current != null -> ComposerSavedSearchAction.Save(
            suggestedName = recentSearchPresentation(current).title.take(MAX_SAVED_SEARCH_NAME_LENGTH),
            onSave = { name -> onSave(name, current) },
        )
        else -> null
    }
    return ComposerSavedSearches(
        entries = entries,
        currentQueryHash = current?.queryHash,
        onOpen = onOpen,
        action = action,
        onManage = onManage,
    )
}

private fun appliedSearchEntry(state: SearchUiState): RecentSearchEntry? {
    val query = state.query
    val hash = query.appliedQueryHash.takeIf(String::isNotBlank) ?: return null
    val scope = query.appliedSourceScope
    return RecentSearchEntry(
        query = query.applied,
        queryHash = hash,
        searchedAtEpochMs = System.currentTimeMillis(),
        kind = when (scope) {
            SearchSourceScope.GlobalUnified -> RecentSearchKind.UNIFIED
            is SearchSourceScope.Single -> RecentSearchKind.SOURCE
            is SearchSourceScope.Temporary -> RecentSearchKind.MULTI_SEARCH
        },
        sources = scope.explicitSources.ifEmpty { query.applied.defaultRecentSearchSources() },
    )
}

private fun savedSearchUpdate(
    state: SearchUiState,
    entries: List<SavedSearchEntry>,
    editing: SavedSearchEntry,
    applied: RecentSearchEntry?,
    onUpdate: (SavedSearchEntry, RecentSearchEntry) -> Unit,
): ComposerSavedSearchAction.Update {
    val conflict = applied?.let { search -> entries.firstOrNull { it.id != editing.id && it.search.replaysAs(search) } }
    val ready = applied?.takeIf {
        conflict == null && !state.hasPendingChanges && !state.execution.liveSearchWaiting && !editing.search.replaysAs(it)
    }
    return ComposerSavedSearchAction.Update(editing.name, conflict?.name, ready?.let { search -> { onUpdate(editing, search) } })
}

private fun savedTagSource(state: SearchUiState): SourceKey? =
    (state.query.draft.mode as? QueryMode.Source)?.source
        ?: state.query.draftSourceScope.explicitSources.firstOrNull()
        ?: state.query.availableSources.firstOrNull()

@Composable
private fun rememberFavoriteTagSections(
    state: SearchUiState,
    favoriteTags: List<FavoriteTagEntry>,
): List<FavoriteTagSection> {
    return remember(state.query.draft.mode, favoriteTags, state.query.availableSources) {
        favoriteTagSections(
            mode = state.query.draft.mode,
            favoriteTags = favoriteTags,
            sourceDisplayOrder = state.query.availableSources,
        )
    }
}

@Composable
private fun SearchFavoriteTags(
    state: SearchUiState,
    favoriteTags: List<FavoriteTagEntry>,
    onAction: (SearchAction) -> Unit,
    onRemoveFavoriteTag: (SourceKey, List<String>) -> Unit,
    onRenameFavoriteTag: (SourceKey, List<String>, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections = rememberFavoriteTagSections(state, favoriteTags)
    var renaming by remember { mutableStateOf<FavoriteTagEntry?>(null) }
    val emptyMessage = remember(state.query.draft.mode) {
        when (val mode = state.query.draft.mode) {
            is QueryMode.Source -> "No saved tags for ${mode.source.displayName()} yet."
            QueryMode.Unified -> "No saved tags yet."
        }
    }
    FavoriteTagSheet(
        sections = sections,
        emptyMessage = emptyMessage,
        onAddTag = { entry ->
            val terms = entry.terms
            onAction(terms.singleOrNull()?.let(SearchAction::AddIncludeTerm) ?: SearchAction.AddSavedTagGroup(terms))
        },
        onRemoveTag = onRemoveFavoriteTag,
        onRenameTag = { entry -> renaming = entry },
        onDismiss = onDismiss,
    )
    renaming?.let { entry ->
        OrGroupNameDialog(entry, onDismiss = { renaming = null }) { name ->
            onRenameFavoriteTag(entry.source, entry.tags, name)
            renaming = null
        }
    }
}

@Composable
private fun SearchFilterSheet(
    state: SearchUiState,
    feed: SearchFeed,
    fabRestoreState: FeedFabRestoreState,
    onAction: (SearchAction) -> Unit,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onReset: () -> Unit,
    onTriage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val mode = state.query.draft.mode
    FilterSheet(
        query = state.query.draft,
        onAction = onAction,
        animatedOnly = state.query.draft.media == MediaFilter.ANIMATED,
        onAnimatedOnlyChange = { enabled ->
            onAction(SearchAction.SetMedia(if (enabled) MediaFilter.ANIMATED else MediaFilter.ANY))
        },
        animatedDurationRange = feed.animatedDurationRange,
        onAnimatedDurationRangeChange = { range ->
            onFabRestoreStateChange(
                fabRestoreState.withSearchAnimationFilter(mode) {
                    it.copy(durationMinBucket = range.normalizedMinBucket, durationMaxBucket = range.normalizedMaxBucket)
                },
            )
        },
        showAnimatedOnlyFilter = !feed.isNhentaiSourceMode,
        hideLiked = fabRestoreState.hideLiked,
        onHideLikedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideLiked = enabled)) },
        hideSaved = fabRestoreState.hideSaved,
        onHideSavedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideSaved = enabled)) },
        hideWatched = fabRestoreState.hideWatched,
        onHideWatchedChange = { enabled -> onFabRestoreStateChange(fabRestoreState.copy(hideWatched = enabled)) },
        nhentaiFullColorFilter = state.query.nhentaiFullColorFilter,
        nhentaiLanguageFilter = state.query.nhentaiLanguageFilter,
        onReset = onReset,
        onTriage = onTriage,
        onDismiss = onDismiss,
    )
}

@Composable
private fun FilterSheet(
    query: Query,
    onAction: (SearchAction) -> Unit,
    animatedOnly: Boolean,
    onAnimatedOnlyChange: (Boolean) -> Unit,
    animatedDurationRange: AnimatedDurationRange,
    onAnimatedDurationRangeChange: (AnimatedDurationRange) -> Unit,
    showAnimatedOnlyFilter: Boolean,
    hideLiked: Boolean,
    onHideLikedChange: (Boolean) -> Unit,
    hideSaved: Boolean,
    onHideSavedChange: (Boolean) -> Unit,
    hideWatched: Boolean,
    onHideWatchedChange: (Boolean) -> Unit,
    nhentaiFullColorFilter: Boolean,
    nhentaiLanguageFilter: NhentaiLanguageFilter,
    onReset: () -> Unit,
    onTriage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset, onTriage = onTriage) {
        FilterSection("Sort") {
            SortMode.entries.forEach { mode ->
                FilterChoiceChip(sortModeLabel(mode), query.sort == mode) { onAction(SearchAction.SelectSort(mode)) }
            }
        }
        FilterSection("Show") {
            if (showAnimatedOnlyFilter) {
                FilterChoiceChip("Animated only", animatedOnly) { onAnimatedOnlyChange(!animatedOnly) }
            } else {
                FilterChoiceChip("Full color", nhentaiFullColorFilter) {
                    onAction(SearchAction.SetNhentaiFullColor(!nhentaiFullColorFilter))
                }
            }
            FilterChoiceChip("Hide liked", hideLiked) { onHideLikedChange(!hideLiked) }
            FilterChoiceChip("Hide saved", hideSaved) { onHideSavedChange(!hideSaved) }
            FilterChoiceChip("Hide watched", hideWatched) { onHideWatchedChange(!hideWatched) }
        }
        if (showAnimatedOnlyFilter) {
            AnimatedDurationRangeControl(range = animatedDurationRange, onRangeChange = onAnimatedDurationRangeChange)
        } else {
            FilterSection("Language") {
                NhentaiLanguageFilter.entries.forEach { filter ->
                    FilterChoiceChip(nhentaiLanguageLabel(filter), nhentaiLanguageFilter == filter) {
                        onAction(SearchAction.SetNhentaiLanguage(filter))
                    }
                }
            }
        }
        DateRangeFilterSection(
            range = query.dateRange,
            onRangeChange = { range -> onAction(SearchAction.SetDateRange(range)) },
        )
    }
}

private fun nhentaiLanguageLabel(filter: NhentaiLanguageFilter): String = when (filter) {
    NhentaiLanguageFilter.ANY -> "Any"
    NhentaiLanguageFilter.ENGLISH -> "English"
    NhentaiLanguageFilter.CHINESE -> "Chinese"
    NhentaiLanguageFilter.JAPANESE -> "Japanese"
}

private data class SearchServerFilters(
    val sort: SortMode,
    val media: MediaFilter,
    val dateRange: DateRange?,
    val fullColor: Boolean,
    val language: NhentaiLanguageFilter,
)

private fun SearchQueryUiState.serverFilters() =
    SearchServerFilters(draft.sort, draft.media, draft.dateRange, nhentaiFullColorFilter, nhentaiLanguageFilter)

private val DEFAULT_SERVER_FILTERS = SearchServerFilters(SortMode.NEWEST, MediaFilter.ANY, null, false, NhentaiLanguageFilter.ANY)

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}
