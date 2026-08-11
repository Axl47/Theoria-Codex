package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.durationFilterReadiness
import com.theoriacodex.app.media.durationFilterMetadata
import com.theoriacodex.app.media.noMediaDurationStateForPost
import com.theoriacodex.app.media.observedMediaDurationMs
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexDetailScreen(
    codexName: String?,
    posts: List<Post>,
    sortMode: CodexSortMode,
    availableSources: Set<SourceKey>,
    creatorBrowsingSources: Set<SourceKey>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
    durationStateForPost: (Post) -> Flow<MediaDurationState?> = noMediaDurationStateForPost,
    onDurationFilterChanged: (Boolean) -> Unit = {},
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onOpenViewer: (List<Post>, Int) -> Unit,
    resolvePostById: suspend (PostId) -> Post? = { null },
    onAddPostsToAnotherCodex: (List<Post>) -> Unit,
    onRemovePosts: (List<Post>) -> Unit,
    onSavePostToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: (() -> Unit)? = null,
    onBack: () -> Unit,
    onDeleteCodex: () -> Unit,
    isLikesCodex: Boolean,
    fabRestoreState: FeedFabRestoreState = FeedFabRestoreState(),
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit = {},
) {
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var editSelection by remember { mutableStateOf(CodexEditSelection()) }
    val animatedOnly = fabRestoreState.animatedOnly
    val durationMinBucket = fabRestoreState.durationMinBucket
    val durationMaxBucket = fabRestoreState.durationMaxBucket
    val selectedSource = fabRestoreState.source?.let { encoded ->
        SourceKey.entries.firstOrNull { source -> source.name == encoded }
    }
    val language = fabRestoreState.language?.let { encoded ->
        CodexLanguageFilter.entries.firstOrNull { filter -> filter.name == encoded }
    } ?: CodexLanguageFilter.ANY
    val fullColorOnly = fabRestoreState.fullColorOnly
    val gridState = rememberLazyStaggeredGridState()
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(durationMinBucket, durationMaxBucket)
    }
    val sourceOptions = remember(posts, availableSources) {
        codexSearchSourceOptions(posts, availableSources).map(CodexSearchSourceOption::source)
    }
    val representedSources = remember(sourceOptions) { sourceOptions.toSet() }
    val supportsLanguage = remember(representedSources) {
        supportsCodexLanguageFilter(representedSources)
    }
    val supportsFullColor = remember(representedSources) {
        supportsCodexFullColorFilter(representedSources)
    }
    val filters = remember(
        animatedOnly,
        animatedDurationRange,
        selectedSource,
        language,
        fullColorOnly,
    ) {
        CodexCollectionFilters(
            animatedOnly = animatedOnly,
            animatedDurationRange = animatedDurationRange,
            source = selectedSource,
            language = language,
            fullColorOnly = fullColorOnly,
        )
    }
    val unknownDurationPolicy = remember(resolveUnknownAnimatedDurations) {
        if (resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
    }
    val durationFilterActive = !animatedDurationRange.isFullRange
    val durationMetadata = remember(posts, durationStates, durationFilterActive) {
        durationFilterMetadata(posts, durationStates, durationFilterActive)
    }
    val acquiredDurations = durationMetadata.knownDurationMsByPostId
    val durationDecisionStates = durationMetadata.stateByPostId
    val durationReadiness = remember(posts, durationFilterActive, durationDecisionStates) {
        durationFilterReadiness(posts, durationFilterActive, durationDecisionStates)
    }
    val visiblePosts = remember(posts, filters, unknownDurationPolicy, acquiredDurations) {
        filterCodexCollectionPosts(
            posts,
            filters,
            unknownDurationPolicy,
            knownDurationMsByPostId = acquiredDurations,
        )
    }
    val applyFilters: (CodexCollectionFilters) -> Unit = { updated ->
        onFabRestoreStateChange(
            fabRestoreState.copy(
                animatedOnly = updated.animatedOnly,
                durationMinBucket = updated.animatedDurationRange.normalizedMinBucket,
                durationMaxBucket = updated.animatedDurationRange.normalizedMaxBucket,
                source = updated.source?.name,
                language = updated.language.name,
                fullColorOnly = updated.fullColorOnly,
            ),
        )
    }

    LaunchedEffect(posts.map(Post::id)) {
        editSelection = editSelection.retainAvailable(posts.mapTo(mutableSetOf(), Post::id))
    }
    CodexFilterReconciliationEffect(
        filters = filters,
        sourceOptions = sourceOptions,
        supportsLanguage = supportsLanguage,
        supportsFullColor = supportsFullColor,
        onFiltersChange = applyFilters,
    )
    LaunchedEffect(durationFilterActive) { onDurationFilterChanged(durationFilterActive) }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)

    if (codexName == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This codex no longer exists.", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back to codex list") }
        }
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                active = filters.isActive || sortMode != CodexSortMode.NEWEST_SAVED,
                contentDescription = "Filter Codex collection",
                onClick = { showFilterSheet = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CodexDetailHeader(
                codexName = codexName,
                itemSummary = codexItemSummary(visiblePosts.size, posts.size, filters.isActive),
                itemCount = posts.size,
                editSelection = editSelection,
                onBack = onBack,
                onBeginEdit = { editSelection = editSelection.begin() },
                onCancelEdit = { editSelection = editSelection.exit() },
                onAddSelected = {
                    val selected = posts.filter { it.id in editSelection.selectedPostIds }
                    editSelection = editSelection.exit()
                    onAddPostsToAnotherCodex(selected)
                },
                onRemoveSelected = {
                    val selected = posts.filter { it.id in editSelection.selectedPostIds }
                    editSelection = editSelection.exit()
                    onRemovePosts(selected)
                },
                onDeleteCodex = { showDeleteConfirm = true },
                isLikesCodex = isLikesCodex,
            )
            CodexDetailGrid(
                posts = visiblePosts,
                collectionIsEmpty = posts.isEmpty(),
                resolvingDurations = durationReadiness.isResolving,
                editSelection = editSelection,
                gridState = gridState,
                pixivUgoiraClient = pixivUgoiraClient,
                resolvePostById = resolvePostById,
                acquiredDurations = acquiredDurations,
                durationStateForPost = durationStateForPost,
                onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
                onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
                onOpenViewer = { index -> onOpenViewer(visiblePosts, index) },
                onToggleSelection = { postId -> editSelection = editSelection.toggle(postId) },
                onOpenPostActions = { post -> selectedActionPost = post },
            )
        }
    }

    if (showFilterSheet) {
        CodexFilterSheet(
            filters = filters,
            sourceOptions = sourceOptions,
            supportsLanguage = supportsLanguage,
            supportsFullColor = supportsFullColor,
            sortMode = sortMode,
            onFiltersChange = applyFilters,
            onSortChange = { updatedSort ->
                onFabRestoreStateChange(fabRestoreState.copy(sortMode = updatedSort.name))
            },
            onReset = {
                onFabRestoreStateChange(FeedFabRestoreState(sortMode = CodexSortMode.NEWEST_SAVED.name))
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSavePostToDevice(post) },
            onSaveToCodex = { onAddPostsToAnotherCodex(listOf(post)) },
            saveToCodexContentDescription = "Add to another Codex",
            onRemoveFromCodex = { onRemovePosts(listOf(post)) },
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onGoToSearch = onGoToSearch,
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                PostTagActionSection(
                    post = post,
                    tagVideoCountProvider = tagVideoCountProvider,
                    fetchTagVideoCounts = fetchTagVideoCounts,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                    onFavoriteTagLongPress = onFavoriteTagLongPress,
                )
            },
        )
    }

    if (showDeleteConfirm) {
        CodexDeleteConfirmationDialog(
            codexName = codexName,
            isLikesCodex = isLikesCodex,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDeleteCodex()
            },
        )
    }
}

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
private fun CodexDeleteConfirmationDialog(
    codexName: String,
    isLikesCodex: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLikesCodex) "Clear Likes?" else "Delete Codex?") },
        text = {
            Text(
                if (isLikesCodex) {
                    "Clear all liked posts from this recommendation profile? The Likes Codex will remain."
                } else {
                    "Delete \"$codexName\" and all saved items in it? This cannot be undone."
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isLikesCodex) "Clear" else "Delete")
            }
        },
    )
}

