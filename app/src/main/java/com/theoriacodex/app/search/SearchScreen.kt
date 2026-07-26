@file:androidx.annotation.OptIn(UnstableApi::class)

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
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.animatedDurationBucketLabel
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.animatedDurationRangeLabel
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.media.probeRemoteVideoDurationMs
import com.theoriacodex.app.recommend.recommendationIncludeTags
import com.theoriacodex.app.recommend.recommendationTagsFor
import com.theoriacodex.app.recommend.associatedDisplayTag
import com.theoriacodex.app.recommend.buildSourceTagAffinity
import com.theoriacodex.app.R
import com.theoriacodex.app.source.SourceLogo
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.isRule34Family
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.ui.components.AutocompleteListShell
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedLoadingState
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
import com.theoriacodex.app.viewer.createLoopingExoPlayer
import com.theoriacodex.app.viewer.createTexturePlayerView
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
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
    favoriteTags: Map<SourceKey, List<String>> = emptyMap(),
    resolveUnknownAnimatedDurations: Boolean = false,
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onAddFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
    onRemoveFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
) {
    val input = state.suggestions.input
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var hideLiked by rememberSaveable { mutableStateOf(false) }
    var hideSaved by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFavoriteTagSheet by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionPostResolving by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    var pendingScrollRestoration by remember { mutableStateOf(false) }
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
    LaunchedEffect(isNhentaiSourceMode) {
        if (isNhentaiSourceMode) {
            animatedOnly = false
            durationMinBucket = ANIMATED_DURATION_MIN_BUCKET
            durationMaxBucket = ANIMATED_DURATION_MAX_BUCKET
        }
    }
    val visibilityFilters = remember(animatedFilterActive, hideLiked, hideSaved, animatedDurationRange) {
        SearchVisibilityFilters(
            animatedOnly = animatedFilterActive,
            hideLiked = hideLiked,
            hideSaved = hideSaved,
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
    val visibleResults = remember(displayResults, visibilityFilters, likedPostIds, savedPostIds, unknownAnimatedDurationPolicy) {
        filterSearchResults(
            results = displayResults,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            savedPostIds = savedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        )
    }
    val durationResolutionRequests = remember(queryHash) { mutableSetOf<PostId>() }
    LaunchedEffect(displayResults, visibilityFilters, unknownAnimatedDurationPolicy, queryHash) {
        if (unknownAnimatedDurationPolicy != UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND) return@LaunchedEffect
        val candidates = animatedDurationResolutionCandidates(
            results = displayResults,
            filters = visibilityFilters,
        ).filter { post -> durationResolutionRequests.add(post.id) }
            .take(ANIMATED_DURATION_RESOLVE_BATCH_SIZE)
        candidates.forEach { post ->
            val resolved = runCatchingPreservingCancellation {
                resolvePostById(post.id)
            }.getOrNull()
            val candidate = resolved ?: post
            if (animatedDurationMs(candidate) == null) {
                val probedDurationMs = probeRemoteVideoDurationMs(candidate)
                if (probedDurationMs != null) {
                    onAction(SearchAction.RememberResolvedPost(candidate.copy(durationMs = probedDurationMs)))
                }
            }
        }
    }
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
    val displayTagSeedBySource = remember(
        state.query.applied.mode,
        state.query.applied.includeTerms,
        visibleResults,
    ) {
        val recommendationTags = state.query.applied.recommendationIncludeTags()
        when (val mode = state.query.applied.mode) {
            is QueryMode.Source -> mapOf(mode.source to recommendationTags)
            QueryMode.Unified -> {
                val sources = visibleResults
                    .asSequence()
                    .map { post -> post.id.source }
                    .toSet()
                sources.associateWith { recommendationTags }
            }
        }
    }
    val displayTagAffinityBySource = remember(visibleResults) {
        val documentsBySource = visibleResults
            .groupBy { post -> post.id.source }
            .mapValues { (_, posts) ->
                posts.map(::recommendationTagsFor)
            }
        buildSourceTagAffinity(documentsBySource = documentsBySource)
    }
    val displayTagByPostId = remember(
        visibleResults,
        displayTagSeedBySource,
        displayTagAffinityBySource,
    ) {
        visibleResults.associate { post ->
            post.id to associatedDisplayTag(
                post = post,
                seedTagsBySource = displayTagSeedBySource,
                affinityBySource = displayTagAffinityBySource,
            )
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

    LaunchedEffect(state.restoration) {
        if (state.restoration is SearchRestorationUiState.Restored) {
            // Restoration is a route-entry concern. Keep it pending until the first result set is
            // available, but never make later page appends eligible to replay the old position.
            pendingScrollRestoration = true
        }
    }

    LaunchedEffect(pendingScrollRestoration, visibleResults.size, animatedFilterActive) {
        if (!pendingScrollRestoration || animatedFilterActive) return@LaunchedEffect
        val restored = (state.restoration as? SearchRestorationUiState.Restored)?.scrollState
        if (restored == null) {
            pendingScrollRestoration = false
            return@LaunchedEffect
        }
        if (visibleResults.isNotEmpty()) {
            val lastIndex = visibleResults.lastIndex.coerceAtLeast(0)
            gridState.scrollToItem(
                index = restored.firstVisibleItemIndex.coerceIn(0, lastIndex),
                scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
            pendingScrollRestoration = false
        }
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
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.loadingMore
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (state.loading || state.loadingMore || !state.content.canLoadMore) return@collect

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
    val clearFocusInteraction = remember { MutableInteractionSource() }
    val showSearchControls = searchFieldFocused ||
        input.isNotBlank() ||
        autocompleteSuggestions.isNotEmpty() ||
        facetedAutocompleteSuggestions.isNotEmpty() ||
        state.hasPendingChanges

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
            FloatingActionButton(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = {
                    focusManager.clearFocus()
                    showFilterSheet = true
                },
            ) {
                Icon(Icons.Default.FilterList, contentDescription = "Filter and sort")
            }
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
                            if (supportedSearchScopes.isEmpty()) {
                                "tag or -tag"
                            } else {
                                "tag, artist:, or -tag"
                            },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            focusManager.clearFocus()
                            showFavoriteTagSheet = true
                        }) {
                            Text("List")
                        }
                        TextButton(
                            onClick = { commitTagInput() },
                            enabled = state.suggestions.canCommitInput,
                        ) {
                            Text("Add")
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
                        state.content.statuses.any { it.state != SourceRunState.SUCCESS }
                    ) {
                        StatusRow(statuses = state.content.statuses)
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() }
                    ) {
                        ErrorBlock(
                            message = state.content.error.message,
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
                            if (!state.content.hasExecutedSearch && !animatedFilterActive) {
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
                        ) { _, post ->
                            SearchResultCard(
                                post = post,
                                pixivUgoiraClient = pixivUgoiraClient,
                                showSourceBadge = state.query.appliedSourceScope !is SearchSourceScope.Single,
                                displayTag = displayTagByPostId[post.id],
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
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    showFilterSheet = false
                }
            },
            sheetState = sheetState,
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
    showSourceBadge: Boolean = false,
    displayTag: String? = null,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    resolvePostById: (suspend (PostId) -> Post?)? = null,
    recoverPostMedia: (suspend (Post, ImageRef) -> Post?)? = null,
    refreshOnPreviewError: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolvedPostOverride by remember(post.id) { mutableStateOf<Post?>(null) }
    var resolutionAttempted by remember(post.id) { mutableStateOf(false) }
    val mediaRecoveryAttemptedUrls = remember(post.id) { mutableSetOf<String>() }
    val effectivePost = resolvedPostOverride ?: post

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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        val title = effectivePost.title?.takeIf { it.isNotBlank() } ?: effectivePost.id.sourcePostId
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
        val imageModel = remember(context, previewUrl, effectivePost.id.source) {
            previewUrl?.let { buildImageRequest(context, it, effectivePost.id.source) }
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
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                )
            } else if (videoRef != null && !videoPlaybackFailed) {
                SearchVideoPreview(
                    media = videoRef,
                    sourceKey = effectivePost.id.source,
                    previewModel = imageModel,
                    modifier = Modifier.fillMaxSize(),
                    onPlaybackError = {
                        videoPlaybackFailed = true
                        if (resolvedPostOverride == null) {
                            requestResolvedCardPreview(force = refreshOnPreviewError)
                        }
                    },
                )
            } else if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(30.dp)
                        .clickable(onClick = onToggleLike),
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
                            contentDescription = if (liked) {
                                "Unlike post"
                            } else {
                                "Like post"
                            },
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

        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val firstTag = displayTag ?: effectivePost.canonicalTags.firstOrNull()
            if (firstTag != null || showSourceBadge) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (firstTag != null) {
                        Text(
                            text = "#$firstTag",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (showSourceBadge) {
                        SourceBadge(
                            source = effectivePost.id.source,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchVideoPreview(
    media: ImageRef,
    sourceKey: SourceKey,
    modifier: Modifier = Modifier,
    previewModel: Any? = null,
    onPlaybackError: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val location = media.localPath ?: media.url
    if (location.isNullOrBlank()) {
        onPlaybackError()
        return
    }

    var playerRef by remember(location, sourceKey) { mutableStateOf<ExoPlayer?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var didNotifyError by remember(location, sourceKey) { mutableStateOf(false) }
    var hasRenderedFirstFrame by remember(location, sourceKey) { mutableStateOf(false) }

    DisposableEffect(location, sourceKey, lifecycleOwner) {
        didNotifyError = false
        val player = createLoopingExoPlayer(
            context = context,
            location = location,
            headers = sourceKey.requestHeaders(),
            muted = true,
        )
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playerRef !== player) return
                if (playbackState != Player.STATE_READY) return
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                runCatching {
                    player.playWhenReady = true
                    player.play()
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                if (didNotifyError) return
                didNotifyError = true
                onPlaybackError()
            }
        }
        player.addListener(listener)
        playerRef = player
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    runCatching {
                        player.playWhenReady = true
                        player.play()
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    runCatching {
                        player.playWhenReady = false
                        player.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching {
                player.playWhenReady = true
                player.play()
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            runCatching {
                player.playWhenReady = false
                player.pause()
            }
            runCatching {
                playerViewRef?.player = null
            }
            runCatching {
                player.release()
            }
            if (playerRef === player) {
                playerRef = null
            }
            playerViewRef = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                createTexturePlayerView(factoryContext).apply {
                    player = playerRef
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    playerViewRef = this
                    isClickable = false
                    isFocusable = false
                }
            },
            update = { playerView ->
                playerViewRef = playerView
                playerView.useController = false
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                playerView.player = playerRef
                playerView.isClickable = false
                playerView.isFocusable = false
            },
        )
        if (!hasRenderedFirstFrame && previewModel != null) {
            AsyncImage(
                model = previewModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
    nhentaiFullColorFilter: Boolean,
    onNhentaiFullColorFilterChange: (Boolean) -> Unit,
    nhentaiLanguageFilter: NhentaiLanguageFilter,
    onNhentaiLanguageFilterChange: (NhentaiLanguageFilter) -> Unit,
    onSortChanged: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
) {
    var minScoreInput by remember(query.minScore) {
        mutableStateOf(query.minScore?.toString().orEmpty())
    }
    var selectedPreset by remember(query.dateRange) {
        mutableStateOf(inferPreset(query.dateRange?.fromEpochMs, query.dateRange?.toEpochMs))
    }
    val focusManager = LocalFocusManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
            val option = options[index]
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
    val filtered = statuses.filter { it.state != SourceRunState.SUCCESS }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filtered.size) { index ->
            val status = filtered[index]
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

enum class UnknownAnimatedDurationPolicy {
    HIDE_UNKNOWNS,
    RESOLVE_IN_BACKGROUND,
}

internal data class FavoriteTagSection(
    val source: SourceKey,
    val tags: List<String>,
)

internal fun filterSearchResults(
    results: List<Post>,
    filters: SearchVisibilityFilters,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy = UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
): List<Post> {
    return results.filter { post ->
        (!filters.animatedOnly || isAnimatedPost(post)) &&
            matchesAnimatedDurationFilter(
                post = post,
                filters = filters,
                unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
            ) &&
            (!filters.hideLiked || post.id !in likedPostIds) &&
            (!filters.hideSaved || post.id !in savedPostIds)
    }
}

internal fun animatedDurationResolutionCandidates(
    results: List<Post>,
    filters: SearchVisibilityFilters,
): List<Post> {
    if (filters.animatedDurationRange.isFullRange) return emptyList()
    return results.filter { post ->
        isAnimatedPost(post) && animatedDurationMs(post) == null
    }
}

private fun matchesAnimatedDurationFilter(
    post: Post,
    filters: SearchVisibilityFilters,
    unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy,
): Boolean {
    val range = filters.animatedDurationRange
    if (range.isFullRange) return true
    if (!isAnimatedPost(post)) return true
    val durationMs = animatedDurationMs(post) ?: return when (unknownAnimatedDurationPolicy) {
        UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS -> false
        UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND -> false
    }
    return range.contains(durationMs)
}

internal fun favoriteTagSections(
    mode: QueryMode,
    favoriteTags: Map<SourceKey, List<String>>,
    sourceDisplayOrder: List<SourceKey>,
): List<FavoriteTagSection> {
    val orderedSources = (sourceDisplayOrder + favoriteTags.keys)
        .distinct()
    return when (mode) {
        is QueryMode.Source -> {
            favoriteTags[mode.source]
                .orEmpty()
                .takeIf { tags -> tags.isNotEmpty() }
                ?.let { tags ->
                    listOf(FavoriteTagSection(source = mode.source, tags = tags))
                }
                .orEmpty()
        }

        QueryMode.Unified -> {
            orderedSources.mapNotNull { source ->
                favoriteTags[source]
                    .orEmpty()
                    .takeIf { tags -> tags.isNotEmpty() }
                    ?.let { tags -> FavoriteTagSection(source = source, tags = tags) }
            }
        }
    }
}

private fun buildEmptySearchMessage(
    sourceResults: List<Post>,
    visibilityFilters: SearchVisibilityFilters,
    loadingMore: Boolean,
    canLoadMore: Boolean,
): String? {
    if (!visibilityFilters.animatedDurationRange.isFullRange && sourceResults.isNotEmpty()) {
        if (loadingMore || canLoadMore) {
            return "No animated media in the selected duration range yet. Retrying with more pages..."
        }
        return "No animated media found in the selected duration range."
    }
    if (visibilityFilters.animatedOnly && sourceResults.isNotEmpty() && (loadingMore || canLoadMore)) {
        return "No animated media yet. Retrying with more pages..."
    }
    if (!visibilityFilters.animatedOnly && !visibilityFilters.hideLiked && !visibilityFilters.hideSaved) {
        return null
    }
    if (sourceResults.isEmpty()) {
        return null
    }
    return when {
        visibilityFilters.animatedOnly && !visibilityFilters.hideLiked && !visibilityFilters.hideSaved ->
            "No animated media found for the current results."

        visibilityFilters.hideLiked && visibilityFilters.hideSaved ->
            "No results remain after hiding liked and saved posts."

        visibilityFilters.hideLiked ->
            "No results remain after hiding liked posts."

        visibilityFilters.hideSaved ->
            "No results remain after hiding saved posts."

        else ->
            "No results remain after applying the current visibility filters."
    }
}

private fun inferPreset(fromEpochMs: Long?, toEpochMs: Long?): DateRangePreset {
    if (fromEpochMs == null || toEpochMs == null) {
        return DateRangePreset.NONE
    }
    val spanMs = abs(toEpochMs - fromEpochMs)
    val dayMs = 24L * 60L * 60L * 1000L
    return when {
        spanMs <= dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.TODAY
        spanMs <= 7L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_7_DAYS
        spanMs <= 30L * dayMs + (2L * 60L * 60L * 1000L) -> DateRangePreset.LAST_30_DAYS
        else -> DateRangePreset.NONE
    }
}

internal fun previewAspectRatio(post: Post): Float {
    val width = post.width ?: return 1f
    val height = post.height ?: return 1f
    if (width <= 0 || height <= 0) return 1f
    return width.toFloat() / height.toFloat()
}

private fun buildImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
): ImageRequest {
    return MediaRequestFactory.imageRequest(
        context = context,
        url = url,
        sourceKey = sourceKey,
        crossfade = false,
    )
}

private fun String.isDigitsOnly(): Boolean {
    return isNotBlank() && all { ch -> ch.isDigit() }
}

private fun Post.hasActionableTags(): Boolean {
    return canonicalTags.any { tag -> tag.isNotBlank() } ||
        rawTags.any { tag -> tag.isNotBlank() }
}

@OptIn(FlowPreview::class)
internal suspend fun Flow<Pair<Int, Int>>.persistDebouncedSearchScrollStates(
    debounceMillis: Long = SEARCH_SCROLL_PERSIST_DEBOUNCE_MS,
    persist: suspend (Pair<Int, Int>) -> Unit,
) {
    var latestObserved: Pair<Int, Int>? = null
    var lastCommitted: Pair<Int, Int>? = null
    try {
        distinctUntilChanged()
            .onEach { position -> latestObserved = position }
            .debounce(debounceMillis)
            .collect { position ->
                persist(position)
                lastCommitted = position
            }
    } finally {
        val pending = latestObserved
        if (pending != null && pending != lastCommitted) {
            withContext(NonCancellable) {
                persist(pending)
            }
        }
    }
}

private const val PAGINATION_PREFETCH_RATIO = 0.8f
internal const val SEARCH_SCROLL_PERSIST_DEBOUNCE_MS = 250L
private const val ANIMATED_PREFETCH_MIN_VISIBLE = 12
private const val ANIMATED_DURATION_RESOLVE_BATCH_SIZE = 8
