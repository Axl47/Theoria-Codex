package com.theoriacodex.app.codex

import com.theoriacodex.app.ui.components.FeedGrid
import com.theoriacodex.app.ui.components.FeedRelatedPostsShelf
import com.theoriacodex.app.ui.components.FeedPostCard
import com.theoriacodex.app.ui.components.FeedCardEnvironment
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.ui.components.rememberFeedView
import com.theoriacodex.app.ui.components.FeedPostFilter
import com.theoriacodex.app.ui.components.FeedView
import com.theoriacodex.app.ui.components.FeedDurations
import androidx.activity.compose.BackHandler
import com.theoriacodex.app.media.CodexOfflineRemovalUndo
import com.theoriacodex.app.media.isAnimatedPost
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import com.theoriacodex.app.ui.components.rememberRetainedFeedGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.ui.components.FilterChoiceChip
import com.theoriacodex.app.ui.components.FilterSection
import com.theoriacodex.app.ui.components.SourceFilterChip
import com.theoriacodex.app.ui.components.rememberFeedScrollSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.components.LoadingState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.triage.triageSeed
import com.theoriacodex.app.ui.undo.UndoPill
import com.theoriacodex.app.ui.components.FeedScrollSignal
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.FeedPostActionSheet
import com.theoriacodex.app.ui.components.rememberFeedCardEnvironment
import com.theoriacodex.app.related.RelatedShelfBinding
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey

@Composable
private fun CodexUnavailable(loading: Boolean, onBack: () -> Unit) {
    if (loading) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Codex not found", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexDetailScreen(
    codexId: String,
    codexName: String?,
    posts: List<Post>,
    sortMode: CodexSortMode,
    availableSources: Set<SourceKey>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    durations: FeedDurations = FeedDurations.NONE,
    onOpenViewer: (List<Post>, Int) -> Unit,
    resolvePostById: suspend (PostId) -> Post? = { null },
    onAddPostsToAnotherCodex: (List<Post>) -> Unit,
    onRemovePosts: (List<Post>) -> Unit,
    onSavePostToDevice: (Post) -> Unit,
    onGoToSearch: (() -> Unit)? = null,
    onBack: () -> Unit,
    headerActions: CodexHeaderActions = CodexHeaderActions(null, null, null),
    customHeader: (@Composable (FeedView, LazyStaggeredGridState) -> Unit)? = null,
    customFilters: (@Composable (() -> Unit) -> Unit)? = null,
    customFiltersActive: Boolean = false,
    emptyMessage: String? = null,
    loading: Boolean = false,
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    related: RelatedShelfBinding? = null,
    onSearchSites: ((SourceKey, Query) -> Unit)? = null,
    tagSheetRequested: Boolean = false,
    onTagSheetRequestHandled: () -> Unit = {},
) {
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var editSelection by rememberCodexEditSelection(posts)
    val gridState = rememberRetainedFeedGridState("codex-detail:$codexId")
    val sourceOptions = remember(posts, availableSources) { codexSearchSourceOptions(posts, availableSources).map(CodexSearchSourceOption::source) }
    val supportsLanguage = remember(sourceOptions) { supportsCodexLanguageFilter(sourceOptions.toSet()) }
    val supportsFullColor = remember(sourceOptions) { supportsCodexFullColorFilter(sourceOptions.toSet()) }
    val filters = remember(fabRestoreState) { fabRestoreState.savedCollectionFilters() }
    val tagFilter = rememberCodexTagFilter(codexId, posts, settled = !loading, tagSheetRequested, onTagSheetRequestHandled)
    val feed = rememberCodexDetailFeed(codexId, posts, filters, durations, related, sortMode, tagFilter.selected)
    val applyFilters = { updated: CodexCollectionFilters -> onFabRestoreStateChange(fabRestoreState.withCollectionFilters(updated)) }
    CodexFilterReconciliationEffect(filters, sourceOptions, supportsLanguage, supportsFullColor, applyFilters)
    val resolveAllDurations = feed.durationFilterActive || sortMode.usesDuration
    LaunchedEffect(resolveAllDurations) { durations.onFilterChanged(resolveAllDurations) }
    DurationRouteEnvironmentEffect(gridState, durations.onEnvironmentChanged)
    if (codexName == null) return CodexUnavailable(loading, onBack)
    val filterScroll = rememberFeedScrollSignal()
    val resetFilters = { onFabRestoreStateChange(FeedFabRestoreState(sortMode = sortMode.name)) }
    val chips = CodexDetailChips(
        sortMode, codexSortModes(posts.any(::isAnimatedPost), sortMode),
        { updatedSort -> onFabRestoreStateChange(fabRestoreState.copy(sortMode = updatedSort.name)) },
        tagFilter,
        rememberCodexTriageAction(codexId, real = customHeader == null, seed = feed.triageSeed()),
    )
    Scaffold(
        modifier = Modifier.nestedScroll(filterScroll.connection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { CodexDetailUndo(codexId) },
        floatingActionButton = {
            CodexDetailFilterFab(
                filters, customFiltersActive, fabRestoreState, filterScroll, resetFilters, onFabRestoreStateChange,
                onClick = { showFilterSheet = true },
            )
        },
    ) { padding ->
        CodexDetailBody(
            padding, codexId, codexName, posts, feed, related, filters.isActive || tagFilter.selected.isNotEmpty(), chips, customHeader, customFiltersActive,
            editSelection, { editSelection = it }, likedPostIds, onToggleLike, loading, gridState, pixivUgoiraClient, resolvePostById,
            durations, onOpenViewer, { selectedActionPost = it }, emptyMessage, headerActions, onBack, onAddPostsToAnotherCodex, onRemovePosts,
        )
    }
    if (showFilterSheet) {
        CodexFilterSheet(
            filters, sourceOptions, supportsLanguage, supportsFullColor, applyFilters, resetFilters, { showFilterSheet = false },
            customFilters?.let { content -> ({ content { showFilterSheet = false } }) },
        )
    }
    CodexTagSheets(tagFilter, codexName, posts, availableSources, onSearchSites)
    selectedActionPost?.let { post ->
        FeedPostActionSheet(
            post, { selectedActionPost = null }, onGoToSearch, onToggleLike, onSavePostToDevice, { onAddPostsToAnotherCodex(listOf(it)) },
            saveToCodexLabel = "Add to another Codex",
            onRemoveFromCodex = if (customHeader == null) ({ onRemovePosts(listOf(post)) }) else null,
            onShowRelated = related?.let { shelf -> { shelf.controller.show(post) } },
        )
    }
}

@Composable
private fun CodexDetailUndo(codexId: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CodexOfflineRemovalUndo(codexId = codexId)
        UndoPill(CodexUndoAnchors.detail(codexId))
    }
}