@Composable
private fun CodexDetailHeader(
    codexName: String,
    itemSummary: String,
    itemCount: Int,
    editSelection: CodexEditSelection,
    onBack: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAddSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
    isLikesCodex: Boolean,
) {
    SecondaryScreenAppBar(
        title = codexName,
        subtitle = itemSummary,
        onBack = onBack,
    ) {
        CodexDetailHeaderActions(
            hasPosts = itemCount > 0,
            editSelection = editSelection,
            onBeginEdit = onBeginEdit,
            onCancelEdit = onCancelEdit,
            onAddSelected = onAddSelected,
            onRemoveSelected = onRemoveSelected,
            onDeleteCodex = onDeleteCodex,
            isLikesCodex = isLikesCodex,
        )
    }
}

@Composable
private fun CodexDetailHeaderActions(
    hasPosts: Boolean,
    editSelection: CodexEditSelection,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAddSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDeleteCodex: () -> Unit,
    isLikesCodex: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (editSelection.active) {
            TextButton(onClick = onCancelEdit) { Text("Cancel") }
            IconButton(
                enabled = editSelection.selectedPostIds.isNotEmpty(),
                onClick = onAddSelected,
            ) {
                val selectedCount = editSelection.selectedPostIds.size
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = "Add $selectedCount selected " +
                        if (selectedCount == 1) "post to another Codex" else "posts to another Codex",
                )
            }
            TextButton(
                enabled = editSelection.selectedPostIds.isNotEmpty(),
                onClick = onRemoveSelected,
            ) {
                Text("Remove (${editSelection.selectedPostIds.size})")
            }
        } else {
            TextButton(enabled = hasPosts, onClick = onBeginEdit) { Text("Edit") }
            TextButton(onClick = onDeleteCodex) { Text(if (isLikesCodex) "Clear" else "Delete") }
        }
    }
}

