package com.theoriacodex.app.search

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.MediaDurationKey
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.media.durationFilterReadiness
import com.theoriacodex.app.media.durationStatesByPostId
import com.theoriacodex.app.media.knownMediaDurations
import com.theoriacodex.app.media.mediaDurationKeysByPostId
import com.theoriacodex.app.media.animatedDurationBucketLabel
import com.theoriacodex.app.media.animatedDurationLabel
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.mergeResolvedPostForPresentation
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.R
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.isRule34Family
import com.theoriacodex.app.ui.components.AutocompleteListShell
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchRestorationUiState
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.tags.FavoriteTagActionGrid
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.PixivUgoiraPlayer
import com.theoriacodex.app.viewer.mediaTestTagPart
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (SearchAction) -> Unit,
    resolvePostById: suspend (PostId) -> Post?,
    recoverPostMedia: suspend (Post, ImageRef) -> Post?,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    likedPostIds: Set<PostId> = emptySet(),
    savedPostIds: Set<PostId> = emptySet(),
    watchedPostIds: Set<PostId> = emptySet(),
    favoriteTags: Map<SourceKey, List<String>> = emptyMap(),
    resolveUnknownAnimatedDurations: Boolean = false,
    durationStates: Map<MediaDurationKey, MediaDurationState> = emptyMap(),
    onDurationFilterChanged: (Boolean) -> Unit = {},
    onDurationPostVisibilityChanged: (Post, Boolean) -> Unit = { _, _ -> },
    onDurationEnvironmentChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onAddFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
    onRemoveFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
) {
    val input = state.suggestions.input
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var hideLiked by rememberSaveable { mutableStateOf(false) }
    var hideSaved by rememberSaveable { mutableStateOf(false) }
    var hideWatched by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFavoriteTagSheet by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionPostResolving by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    val queryHash = state.query.appliedQueryHash
    val isNhentaiSourceMode = state.query.draft.mode == QueryMode.Source(SourceKey.NHENTAI)
    val animatedFilterActive = animatedOnly && !isNhentaiSourceMode
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(
            minBucket = durationMinBucket,
            maxBucket = durationMaxBucket,
        )
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange && !isNhentaiSourceMode
    val searchFiltersActive = animatedFilterActive || animatedDurationFilterActive ||
        hideLiked || hideSaved || hideWatched || state.query.draft.sort != SortMode.NEWEST ||
        state.query.draft.dateRange != null || state.query.draft.minScore != null ||
        state.query.nhentaiFullColorFilter ||
        state.query.nhentaiLanguageFilter != NhentaiLanguageFilter.ANY
    val appliedFilterCount = activeSearchFilterCount(
        appliedQuery = state.query.applied,
        animatedOnly = animatedFilterActive,
        animatedDurationActive = animatedDurationFilterActive,
        hideLiked = hideLiked,
        hideSaved = hideSaved,
        hideWatched = hideWatched,
        fullColor = state.query.nhentaiFullColorFilter,
        language = state.query.nhentaiLanguageFilter,
    )
    val collapsedSearchContext = if (
        state.content.hasExecutedSearch || state.query.appliedQueryHash.isNotBlank()
    ) {
        collapsedSearchContextSummary(
            appliedSourceScope = state.query.appliedSourceScope,
            appliedQuery = state.query.applied,
            activeFilterCount = appliedFilterCount,
        )
    } else {
        null
    }
    LaunchedEffect(isNhentaiSourceMode) {
        if (isNhentaiSourceMode) {
            animatedOnly = false
            durationMinBucket = ANIMATED_DURATION_MIN_BUCKET
            durationMaxBucket = ANIMATED_DURATION_MAX_BUCKET
        }
    }
    val visibilityFilters = remember(
        animatedFilterActive,
        hideLiked,
        hideSaved,
        hideWatched,
        animatedDurationRange,
    ) {
        SearchVisibilityFilters(
            animatedOnly = animatedFilterActive,
            hideLiked = hideLiked,
            hideSaved = hideSaved,
            hideWatched = hideWatched,
            animatedDurationRange = animatedDurationRange,
        )
    }
    val unknownAnimatedDurationPolicy = remember(resolveUnknownAnimatedDurations) {
        if (resolveUnknownAnimatedDurations) {
            UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        } else {
            UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS
        }
    }
    val displayResults = remember(state.content.results, state.content.displayVersion, queryHash) {
        state.content.results
    }
    val durationKeysByPostId = remember(displayResults) {
        mediaDurationKeysByPostId(displayResults)
    }
    val acquiredDurations = remember(displayResults, durationStates, durationKeysByPostId) {
        knownMediaDurations(displayResults, durationStates, durationKeysByPostId)
    }
    val durationDecisionStates = remember(displayResults, durationStates, durationKeysByPostId) {
        durationStatesByPostId(displayResults, durationStates, durationKeysByPostId)
    }
    val durationReadiness = remember(
        displayResults,
        animatedDurationFilterActive,
        durationDecisionStates,
    ) {
        durationFilterReadiness(
            posts = displayResults,
            durationFilterActive = animatedDurationFilterActive,
            stateByPostId = durationDecisionStates,
        )
    }
    val visibleResults = remember(
        displayResults,
        visibilityFilters,
        likedPostIds,
        savedPostIds,
        watchedPostIds,
        unknownAnimatedDurationPolicy,
        acquiredDurations,
    ) {
        filterSearchResults(
            results = displayResults,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            savedPostIds = savedPostIds,
            watchedPostIds = watchedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
            knownDurationMsByPostId = acquiredDurations,
        )
    }
    LaunchedEffect(animatedDurationFilterActive) {
        onDurationFilterChanged(animatedDurationFilterActive)
    }
    DurationRouteEnvironmentEffect(gridState, onDurationEnvironmentChanged)
    fun openPostActionSheet(post: Post) {
        focusManager.clearFocus()
        val displayPost = state.content.results.firstOrNull { candidate -> candidate.id == post.id } ?: post
        selectedActionPost = displayPost
        selectedActionPostResolving = false
        if (displayPost.hasActionableTags()) return

        selectedActionPostResolving = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation {
                resolvePostById(post.id)
            }.getOrNull()
            if (selectedActionPost?.id == post.id) {
                selectedActionPost = resolved ?: displayPost
                selectedActionPostResolving = false
            }
        }
    }
    val sourceDisplayOrder = remember(state.query.modeOptions) {
        state.query.modeOptions
            .mapNotNull { mode -> (mode as? QueryMode.Source)?.source }
    }
    val favoriteSections = remember(state.query.draft.mode, favoriteTags, sourceDisplayOrder) {
        favoriteTagSections(
            mode = state.query.draft.mode,
            favoriteTags = favoriteTags,
            sourceDisplayOrder = sourceDisplayOrder,
        )
    }
    val favoriteTagEmptyMessage = remember(state.query.draft.mode) {
        when (val mode = state.query.draft.mode) {
            is QueryMode.Source -> "No favorite tags saved for ${mode.source.displayName()} yet."
            QueryMode.Unified -> "No favorite tags saved yet."
        }
    }
    suspend fun resetScrollToTop() {
        if (visibleResults.isNotEmpty()) {
            runCatchingPreservingCancellation {
                gridState.scrollToItem(index = 0, scrollOffset = 0)
            }
        }
        onAction(SearchAction.ScrollChanged(0, 0))
    }

    val scrollRestoration = (state.restoration as? SearchRestorationUiState.Restored)
        ?.takeIf { restored -> restored.scrollState != null }
    LaunchedEffect(
        scrollRestoration?.scrollRequestId,
        visibleResults.isNotEmpty(),
        animatedFilterActive,
    ) {
        val request = scrollRestoration ?: return@LaunchedEffect
        val restored = request.scrollState ?: return@LaunchedEffect
        if (animatedFilterActive || visibleResults.isEmpty()) return@LaunchedEffect

        // A request is issued only for route entry/re-entry and acknowledged after it is applied.
        // Page appends therefore cannot replay the saved position and jump the grid unexpectedly.
        val lastIndex = visibleResults.lastIndex.coerceAtLeast(0)
        gridState.scrollToItem(
            index = restored.firstVisibleItemIndex.coerceIn(0, lastIndex),
            scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
        )
        onAction(SearchAction.ScrollRestorationApplied(request.scrollRequestId))
    }

    LaunchedEffect(queryHash, animatedFilterActive) {
        if (animatedFilterActive) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                onAction(SearchAction.ScrollChanged(index, offset))
            }
    }

    LaunchedEffect(
        queryHash,
        visibleResults.size,
        state.content.results.size,
        state.content.canLoadMore,
        state.loading,
        state.loadingMore,
        animatedFilterActive,
        animatedDurationFilterActive,
        durationReadiness.pendingCount,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.loadingMore
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (state.loading || state.loadingMore || !state.content.canLoadMore) return@collect
            if (durationReadiness.isResolving) return@collect

            val totalVisible = visibleResults.size
            val shouldTriggerByThreshold = if (totalVisible > 0 && lastVisibleIndex >= 0) {
                val triggerIndex = ((totalVisible - 1) * PAGINATION_PREFETCH_RATIO)
                    .toInt()
                    .coerceAtLeast(0)
                lastVisibleIndex >= triggerIndex
            } else {
                false
            }

            // Keep filling animated feed when the filtered set is still too small.
            val shouldTriggerForAnimatedBuffer =
                (animatedFilterActive || animatedDurationFilterActive) &&
                    totalVisible < ANIMATED_PREFETCH_MIN_VISIBLE &&
                    state.content.results.isNotEmpty()

            if (shouldTriggerByThreshold || shouldTriggerForAnimatedBuffer) {
                onAction(SearchAction.LoadNextPage)
            }
        }
    }

    LaunchedEffect(searchFieldFocused) {
        if (!searchFieldFocused) return@LaunchedEffect
        snapshotFlow { gridState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    focusManager.clearFocus()
                }
            }
    }

    val autocompleteSuggestions = state.suggestions.autocomplete
    val facetedAutocompleteSuggestions = state.suggestions.facetedAutocomplete
    val supportedSearchScopes = state.query.supportedScopes
    val sourceAuthErrorMessage = remember(state.content.statuses) {
        buildSourceAuthErrorMessage(state.content.statuses)
    }
    val sourceFailureMessage = remember(state.content.statuses) {
        buildSourceFailureMessage(state.content.statuses)
    }
    val sourceStatusChips = remember(state.content.statuses) {
        visibleSourceStatusChipStatuses(state.content.statuses)
    }
    val clearFocusInteraction = remember { MutableInteractionSource() }
    val showSearchControls = searchFieldFocused ||
        input.isNotBlank() ||
        autocompleteSuggestions.isNotEmpty() ||
        facetedAutocompleteSuggestions.isNotEmpty() ||
        state.hasPendingChanges
    val showAddInputAction = searchFieldFocused && input.isNotBlank()

    fun commitTagInput() {
        val typed = input.trim()
        if (!state.suggestions.canCommitInput) return
        onAction(SearchAction.CommitTagInput(input))
        focusManager.clearFocus()
        if (typed.isDigitsOnly()) {
            scope.launch { resetScrollToTop() }
        }
    }

    fun applyDraftAndResetScroll() {
        focusManager.clearFocus(force = true)
        onAction(SearchAction.ApplyDraft)
        onAction(SearchAction.ClearAutocomplete)
        scope.launch { resetScrollToTop() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                active = searchFiltersActive,
                contentDescription = "Filter and sort",
                onClick = {
                    focusManager.clearFocus()
                    showFilterSheet = true
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        searchFieldFocused = state.isFocused
                        if (state.isFocused) {
                            onAction(SearchAction.AutocompleteChanged(input))
                        } else {
                            onAction(SearchAction.ClearAutocomplete)
                        }
                    },
                value = input,
                onValueChange = {
                    onAction(SearchAction.DismissValidation)
                    onAction(SearchAction.AutocompleteChanged(it))
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                isError = state.query.validationMessage != null,
                supportingText = state.query.validationMessage?.let { message ->
                    { Text(message) }
                },
                placeholder = if (!searchFieldFocused) {
                    {
                        Text(
                            text = collapsedSearchContext ?: if (supportedSearchScopes.isEmpty()) {
                                "tag or -tag"
                            } else {
                                "tag, artist:, or -tag"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { commitTagInput() },
                ),
                trailingIcon = {
                    Row(
                        modifier = Modifier.padding(end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = {
                            focusManager.clearFocus()
                            showFavoriteTagSheet = true
                        }) {
                            Text("List")
                        }
                        if (showAddInputAction) {
                            TextButton(
                                onClick = { commitTagInput() },
                                enabled = state.suggestions.canCommitInput,
                            ) {
                                Text("Add")
                            }
                        }
                    }
                }
            )

            AnimatedVisibility(
                visible = showSearchControls,
                enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight / 4 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight / 5 }) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (supportedSearchScopes.isNotEmpty()) {
                        SearchScopeRow(
                            scopes = supportedSearchScopes,
                            selectedScope = state.query.selectedScope,
                            onScopeSelected = { selected ->
                                onAction(SearchAction.SelectSuggestionScope(selected))
                                true
                            },
                        )
                    }

                    if (facetedAutocompleteSuggestions.isNotEmpty()) {
                        FacetedAutocompletePanel(
                            suggestions = facetedAutocompleteSuggestions,
                            onInclude = { suggestion ->
                                onAction(SearchAction.IncludeSuggestion(suggestion))
                                onAction(SearchAction.ClearAutocomplete)
                            },
                            onExclude = { suggestion ->
                                onAction(SearchAction.ExcludeSuggestion(suggestion))
                                onAction(SearchAction.ClearAutocomplete)
                            },
                        )
                    } else if (autocompleteSuggestions.isNotEmpty()) {
                        AutocompletePanel(
                            suggestions = autocompleteSuggestions,
                            onInclude = { tag ->
                                onAction(SearchAction.AddIncludeTerm(SearchTerm(tag)))
                                onAction(SearchAction.ClearAutocomplete)
                            },
                            onExclude = { tag ->
                                onAction(SearchAction.AddExcludeTerm(SearchTerm(tag)))
                                onAction(SearchAction.ClearAutocomplete)
                            },
                        )
                    }

                    ModeRow(
                        draftSourceScope = state.query.draftSourceScope,
                        options = state.query.modeOptions,
                        unifiedSourceCount = state.query.enabledSourceCount,
                        onModeSelected = { mode -> onAction(SearchAction.SelectMode(mode)) },
                        onTemporarySourceToggled = { source ->
                            onAction(SearchAction.ToggleTemporarySource(source))
                        },
                    )

                    TagRow(
                        includeTerms = state.query.draft.includeTerms,
                        excludeTerms = state.query.draft.excludeTerms,
                        onRemoveInclude = { term -> onAction(SearchAction.RemoveIncludeTerm(term)) },
                        onRemoveExclude = { term -> onAction(SearchAction.RemoveExcludeTerm(term)) },
                    )

                    if (
                        state.query.appliedSourceScope !is SearchSourceScope.Single &&
                        sourceStatusChips.isNotEmpty()
                    ) {
                        StatusRow(statuses = sourceStatusChips)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = {
                                focusManager.clearFocus()
                                onAction(SearchAction.ClearDraft)
                                onAction(SearchAction.ClearAutocomplete)
                                showFilterSheet = false
                                scope.launch { resetScrollToTop() }
                            },
                            enabled = state.hasPendingChanges,
                        ) {
                            Text("Reset")
                        }
                        TextButton(onClick = {
                            applyDraftAndResetScroll()
                        }) {
                            Text("Apply")
                        }
                    }
                }
            }

            when {
                state.loading -> {
                    FeedLoadingState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() },
                    )
                }
                state.content.error != null -> {
                    val error = requireNotNull(state.content.error)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() }
                    ) {
                        ErrorBlock(
                            message = error.message,
                            onRetry = {
                                focusManager.clearFocus()
                                onAction(SearchAction.Retry)
                            },
                        )
                    }
                }
                visibleResults.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sourceAuthErrorMessage?.let { authMessage ->
                                ErrorBlock(
                                    title = "Source account required",
                                    message = authMessage,
                                )
                            }
                            sourceFailureMessage?.let { failureMessage ->
                                ErrorBlock(
                                    message = failureMessage,
                                    onRetry = {
                                        focusManager.clearFocus()
                                        onAction(SearchAction.Retry)
                                    },
                                )
                            }
                            if (durationReadiness.isResolving) {
                                EmptyBlock(
                                    hasPendingChanges = false,
                                    messageOverride = "Resolving durations…",
                                )
                            } else if (!state.content.hasExecutedSearch && !animatedFilterActive) {
                                SearchStartSplash(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                EmptyBlock(
                                    hasPendingChanges = state.hasPendingChanges,
                                    messageOverride = buildEmptySearchMessage(
                                        sourceResults = state.content.results,
                                        visibilityFilters = visibilityFilters,
                                        loadingMore = state.loadingMore,
                                        canLoadMore = state.content.canLoadMore,
                                    ),
                                )
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sourceAuthErrorMessage?.let { authMessage ->
                            ErrorBlock(
                                title = "Source account required",
                                message = authMessage,
                            )
                        }
                        TwoColumnPostStaggeredGrid(
                            posts = visibleResults,
                            state = gridState,
                            modifier = Modifier.weight(1f),
                            showPagingTile = state.loadingMore,
                            footerMessage = if (durationReadiness.isResolving) {
                                "Resolving durations…"
                            } else {
                                null
                            },
                        ) { _, post ->
                            SearchResultCard(
                                post = post,
                                pixivUgoiraClient = pixivUgoiraClient,
                                acquiredDurationMs = acquiredDurations[post.id],
                                showSourceBadge = state.query.appliedSourceScope !is SearchSourceScope.Single,
                                liked = post.id in likedPostIds,
                                onToggleLike = onToggleLike?.let { toggle ->
                                    { toggle(post) }
                                },
                                resolvePostById = resolvePostById,
                                recoverPostMedia = { failedPost, failedMedia ->
                                    recoverPostMedia(failedPost, failedMedia)
                                },
                                onClick = {
                                    focusManager.clearFocus()
                                    onAction(SearchAction.OpenResult(
                                        postId = post.id,
                                        visibleResults = visibleResults,
                                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        visibilityFilters = visibilityFilters,
                                    ))
                                },
                                onLongPress = { openPostActionSheet(post) },
                                onViewportChanged = { visible ->
                                    onDurationPostVisibilityChanged(post, visible)
                                },
                                onAuthoritativeDurationKnown = { durationMs ->
                                    onAuthoritativeDurationKnown(post, durationMs)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            creatorBrowsingSources = creatorBrowsingSources,
            onDismiss = {
                selectedActionPost = null
                selectedActionPostResolving = false
            },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onOpenCreatorProfile = onOpenCreatorProfile,
            onOpenLegacyCreatorProfile = { onOpenLegacyCreatorProfile(post) },
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                if (selectedActionPostResolving && !post.hasActionableTags()) {
                    Text(
                        text = "Loading tags...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PostTagActionSection(
                        post = post,
                        tagVideoCountProvider = tagVideoCountProvider,
                        fetchTagVideoCounts = fetchTagVideoCounts,
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
                        onFavoriteTagLongPress = onAddFavoriteTag,
                    )
                }
            },
        )
    }

    if (showFavoriteTagSheet) {
        FavoriteTagSheet(
            sections = favoriteSections,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            emptyMessage = favoriteTagEmptyMessage,
            onAddTag = { tag ->
                onAction(SearchAction.AddIncludeTerm(SearchTerm(tag)))
            },
            onRemoveTag = { source, tag ->
                onRemoveFavoriteTag(source, tag)
            },
            onDismiss = { showFavoriteTagSheet = false },
        )
    }

    if (showFilterSheet) {
        FilterSheet(
            query = state.query.draft,
            onAction = onAction,
            animatedOnly = animatedOnly,
            onAnimatedOnlyChange = { animatedOnly = it },
            animatedDurationRange = animatedDurationRange,
            onAnimatedDurationRangeChange = { range ->
                durationMinBucket = range.normalizedMinBucket
                durationMaxBucket = range.normalizedMaxBucket
            },
            showAnimatedOnlyFilter = !isNhentaiSourceMode,
            hideLiked = hideLiked,
            onHideLikedChange = { hideLiked = it },
            hideSaved = hideSaved,
            onHideSavedChange = { hideSaved = it },
            hideWatched = hideWatched,
            onHideWatchedChange = { hideWatched = it },
            nhentaiFullColorFilter = state.query.nhentaiFullColorFilter,
            onNhentaiFullColorFilterChange = { enabled ->
                onAction(SearchAction.SetNhentaiFullColor(enabled))
                applyDraftAndResetScroll()
            },
            nhentaiLanguageFilter = state.query.nhentaiLanguageFilter,
            onNhentaiLanguageFilterChange = { filter ->
                onAction(SearchAction.SetNhentaiLanguage(filter))
                applyDraftAndResetScroll()
            },
            onSortChanged = { applyDraftAndResetScroll() },
            onDismiss = { showFilterSheet = false },
        )
    }
}

@Composable
private fun SearchStartSplash(
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.theoria_splash_mark),
                contentDescription = "Theoria splash",
                modifier = Modifier.size(180.dp),
            )
            Text(
                text = "Add tags and press Apply to start searching.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SearchResultCard(
    post: Post,
    pixivUgoiraClient: PixivUgoiraClient?,
    acquiredDurationMs: Long? = null,
    showSourceBadge: Boolean = false,
    metadataLabel: String? = null,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    refreshOnPreviewError: Boolean = false,
    playbackDiagnosticsEnabled: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onViewportChanged: (Boolean) -> Unit = {},
    onAuthoritativeDurationKnown: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isInViewport by remember(post.id) { mutableStateOf(false) }
    var isLifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val playbackActive = shouldFeedMediaPlay(
        isInViewport = isInViewport,
        isLifecycleStarted = isLifecycleStarted,
    )
    var resolvedPostOverride by remember(post.id) { mutableStateOf<Post?>(null) }
    var resolutionAttempted by remember(post.id) { mutableStateOf(false) }
    val mediaRecoveryAttemptedUrls = remember(post.id) { mutableSetOf<String>() }
    val effectivePost = resolvedPostOverride?.let { resolved ->
        mergeResolvedPostForPresentation(original = post, resolved = resolved)
    } ?: post

    DisposableEffect(post.id) {
        onDispose {
            if (isInViewport) onViewportChanged(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isLifecycleStarted = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestResolvedCardPreview(force: Boolean = false) {
        if (resolutionAttempted) return
        val resolver = resolvePostById ?: return
        if (
            !force &&
            post.id.source != SourceKey.RULE34VIDEO &&
            post.id.source != SourceKey.RULE34GEN
        ) return
        resolutionAttempted = true
        scope.launch {
            val resolved = runCatchingPreservingCancellation {
                resolver(post.id)
            }.getOrNull() ?: return@launch
            resolvedPostOverride = resolved
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val visible = coordinates.isAttached &&
                    isVisibleFeedBounds(coordinates.boundsInWindow(clipBounds = true))
                if (isInViewport != visible) {
                    isInViewport = visible
                    onViewportChanged(visible)
                }
            }
            .testTag(searchCardTestTag(post.id))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        val title = effectivePost.displayTitleOrNull()
        val durationLabel = animatedDurationLabel(effectivePost, acquiredDurationMs)
        val supportingContent = searchCardSupportingContent(
            title = title,
            metadataLabel = metadataLabel,
            showSourceBadge = showSourceBadge,
        )
        val mediaContentDescription = title ?: effectivePost.id.sourcePostId
        val videoRef = remember(
            effectivePost.id.source,
            effectivePost.id.sourcePostId,
            effectivePost.media,
            effectivePost.full,
            effectivePost.preview,
        ) {
            if (allowsInlineAutoplayInSearch(effectivePost)) {
                resolveCardVideoRef(effectivePost)
            } else {
                null
            }
        }
        var videoPlaybackFailed by remember(
            effectivePost.id.source,
            effectivePost.id.sourcePostId,
            videoRef?.url,
            videoRef?.localPath,
        ) {
            mutableStateOf(false)
        }
        val previewRef = remember(effectivePost) {
            postPreviewImageCandidate(effectivePost)?.ref
        }
        val imageCandidates = remember(previewRef?.url, previewRef?.progressiveUrls) {
            searchCardImageCandidates(previewRef)
        }
        var displayedImageCandidateIndex by remember(
            effectivePost.id,
            previewRef?.url,
            previewRef?.progressiveUrls,
        ) {
            mutableIntStateOf(0)
        }
        val previewUrl = imageCandidates.getOrNull(displayedImageCandidateIndex)
        val ratio = remember(post.id) {
            previewAspectRatio(post)
        }
        val windowSize = LocalWindowInfo.current.containerSize
        val decodeSize = remember(windowSize.width, ratio) {
            feedPreviewDecodeSize(
                screenWidthPx = windowSize.width,
                aspectRatio = ratio,
            )
        }
        val imageModel = remember(
            context,
            previewUrl,
            effectivePost.id.source,
            decodeSize,
        ) {
            previewUrl?.let {
                buildFeedImageRequest(
                    context = context,
                    url = it,
                    sourceKey = effectivePost.id.source,
                    decodeSize = decodeSize,
                )
            }
        }
        val mediaCount = postMediaCount(effectivePost)

        LaunchedEffect(effectivePost.id, videoRef?.url, videoRef?.localPath, resolvePostById) {
            if (videoRef == null && resolvedPostOverride == null) {
                requestResolvedCardPreview()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        ) {
            val showUgoira = isPixivUgoiraPost(effectivePost) && pixivUgoiraClient != null
            if (showUgoira) {
                PixivUgoiraPlayer(
                    postId = effectivePost.id.sourcePostId,
                    client = requireNotNull(pixivUgoiraClient),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = mediaContentDescription,
                    contentScale = ContentScale.Crop,
                    isActive = playbackActive,
                    onDurationKnown = onAuthoritativeDurationKnown,
                )
            } else if (videoRef != null && !videoPlaybackFailed) {
                SearchVideoPreview(
                    media = videoRef,
                    postId = effectivePost.id,
                    sourceKey = effectivePost.id.source,
                    playbackDiagnosticsEnabled = playbackDiagnosticsEnabled,
                    isActive = playbackActive,
                    previewModel = imageModel,
                    modifier = Modifier.fillMaxSize(),
                    onPlaybackError = {
                        videoPlaybackFailed = true
                        if (resolvedPostOverride == null) {
                            requestResolvedCardPreview(force = refreshOnPreviewError)
                        }
                    },
                    onDurationKnown = { durationMs ->
                        if (isAuthoritativeDurationMedia(effectivePost, videoRef)) {
                            onAuthoritativeDurationKnown(durationMs)
                        }
                    },
                )
            } else if (imageModel != null) {
                FeedAsyncImage(
                    model = imageModel,
                    contentDescription = mediaContentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    isActive = playbackActive,
                    onError = { state ->
                        val canAdvance = displayedImageCandidateIndex < imageCandidates.lastIndex
                        val failedRef = previewRef?.copy(url = previewUrl)
                        if (
                            failedRef != null &&
                            recoverPostMedia != null &&
                            isHttpNotFound(state.result.throwable) &&
                            mediaRecoveryAttemptedUrls.add(previewUrl.orEmpty())
                        ) {
                            scope.launch {
                                val recovered = try {
                                    recoverPostMedia(effectivePost, failedRef)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    null
                                }
                                if (recovered != null && recovered != effectivePost) {
                                    resolvedPostOverride = recovered
                                } else if (canAdvance) {
                                    displayedImageCandidateIndex += 1
                                }
                            }
                        } else if (canAdvance) {
                            displayedImageCandidateIndex += 1
                        } else if (resolvedPostOverride == null && refreshOnPreviewError) {
                            requestResolvedCardPreview(force = true)
                        }
                    },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No preview",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (mediaCount > 1) {
                ImageCountBadge(
                    count = mediaCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            if (onToggleLike != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (liked) "Unlike post" else "Like post"
                            selected = liked
                            stateDescription = if (liked) "Liked" else "Not liked"
                        }
                        .clickable(
                            role = Role.Checkbox,
                            onClick = onToggleLike,
                        ),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("Search like visual"),
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (liked) {
                                    Icons.Default.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = null,
                                tint = if (liked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.White
                                },
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            if (supportingContent.showPreviewContext) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (metadataLabel != null) {
                        PreviewMetadataLabel(metadataLabel)
                    }
                    if (showSourceBadge) {
                        SourceBadge(source = effectivePost.id.source)
                    }
                }
            }
            if (durationLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                ) {
                    PreviewMetadataLabel(durationLabel)
                }
            }
        }

        if (supportingContent.showTitleFooter) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = requireNotNull(title),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class SearchCardSupportingContent(
    val showTitleFooter: Boolean,
    val showPreviewContext: Boolean,
)

internal fun searchCardSupportingContent(
    title: String?,
    metadataLabel: String?,
    showSourceBadge: Boolean,
): SearchCardSupportingContent {
    return SearchCardSupportingContent(
        showTitleFooter = title != null,
        showPreviewContext = metadataLabel != null || showSourceBadge,
    )
}

@Composable
private fun PreviewMetadataLabel(label: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ImageCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Gray.copy(alpha = 0.65f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = "Image count",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

internal fun postMediaCount(post: Post): Int {
    post.mediaCount?.takeIf { it > 0 }?.let { return it }
    val explicitCount = post.media.count { !it.url.isNullOrBlank() || !it.localPath.isNullOrBlank() }
    return when {
        explicitCount > 0 -> explicitCount
        post.full != null -> 1
        else -> 1
    }
}

internal fun searchCardImageCandidates(previewRef: ImageRef?): List<String> {
    return buildList {
        previewRef?.url?.takeIf(String::isNotBlank)?.let(::add)
        addAll(previewRef?.progressiveUrls.orEmpty().filter(String::isNotBlank))
    }.distinct()
}

private fun resolveCardVideoRef(post: Post): ImageRef? {
    return postPlaybackMediaCandidate(post)?.ref
}

internal fun allowsInlineAutoplayInSearch(post: Post): Boolean {
    return post.id.source != SourceKey.IWARA && post.id.source != SourceKey.HITOMI
}

internal fun searchCardTestTag(postId: PostId): String = "search_card_${postId.mediaTestTagPart()}"

internal fun searchVideoTestTag(postId: PostId): String = "search_video_${postId.mediaTestTagPart()}"

@OptIn(ExperimentalMaterial3Api::class)
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
    onNhentaiFullColorFilterChange: (Boolean) -> Unit,
    nhentaiLanguageFilter: NhentaiLanguageFilter,
    onNhentaiLanguageFilterChange: (NhentaiLanguageFilter) -> Unit,
    onSortChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var minScoreInput by remember(query.minScore) {
        mutableStateOf(query.minScore?.toString().orEmpty())
    }
    var selectedPreset by remember(query.dateRange) {
        mutableStateOf(inferPreset(query.dateRange?.fromEpochMs, query.dateRange?.toEpochMs))
    }
    val focusManager = LocalFocusManager.current

    FeedFilterSheet(onDismiss = onDismiss) {
            Text("Visibility", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showAnimatedOnlyFilter) {
                    item {
                        FilterChip(
                            selected = animatedOnly,
                            onClick = { onAnimatedOnlyChange(!animatedOnly) },
                            label = { Text("Animated only") },
                        )
                    }
                }
                item {
                    FilterChip(
                        selected = hideLiked,
                        onClick = { onHideLikedChange(!hideLiked) },
                        label = { Text("Hide liked") },
                    )
                }
                item {
                    FilterChip(
                        selected = hideSaved,
                        onClick = { onHideSavedChange(!hideSaved) },
                        label = { Text("Hide saved") },
                    )
                }
                item {
                    FilterChip(
                        selected = hideWatched,
                        onClick = { onHideWatchedChange(!hideWatched) },
                        label = { Text("Hide Watched") },
                    )
                }
                if (!showAnimatedOnlyFilter) {
                    item {
                        FilterChip(
                            selected = nhentaiFullColorFilter,
                            onClick = {
                                onNhentaiFullColorFilterChange(!nhentaiFullColorFilter)
                            },
                            label = { Text("Full Color") },
                        )
                    }
                }
            }

            if (showAnimatedOnlyFilter) {
                AnimatedDurationRangeControl(
                    range = animatedDurationRange,
                    onRangeChange = onAnimatedDurationRangeChange,
                )
            }

            if (!showAnimatedOnlyFilter) {
                HorizontalDivider()
                Text("Language", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(NhentaiLanguageFilter.entries.size) { index ->
                        val languageFilter = NhentaiLanguageFilter.entries[index]
                        val label = when (languageFilter) {
                            NhentaiLanguageFilter.ANY -> "Any"
                            NhentaiLanguageFilter.ENGLISH -> "English"
                            NhentaiLanguageFilter.CHINESE -> "Chinese"
                            NhentaiLanguageFilter.JAPANESE -> "Japanese"
                        }
                        FilterChip(
                            selected = nhentaiLanguageFilter == languageFilter,
                            onClick = {
                                if (nhentaiLanguageFilter == languageFilter) return@FilterChip
                                onNhentaiLanguageFilterChange(languageFilter)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Sort", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SortMode.entries.size) { index ->
                    val mode = SortMode.entries[index]
                    FilterChip(
                        selected = query.sort == mode,
                        onClick = {
                            if (query.sort == mode) return@FilterChip
                            onAction(SearchAction.SelectSort(mode))
                            onSortChanged()
                        },
                        label = { Text(mode.name) },
                    )
                }
            }

            HorizontalDivider()
            Text("Date range", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DateRangePreset.entries.size) { index ->
                    val preset = DateRangePreset.entries[index]
                    val label = when (preset) {
                        DateRangePreset.NONE -> "None"
                        DateRangePreset.TODAY -> "Today"
                        DateRangePreset.LAST_7_DAYS -> "7d"
                        DateRangePreset.LAST_30_DAYS -> "30d"
                    }
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            onAction(SearchAction.SetDateRangePreset(preset))
                        },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()
            Text("Min score", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = minScoreInput,
                onValueChange = { value ->
                    minScoreInput = value.filter { it.isDigit() }
                    onAction(SearchAction.SetMinimumScore(minScoreInput.toIntOrNull()))
                },
                label = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = {
                    onAction(SearchAction.ResetFilters)
                    selectedPreset = DateRangePreset.NONE
                    minScoreInput = ""
                }) {
                    Text("Reset")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
    }
}

@Composable
fun AnimatedDurationRangeControl(
    range: AnimatedDurationRange,
    onRangeChange: (AnimatedDurationRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Animated duration", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (range.isFullRange) "Any" else animatedDurationRangeLabel(range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RangeSlider(
            value = range.normalizedMinBucket.toFloat()..range.normalizedMaxBucket.toFloat(),
            onValueChange = { values ->
                val minBucket = values.start.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                val maxBucket = values.endInclusive.roundToInt()
                    .coerceIn(ANIMATED_DURATION_MIN_BUCKET, ANIMATED_DURATION_MAX_BUCKET)
                onRangeChange(
                    AnimatedDurationRange(
                        minBucket = minOf(minBucket, maxBucket),
                        maxBucket = maxOf(minBucket, maxBucket),
                    )
                )
            },
            valueRange = ANIMATED_DURATION_MIN_BUCKET.toFloat()..ANIMATED_DURATION_MAX_BUCKET.toFloat(),
            steps = (ANIMATED_DURATION_MAX_BUCKET - ANIMATED_DURATION_MIN_BUCKET - 1).coerceAtLeast(0),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = animatedDurationBucketLabel(ANIMATED_DURATION_MIN_BUCKET),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = animatedDurationBucketLabel(ANIMATED_DURATION_MAX_BUCKET),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteTagSheet(
    sections: List<FavoriteTagSection>,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    emptyMessage: String,
    onAddTag: (String) -> Unit,
    onRemoveTag: (SourceKey, String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Favorite Tags", style = MaterialTheme.typography.titleMedium)
            if (sections.isEmpty()) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sections.forEachIndexed { index, section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = section.source.displayName(),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FavoriteTagActionGrid(
                            source = section.source,
                            tags = section.tags,
                            tagVideoCountProvider = tagVideoCountProvider,
                            fetchTagVideoCounts = fetchTagVideoCounts,
                            onAddTag = onAddTag,
                            onRemoveTag = { tag ->
                                onRemoveTag(section.source, tag)
                            },
                        )
                    }
                    if (index != sections.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun SearchScopeRow(
    scopes: List<FacetedSearchScope>,
    selectedScope: FacetedSearchScope,
    onScopeSelected: (FacetedSearchScope) -> Boolean,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val allScope = scopes.firstOrNull(FacetedSearchScope::isAll)
    val primaryScopes = primarySearchFacets.mapNotNull { facet ->
        scopes.firstOrNull { scope -> scope.facet == facet }
    }
    val secondaryScopes = secondarySearchFacets.mapNotNull { facet ->
        scopes.firstOrNull { scope -> scope.facet == facet }
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        allScope?.let { scope ->
            item {
                FilterChip(
                    selected = selectedScope.isAll,
                    onClick = { onScopeSelected(scope) },
                    label = { Text(searchScopeLabel(scope)) },
                )
            }
        }
        items(primaryScopes.size) { index ->
            val scope = primaryScopes[index]
            FilterChip(
                selected = selectedScope.facet == scope.facet &&
                    (selectedScope == scope || scope.facet == SearchFacet.TAG),
                onClick = { onScopeSelected(scope) },
                label = { Text(searchScopeLabel(scope)) },
            )
        }
        if (secondaryScopes.isNotEmpty()) {
            item {
                Box {
                    val selectedSecondary = secondaryScopes.firstOrNull { scope ->
                        scope.facet == selectedScope.facet
                    }
                    FilterChip(
                        selected = selectedSecondary != null,
                        onClick = { moreExpanded = true },
                        label = {
                            Text(selectedSecondary?.let(::searchScopeLabel) ?: "More")
                        },
                    )
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                    ) {
                        secondaryScopes.forEach { scope ->
                            DropdownMenuItem(
                                text = { Text(searchScopeLabel(scope)) },
                                onClick = {
                                    onScopeSelected(scope)
                                    moreExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FacetedAutocompletePanel(
    suggestions: List<FacetedTagSuggestion>,
    onInclude: (FacetedTagSuggestion) -> Unit,
    onExclude: (FacetedTagSuggestion) -> Unit,
) {
    AutocompleteListShell(
        items = suggestions,
    ) { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = facetedSuggestionMetaLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onInclude(item) }) {
                    Text("+")
                }
                TextButton(onClick = { onExclude(item) }) {
                    Text("−")
                }
            }
        }
    }
}

@Composable
private fun AutocompletePanel(
    suggestions: List<TagSuggestion>,
    onInclude: (String) -> Unit,
    onExclude: (String) -> Unit,
) {
    AutocompleteListShell(
        items = suggestions,
    ) { item ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.text, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(item.type, item.count?.toString()).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(text = meta, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onInclude(item.text) }) {
                    Text("+")
                }
                TextButton(onClick = { onExclude(item.text) }) {
                    Text("-")
                }
            }
        }
    }
}

@Composable
internal fun ModeRow(
    draftSourceScope: SearchSourceScope,
    options: List<QueryMode>,
    unifiedSourceCount: Int,
    onModeSelected: (QueryMode) -> Unit,
    onTemporarySourceToggled: (SourceKey) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            ModeChip(
                option = options[index],
                draftSourceScope = draftSourceScope,
                unifiedSourceCount = unifiedSourceCount,
                onModeSelected = onModeSelected,
                onTemporarySourceToggled = onTemporarySourceToggled,
            )
        }
    }
}

@Composable
private fun ModeChip(
    option: QueryMode,
    draftSourceScope: SearchSourceScope,
    unifiedSourceCount: Int,
    onModeSelected: (QueryMode) -> Unit,
    onTemporarySourceToggled: (SourceKey) -> Unit,
) {
    val source = (option as? QueryMode.Source)?.source
    val selected = when {
        option == QueryMode.Unified -> draftSourceScope is SearchSourceScope.GlobalUnified
        source != null -> source in draftSourceScope.explicitSources
        else -> false
    }
    val label = option.sourceChipLabel()
    Box {
        FilterChip(
            selected = selected,
            modifier = Modifier.clearAndSetSemantics {},
            onClick = {},
            label = {
                ModeChipLabel(
                    option = option,
                    source = source,
                    unifiedSourceCount = unifiedSourceCount,
                )
            },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    role = Role.Checkbox,
                    onClickLabel = "Select $label",
                    onLongClickLabel = source?.let {
                        "Add or remove $label from temporary source search"
                    },
                    onClick = { onModeSelected(option) },
                    onLongClick = source?.let { selectedSource ->
                        { onTemporarySourceToggled(selectedSource) }
                    },
                )
                .semantics {
                    this.selected = selected
                    contentDescription = label
                },
        )
    }
}

@Composable
private fun ModeChipLabel(
    option: QueryMode,
    source: SourceKey?,
    unifiedSourceCount: Int,
) {
    if (option == QueryMode.Unified) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Unified")
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = unifiedSourceCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    } else {
        SourceLogo(source = requireNotNull(source), size = 18.dp)
    }
}

private fun QueryMode.sourceChipLabel(): String {
    return when (this) {
        QueryMode.Unified -> "Unified"
        is QueryMode.Source -> source.displayName()
    }
}

@Composable
private fun SourceBadge(
    source: SourceKey,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (source.isRule34Family()) {
            Color.Transparent
        } else {
            Color.Black.copy(alpha = 0.55f)
        },
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            SourceLogo(
                source = source,
                size = if (source.isRule34Family()) 0.dp else 12.dp,
            )
        }
    }
}

@Composable
private fun TagRow(
    includeTerms: List<SearchTerm>,
    excludeTerms: List<SearchTerm>,
    onRemoveInclude: (SearchTerm) -> Unit,
    onRemoveExclude: (SearchTerm) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(includeTerms.size) { index ->
            val term = includeTerms[index]
            AssistChip(
                onClick = { onRemoveInclude(term) },
                label = { Text(searchTermChipLabel(term, excluded = false)) }
            )
        }
        items(excludeTerms.size) { index ->
            val term = excludeTerms[index]
            AssistChip(
                onClick = { onRemoveExclude(term) },
                label = { Text(searchTermChipLabel(term, excluded = true)) }
            )
        }
    }
}

@Composable
private fun StatusRow(statuses: List<SourceRunStatus>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(statuses.size) { index ->
            val status = statuses[index]
            val text = sourceStatusChipText(status)
            AssistChip(onClick = {}, label = { Text(text) })
        }
    }
}

@Composable
private fun EmptyBlock(
    hasPendingChanges: Boolean,
    messageOverride: String? = null,
) {
    FeedEmptyTile(
        message = messageOverride ?: if (hasPendingChanges) {
                "Draft updated. Press Apply to refresh results."
            } else {
                "No results yet. Add tags and press Apply to start searching."
            },
        contentPadding = 24.dp,
    )
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: (() -> Unit)? = null,
    title: String = "Could not load results",
    actionLabel: String = "Retry",
) {
    FeedErrorTile(
        message = message,
        title = title,
        actionLabel = actionLabel,
        onRetry = onRetry,
    )
}

internal fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}

private const val PAGINATION_PREFETCH_RATIO = 0.8f
private const val ANIMATED_PREFETCH_MIN_VISIBLE = 12