@Composable
private fun rememberCodexEditSelection(posts: List<Post>): MutableState<CodexEditSelection> {
    val selection = remember { mutableStateOf(CodexEditSelection()) }
    LaunchedEffect(posts.map(Post::id)) {
        selection.value = selection.value.retainAvailable(posts.mapTo(mutableSetOf(), Post::id))
    }
    BackHandler(enabled = selection.value.active) { selection.value = selection.value.exit() }
    return selection
}

@Composable
private fun CodexDetailBody(
    padding: PaddingValues,
    codexId: String,
    codexName: String,
    posts: List<Post>,
    feed: FeedView,
    related: RelatedShelfBinding?,
    filtersActive: Boolean,
    chips: CodexDetailChips,
    customHeader: (@Composable (FeedView, LazyStaggeredGridState) -> Unit)?,
    customFiltersActive: Boolean,
    editSelection: CodexEditSelection,
    onEditSelectionChange: (CodexEditSelection) -> Unit,
    likedPostIds: Set<PostId>,
    onToggleLike: ((Post) -> Unit)?,
    loading: Boolean,
    gridState: LazyStaggeredGridState,
    pixivUgoiraClient: PixivUgoiraClient?,
    resolvePostById: suspend (PostId) -> Post?,
    durations: FeedDurations,
    onOpenViewer: (List<Post>, Int) -> Unit,
    onOpenPostActions: (Post) -> Unit,
    emptyMessage: String?,
    headerActions: CodexHeaderActions,
    onBack: () -> Unit,
    onAddPostsToAnotherCodex: (List<Post>) -> Unit,
    onRemovePosts: (List<Post>) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (customHeader != null) {
            customHeader(feed, gridState)
        } else CodexEditableHeader(
            codexId, codexName, posts, feed.visiblePosts, filtersActive || customFiltersActive, editSelection, headerActions,
            onEditSelectionChange, onBack, onAddPostsToAnotherCodex, onRemovePosts,
        )
        if (posts.isNotEmpty() && !editSelection.active) CodexDetailChipRow(chips)
        val cards = rememberFeedCardEnvironment(
            pixivUgoiraClient, likedPostIds, feed.durations.knownDurationMsByPostId, durations, onToggleLike,
        )
        CodexDetailGrid(
            feed, cards, posts.isEmpty(), loading, editSelection, gridState, resolvePostById,
            { index -> onOpenViewer(feed.visiblePosts, index) },
            { postId -> onEditSelectionChange(editSelection.toggle(postId)) },
            onEditSelectionChange, onOpenPostActions, emptyMessage, related,
        )
    }
}