@Composable
private fun CodexDetailGrid(
    posts: List<Post>,
    collectionIsEmpty: Boolean,
    resolvingDurations: Boolean,
    editSelection: CodexEditSelection,
    gridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    pixivUgoiraClient: PixivUgoiraClient?,
    resolvePostById: suspend (PostId) -> Post?,
    acquiredDurations: Map<PostId, Long>,
    durationStateForPost: (Post) -> Flow<MediaDurationState?>,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onOpenPostActions: (Post) -> Unit,
) {
    if (posts.isEmpty()) {
        FeedEmptyTile(
            title = if (collectionIsEmpty) "This codex is empty" else "No matching posts",
            message = when {
                collectionIsEmpty -> "Browse and save posts"
                resolvingDurations -> "Resolving durations…"
                else -> "Adjust your filters"
            },
            contentPadding = 24.dp,
        )
        return
    }
    TwoColumnPostStaggeredGrid(
        posts = posts,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        footerMessage = if (resolvingDurations) "Resolving durations…" else null,
    ) { index, post ->
        val observedDurationMs = observedMediaDurationMs(post, durationStateForPost)
        CodexSelectablePostCard(
            post = post,
            index = index,
            editSelection = editSelection,
            pixivUgoiraClient = pixivUgoiraClient,
            resolvePostById = resolvePostById,
            acquiredDurationMs = observedDurationMs ?: acquiredDurations[post.id],
            onDurationPostVisibilityChanged = onDurationPostVisibilityChanged,
            onAuthoritativeDurationKnown = onAuthoritativeDurationKnown,
            onOpenViewer = onOpenViewer,
            onToggleSelection = onToggleSelection,
            onOpenPostActions = onOpenPostActions,
        )
    }
}

@Composable
private fun CodexSelectablePostCard(
    post: Post,
    index: Int,
    editSelection: CodexEditSelection,
    pixivUgoiraClient: PixivUgoiraClient?,
    resolvePostById: suspend (PostId) -> Post?,
    acquiredDurationMs: Long?,
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onOpenViewer: (Int) -> Unit,
    onToggleSelection: (PostId) -> Unit,
    onOpenPostActions: (Post) -> Unit,
) {
    Box {
        SearchResultCard(
            post = post,
            pixivUgoiraClient = pixivUgoiraClient,
            acquiredDurationMs = acquiredDurationMs,
            showSourceBadge = true,
            resolvePostById = resolvePostById,
            refreshOnPreviewError = true,
            onClick = {
                if (editSelection.active) onToggleSelection(post.id) else onOpenViewer(index)
            },
            onLongPress = {
                if (editSelection.active) onToggleSelection(post.id) else onOpenPostActions(post)
            },
            onViewportChanged = { visible -> onDurationPostVisibilityChanged(post, visible) },
            onAuthoritativeDurationKnown = { durationMs ->
                onAuthoritativeDurationKnown(post, durationMs)
            },
        )
        if (editSelection.active) {
            CodexSelectionMarker(selected = post.id in editSelection.selectedPostIds)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CodexSelectionMarker(selected: Boolean) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .size(32.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CodexFilterSheet(
    filters: CodexCollectionFilters,
    sourceOptions: List<SourceKey>,
    supportsLanguage: Boolean,
    supportsFullColor: Boolean,
    sortMode: CodexSortMode,
    onFiltersChange: (CodexCollectionFilters) -> Unit,
    onSortChange: (CodexSortMode) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    FeedFilterSheet(onDismiss = onDismiss, title = "Codex filters") {
        CodexVisibilityFilters(filters, supportsFullColor, onFiltersChange)
        CodexSourceFilters(filters.source, sourceOptions) { source ->
            onFiltersChange(filters.copy(source = source))
        }
        if (supportsLanguage) {
            CodexLanguageFilters(filters.language) { language ->
                onFiltersChange(filters.copy(language = language))
            }
        }
        CodexSortFilters(sortMode, onSortChange)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onReset) { Text("Reset") }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun CodexVisibilityFilters(
    filters: CodexCollectionFilters,
    supportsFullColor: Boolean,
    onFiltersChange: (CodexCollectionFilters) -> Unit,
) {
    Text("Visibility", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = filters.animatedOnly,
                onClick = { onFiltersChange(filters.copy(animatedOnly = !filters.animatedOnly)) },
                label = { Text("Animated only") },
            )
        }
        if (supportsFullColor) {
            item {
                FilterChip(
                    selected = filters.fullColorOnly,
                    onClick = {
                        onFiltersChange(filters.copy(fullColorOnly = !filters.fullColorOnly))
                    },
                    label = { Text("Full Color") },
                )
            }
        }
    }
    AnimatedDurationRangeControl(
        range = filters.animatedDurationRange,
        onRangeChange = { range ->
            onFiltersChange(filters.copy(animatedDurationRange = range))
        },
    )
}

@Composable
private fun CodexSourceFilters(
    selectedSource: SourceKey?,
    sourceOptions: List<SourceKey>,
    onSourceChange: (SourceKey?) -> Unit,
) {
    HorizontalDivider()
    Text("Source", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selectedSource == null,
                onClick = { onSourceChange(null) },
                label = { Text("All") },
            )
        }
        items(sourceOptions, key = SourceKey::name) { source ->
            FilterChip(
                selected = selectedSource == source,
                onClick = { onSourceChange(source) },
                label = { Text(source.displayName()) },
            )
        }
    }
}

@Composable
private fun CodexLanguageFilters(
    selectedLanguage: CodexLanguageFilter,
    onLanguageChange: (CodexLanguageFilter) -> Unit,
) {
    HorizontalDivider()
    Text("Language", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CodexLanguageFilter.entries, key = CodexLanguageFilter::name) { option ->
            FilterChip(
                selected = selectedLanguage == option,
                onClick = { onLanguageChange(option) },
                label = { Text(option.displayLabel()) },
            )
        }
    }
}

@Composable
private fun CodexSortFilters(
    sortMode: CodexSortMode,
    onSortChange: (CodexSortMode) -> Unit,
) {
    HorizontalDivider()
    Text("Sort", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CodexSortMode.entries, key = CodexSortMode::name) { mode ->
            FilterChip(
                selected = sortMode == mode,
                onClick = { onSortChange(mode) },
                label = { Text(mode.displayLabel()) },
            )
        }
    }
}

internal fun codexItemSummary(visibleCount: Int, totalCount: Int, filtersActive: Boolean): String {
    return if (filtersActive) "$visibleCount of $totalCount items" else "$totalCount items"
}

private fun CodexLanguageFilter.displayLabel(): String = when (this) {
    CodexLanguageFilter.ANY -> "Any"
    CodexLanguageFilter.ENGLISH -> "English"
    CodexLanguageFilter.CHINESE -> "Chinese"
    CodexLanguageFilter.JAPANESE -> "Japanese"
}

private fun CodexSortMode.displayLabel(): String = when (this) {
    CodexSortMode.NEWEST_SAVED -> "Newest"
    CodexSortMode.OLDEST_SAVED -> "Oldest"
    CodexSortMode.BY_SOURCE -> "By source"
}