@Composable
private fun CodexDetailFilterFab(
    filters: CodexCollectionFilters,
    customFiltersActive: Boolean,
    fabRestoreState: FeedFabRestoreState,
    filterScroll: FeedScrollSignal,
    resetFilters: () -> Unit,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    onClick: () -> Unit,
) {
    FeedFilterFab(
        modifier = Modifier.padding(bottom = 8.dp),
        labels = codexFilterLabels(filters, customFiltersActive),
        contentDescription = "Filter and sort",
        onClick = onClick,
        onClear = {
            val previous = fabRestoreState
            resetFilters()
            ({ onFabRestoreStateChange(previous) })
        },
        scrollSignal = filterScroll,
    )
}

private data class CodexPostFilter(val filters: CodexCollectionFilters, val tagKeys: List<String>) : FeedPostFilter {
    override fun filter(posts: List<Post>, knownDurationMsByPostId: Map<PostId, Long>): List<Post> =
        filterCodexCollectionPosts(posts, filters, knownDurationMsByPostId).withCodexTags(tagKeys)
}

@Composable
internal fun rememberCodexDetailFeed(
    codexId: String,
    posts: List<Post>,
    filters: CodexCollectionFilters,
    durations: FeedDurations,
    relatedShelf: RelatedShelfBinding?,
    sortMode: CodexSortMode,
    tagKeys: List<String> = emptyList(),
): FeedView {
    val visibility = remember(filters.animatedOnly, filters.animatedDurationRange) {
        SearchVisibilityFilters(animatedOnly = filters.animatedOnly, animatedDurationRange = filters.animatedDurationRange)
    }
    return rememberFeedView(
        "codex-detail:$codexId", posts, visibility, durations, relatedShelf?.state ?: RelatedPostsUiState.Idle,
        revealInOrder = !sortMode.usesDuration,
        postFilter = remember(filters, tagKeys) { CodexPostFilter(filters, tagKeys) },
    )
}

private fun FeedFabRestoreState.withCollectionFilters(updated: CodexCollectionFilters) = copy(
    animatedOnly = updated.animatedOnly,
    durationMinBucket = updated.animatedDurationRange.normalizedMinBucket,
    durationMaxBucket = updated.animatedDurationRange.normalizedMaxBucket,
    source = updated.source?.id,
    language = updated.language.name,
    fullColorOnly = updated.fullColorOnly,
)

@Composable
private fun CodexFilterReconciliationEffect(
    filters: CodexCollectionFilters,
    sourceOptions: List<SourceKey>,
    supportsLanguage: Boolean,
    supportsFullColor: Boolean,
    onFiltersChange: (CodexCollectionFilters) -> Unit,
) {
    LaunchedEffect(sourceOptions, supportsLanguage, supportsFullColor) {
        val reconciled = filters.copy(
            source = filters.source?.takeIf(sourceOptions::contains),
            language = filters.language.takeIf { supportsLanguage } ?: CodexLanguageFilter.ANY,
            fullColorOnly = filters.fullColorOnly && supportsFullColor,
        )
        if (reconciled != filters) onFiltersChange(reconciled)
    }
}

@Composable
private fun CodexDetailGrid(
    feed: FeedView,
    cards: FeedCardEnvironment,
    collectionIsEmpty: Boolean,
    loading: Boolean,
    editSelection: CodexEditSelection,
    gridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    resolvePostById: suspend (PostId) -> Post?,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onSelectionChange: (CodexEditSelection) -> Unit,
    onOpenPostActions: (Post) -> Unit,
    emptyMessage: String?,
    relatedShelf: RelatedShelfBinding?,
) {
    val posts = feed.visiblePosts
    val resolvingDurations = feed.durations.readiness.isResolving
    if (posts.isEmpty()) return CodexDetailEmptyState(collectionIsEmpty, loading, resolvingDurations, emptyMessage)
    val footerMessage = if (resolvingDurations) "Resolving durations…" else null
    val postIds = remember(posts) { posts.map(Post::id) }
    val dragSelect = if (relatedShelf == null) Modifier.codexDragSelect(gridState, postIds, editSelection, onSelectionChange) else Modifier
    FeedGrid(
        view = feed,
        state = gridState,
        footerMessage = footerMessage,
        modifier = Modifier.fillMaxSize().then(dragSelect),
        shelf = { relatedShelf?.let { FeedRelatedPostsShelf(it, feed.related, cards, onOpenPostActions) } },
    ) { index, post ->
        CodexSelectablePostCard(
            post = post,
            cards = cards,
            index = index,
            editSelection = editSelection,
            resolvePostById = resolvePostById,
            onOpenViewer = onOpenViewer,
            onToggleSelection = onToggleSelection,
            onOpenPostActions = onOpenPostActions,
        )
    }
}

@Composable
private fun CodexDetailEmptyState(collectionIsEmpty: Boolean, loading: Boolean, resolvingDurations: Boolean, emptyMessage: String?) {
    if (loading) {
        LoadingState(Modifier.fillMaxSize())
    } else {
        FeedEmptyTile(
            title = "No matching posts".takeUnless { emptyMessage != null || collectionIsEmpty },
            message = emptyMessage ?: when {
                collectionIsEmpty -> "No posts"
                resolvingDurations -> "Resolving durations…"
                else -> "Adjust your filters"
            },
            contentPadding = 24.dp,
        )
    }
}

@Composable
private fun CodexSelectablePostCard(
    post: Post,
    cards: FeedCardEnvironment,
    index: Int,
    editSelection: CodexEditSelection,
    resolvePostById: suspend (PostId) -> Post?,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onOpenPostActions: (Post) -> Unit,
) {
    Box {
        FeedPostCard(
            post = post,
            environment = cards,
            onClick = {
                if (editSelection.active) onToggleSelection(post.id) else onOpenViewer(index)
            },
            onLongPress = { if (!editSelection.active) onOpenPostActions(post) },
            showSourceBadge = true,
            likeEnabled = !editSelection.active,
            refreshOnPreviewError = true,
            resolvePostById = resolvePostById,
        )
        if (editSelection.active) {
            CodexSelectionMarker(selected = post.id in editSelection.selectedPostIds)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CodexSelectionMarker(selected: Boolean) {
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .size(32.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun CodexFilterSheet(
    filters: CodexCollectionFilters,
    sourceOptions: List<SourceKey>,
    supportsLanguage: Boolean,
    supportsFullColor: Boolean,
    onFiltersChange: (CodexCollectionFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    followedFilters: (@Composable () -> Unit)? = null,
) {
    FeedFilterSheet(onDismiss = onDismiss, onReset = onReset, modifier = Modifier.testTag("codex-filter-scroll")) {
        FilterSection("Show") {
            FilterChoiceChip("Animated only", filters.animatedOnly) {
                onFiltersChange(filters.copy(animatedOnly = !filters.animatedOnly))
            }
            if (supportsFullColor) {
                FilterChoiceChip("Full color", filters.fullColorOnly) {
                    onFiltersChange(filters.copy(fullColorOnly = !filters.fullColorOnly))
                }
            }
        }
        AnimatedDurationRangeControl(
            range = filters.animatedDurationRange,
            onRangeChange = { range -> onFiltersChange(filters.copy(animatedDurationRange = range)) },
        )
        if (followedFilters != null) {
            followedFilters()
        } else {
            FilterSection("Source") {
                FilterChoiceChip("All", filters.source == null) { onFiltersChange(filters.copy(source = null)) }
                sourceOptions.forEach { source ->
                    SourceFilterChip(source, filters.source == source) { onFiltersChange(filters.copy(source = source)) }
                }
            }
        }
        if (supportsLanguage) {
            FilterSection("Language") {
                CodexLanguageFilter.entries.forEach { option ->
                    FilterChoiceChip(option.displayLabel(), filters.language == option) {
                        onFiltersChange(filters.copy(language = option))
                    }
                }
            }
        }
    }
}

private fun codexFilterLabels(
    filters: CodexCollectionFilters,
    customFiltersActive: Boolean,
): List<String> = listOfNotNull(
    "Animated".takeIf { filters.animatedOnly },
    animatedDurationRangeLabel(filters.animatedDurationRange).takeIf { !filters.animatedDurationRange.isFullRange },
    "Full color".takeIf { filters.fullColorOnly },
    filters.source?.displayName(),
    filters.language.displayLabel().takeIf { filters.language != CodexLanguageFilter.ANY },
    "Selection".takeIf { customFiltersActive },
)

private fun CodexLanguageFilter.displayLabel(): String = when (this) {
    CodexLanguageFilter.ANY -> "Any"
    CodexLanguageFilter.ENGLISH -> "English"
    CodexLanguageFilter.CHINESE -> "Chinese"
    CodexLanguageFilter.JAPANESE -> "Japanese"
}
