@file:androidx.annotation.OptIn(UnstableApi::class)

package com.theoriacodex.app.search

import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.theoriacodex.app.media.copyPostTagsToClipboard
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.media.postPlaybackMediaCandidate
import com.theoriacodex.app.media.postPreviewImageCandidate
import com.theoriacodex.app.media.probeRemoteVideoDurationMs
import com.theoriacodex.app.recommend.recommendationIncludeTags
import com.theoriacodex.app.recommend.recommendationTagsFor
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.recommend.associatedDisplayTag
import com.theoriacodex.app.recommend.buildSourceTagAffinity
import com.theoriacodex.app.R
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.app.tags.FavoriteTagActionGrid
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.PixivUgoiraPlayer
import com.theoriacodex.app.viewer.createLoopingExoPlayer
import com.theoriacodex.app.viewer.createTexturePlayerView
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.FacetedSearchScope
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.decode.SvgDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    likedPostIds: Set<PostId> = emptySet(),
    savedPostIds: Set<PostId> = emptySet(),
    favoriteTags: Map<SourceKey, List<String>> = emptyMap(),
    resolveUnknownAnimatedDurations: Boolean = false,
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenViewer: (List<Post>, ViewerLaunchContext, SearchVisibilityFilters) -> Unit,
    onApplySearch: () -> Unit,
    onRetrySearch: () -> Unit,
    onOpenCreatorProfile: (CreatorProfile) -> Unit,
    onOpenLegacyCreatorProfile: (Post) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onAddFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
    onRemoveFavoriteTag: (SourceKey, String) -> Unit = { _, _ -> },
) {
    var input by rememberSaveable { mutableStateOf("") }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var hideLiked by rememberSaveable { mutableStateOf(false) }
    var hideSaved by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFavoriteTagSheet by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var selectedActionPostResolving by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    val queryHash = coordinator.appliedQueryHash
    val isNhentaiSourceMode = coordinator.draftQuery.mode == QueryMode.Source(SourceKey.NHENTAI)
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
    val displayResults = remember(coordinator.results, coordinator.displayResultsVersion, queryHash) {
        coordinator.displayResults()
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
            val resolved = runCatching { coordinator.resolvePostForSearch(post.id) }.getOrNull()
            val candidate = resolved ?: post
            if (animatedDurationMs(candidate) == null) {
                val probedDurationMs = probeRemoteVideoDurationMs(candidate)
                if (probedDurationMs != null) {
                    coordinator.rememberResolvedPost(candidate.copy(durationMs = probedDurationMs))
                }
            }
        }
    }
    fun openPostActionSheet(post: Post) {
        focusManager.clearFocus()
        val displayPost = coordinator.displayPost(post)
        selectedActionPost = displayPost
        selectedActionPostResolving = false
        if (displayPost.hasActionableTags()) return

        selectedActionPostResolving = true
        scope.launch {
            val resolved = runCatching { coordinator.resolvePostForSearch(post.id) }.getOrNull()
            if (selectedActionPost?.id == post.id) {
                selectedActionPost = resolved ?: displayPost
                selectedActionPostResolving = false
            }
        }
    }
    val displayTagSeedBySource = remember(
        coordinator.appliedQuery.mode,
        coordinator.appliedQuery.includeTerms,
        visibleResults,
    ) {
        val recommendationTags = coordinator.appliedQuery.recommendationIncludeTags()
        when (val mode = coordinator.appliedQuery.mode) {
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
    val sourceDisplayOrder = remember(coordinator.modeOptions) {
        coordinator.modeOptions
            .mapNotNull { mode -> (mode as? QueryMode.Source)?.source }
    }
    val favoriteSections = remember(coordinator.draftQuery.mode, favoriteTags, sourceDisplayOrder) {
        favoriteTagSections(
            mode = coordinator.draftQuery.mode,
            favoriteTags = favoriteTags,
            sourceDisplayOrder = sourceDisplayOrder,
        )
    }
    val favoriteTagEmptyMessage = remember(coordinator.draftQuery.mode) {
        when (val mode = coordinator.draftQuery.mode) {
            is QueryMode.Source -> "No favorite tags saved for ${mode.source.displayName()} yet."
            QueryMode.Unified -> "No favorite tags saved yet."
        }
    }
    suspend fun resetScrollToTop() {
        if (visibleResults.isNotEmpty()) {
            runCatching {
                gridState.scrollToItem(index = 0, scrollOffset = 0)
            }
        }
        coordinator.persistSearchScrollState(index = 0, offsetPx = 0)
    }

    LaunchedEffect(coordinator.draftQuery.mode) {
        coordinator.loadTrendingTags()
    }

    LaunchedEffect(
        coordinator.draftQuery.mode,
        coordinator.selectedSearchScope,
        input,
        searchFieldFocused,
    ) {
        if (!searchFieldFocused) {
            coordinator.clearAutocompleteSuggestions()
            return@LaunchedEffect
        }
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            coordinator.clearAutocompleteSuggestions()
            return@LaunchedEffect
        }
        delay(300)
        coordinator.refreshAutocompleteSuggestions(trimmed)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    coordinator.restoreLastAppliedSearchIfNeeded()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(queryHash, visibleResults.size, animatedFilterActive) {
        if (animatedFilterActive) return@LaunchedEffect
        val restored = coordinator.restoreSearchScrollState() ?: return@LaunchedEffect
        if (visibleResults.isNotEmpty()) {
            val lastIndex = visibleResults.lastIndex.coerceAtLeast(0)
            gridState.scrollToItem(
                index = restored.firstVisibleItemIndex.coerceIn(0, lastIndex),
                scrollOffset = restored.firstVisibleItemOffsetPx.coerceAtLeast(0),
            )
        }
    }

    LaunchedEffect(queryHash, visibleResults.size, animatedFilterActive) {
        if (animatedFilterActive || visibleResults.isEmpty()) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                coordinator.persistSearchScrollState(index = index, offsetPx = offset)
            }
    }

    LaunchedEffect(
        queryHash,
        visibleResults.size,
        coordinator.results.size,
        coordinator.canLoadMore,
        coordinator.loading,
        coordinator.loadingMore,
        animatedFilterActive,
        animatedDurationFilterActive,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to coordinator.loadingMore
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (coordinator.loading || coordinator.loadingMore || !coordinator.canLoadMore) return@collect

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
                    coordinator.results.isNotEmpty()

            if (shouldTriggerByThreshold || shouldTriggerForAnimatedBuffer) {
                coordinator.loadNextPage()
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

    val autocompleteSuggestions = coordinator.autocompleteSuggestions
    val facetedAutocompleteSuggestions = coordinator.facetedAutocompleteSuggestions
    val supportedSearchScopes = coordinator.supportedSearchScopes
    val sourceAuthErrorMessage = remember(coordinator.statuses) {
        buildSourceAuthErrorMessage(coordinator.statuses)
    }
    val sourceFailureMessage = remember(coordinator.statuses) {
        buildSourceFailureMessage(coordinator.statuses)
    }
    val clearFocusInteraction = remember { MutableInteractionSource() }
    val showSearchControls = searchFieldFocused ||
        input.isNotBlank() ||
        autocompleteSuggestions.isNotEmpty() ||
        facetedAutocompleteSuggestions.isNotEmpty() ||
        coordinator.hasPendingChanges

    fun commitTagInput() {
        val typed = input.trim()
        val committed = coordinator.commitTagInput(input)
        if (committed) {
            input = ""
            focusManager.clearFocus()
            if (typed.isDigitsOnly() && coordinator.directNhentaiGalleryIdCandidate() != null) {
                coordinator.clearAutocompleteSuggestions()
                onApplySearch()
                scope.launch { resetScrollToTop() }
            }
        }
    }

    fun applyDraftAndResetScroll() {
        val pendingTyped = input.trim()
        if (pendingTyped.isDigitsOnly() && coordinator.canCommitTagInput(pendingTyped)) {
            if (coordinator.commitTagInput(pendingTyped)) {
                input = ""
            }
        }
        focusManager.clearFocus(force = true)
        coordinator.clearAutocompleteSuggestions()
        onApplySearch()
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
                    },
                value = input,
                onValueChange = {
                    input = it
                    coordinator.clearTagInputValidationMessage()
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                isError = coordinator.tagInputValidationMessage != null,
                supportingText = coordinator.tagInputValidationMessage?.let { message ->
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
                            enabled = coordinator.canCommitTagInput(input),
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
                            selectedScope = coordinator.selectedSearchScope,
                            onScopeSelected = coordinator::selectSearchScope,
                        )
                    }

                    if (facetedAutocompleteSuggestions.isNotEmpty()) {
                        FacetedAutocompletePanel(
                            suggestions = facetedAutocompleteSuggestions,
                            onInclude = { suggestion ->
                                coordinator.addIncludeSuggestion(suggestion)
                                coordinator.clearTagInputValidationMessage()
                                input = ""
                            },
                            onExclude = { suggestion ->
                                coordinator.addExcludeSuggestion(suggestion)
                                coordinator.clearTagInputValidationMessage()
                                input = ""
                            },
                        )
                    } else if (autocompleteSuggestions.isNotEmpty()) {
                        AutocompletePanel(
                            suggestions = autocompleteSuggestions,
                            onInclude = { tag ->
                                coordinator.addIncludeTag(tag)
                                coordinator.clearTagInputValidationMessage()
                                input = ""
                            },
                            onExclude = { tag ->
                                coordinator.addExcludeTag(tag)
                                coordinator.clearTagInputValidationMessage()
                                input = ""
                            },
                        )
                    }

                    ModeRow(
                        mode = coordinator.draftQuery.mode,
                        options = coordinator.modeOptions,
                        unifiedSourceCount = coordinator.enabledSourceCount,
                        onModeSelected = coordinator::setMode,
                    )

                    TagRow(
                        includeTerms = coordinator.draftQuery.includeTerms,
                        excludeTerms = coordinator.draftQuery.excludeTerms,
                        onRemoveInclude = coordinator::removeIncludeTerm,
                        onRemoveExclude = coordinator::removeExcludeTerm,
                    )

                    if (
                        coordinator.appliedQuery.mode == QueryMode.Unified &&
                        coordinator.statuses.any { it.state != SourceRunState.SUCCESS }
                    ) {
                        StatusRow(coordinator = coordinator)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = {
                                focusManager.clearFocus()
                                coordinator.clearDraft()
                                input = ""
                                showFilterSheet = false
                                scope.launch { resetScrollToTop() }
                            },
                            enabled = coordinator.hasPendingChanges,
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
                coordinator.loading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() },
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                coordinator.errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = clearFocusInteraction,
                                indication = null,
                            ) { focusManager.clearFocus() }
                    ) {
                        ErrorBlock(
                            message = coordinator.errorMessage.orEmpty(),
                            onRetry = {
                                focusManager.clearFocus()
                                onRetrySearch()
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
                                        onRetrySearch()
                                    },
                                )
                            }
                            if (!coordinator.hasAnySearchRun && !animatedFilterActive) {
                                SearchStartSplash(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                EmptyBlock(
                                    hasPendingChanges = coordinator.hasPendingChanges,
                                    messageOverride = buildEmptySearchMessage(
                                        sourceResults = coordinator.results,
                                        visibilityFilters = visibilityFilters,
                                        loadingMore = coordinator.loadingMore,
                                        canLoadMore = coordinator.canLoadMore,
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
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier.weight(1f),
                            verticalItemSpacing = 6.dp,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(
                                items = visibleResults,
                                key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
                            ) { index, post ->
                                SearchResultCard(
                                    post = post,
                                    pixivUgoiraClient = pixivUgoiraClient,
                                    showSourceBadge = coordinator.appliedQuery.mode == QueryMode.Unified,
                                    displayTag = displayTagByPostId[post.id],
                                    liked = post.id in likedPostIds,
                                    onToggleLike = onToggleLike?.let { toggle ->
                                        { toggle(post) }
                                    },
                                    resolvePostById = { postId -> coordinator.resolvePostForSearch(postId) },
                                    recoverPostMedia = { failedPost, failedMedia ->
                                        coordinator.recoverPostMedia(failedPost, failedMedia)
                                    },
                                    onClick = {
                                        focusManager.clearFocus()
                                        val context = coordinator.buildViewerLaunchContext(
                                            startIndex = index,
                                            scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        )
                                        scope.launch { coordinator.setViewerLaunchContext(context) }
                                        onOpenViewer(visibleResults, context, visibilityFilters)
                                    },
                                    onLongPress = {
                                        openPostActionSheet(post)
                                    },
                                )
                            }
                            if (coordinator.loadingMore) {
                                item {
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        val context = LocalContext.current
        val actionSheetHorizontalPadding = 16.dp
        ModalBottomSheet(
            onDismissRequest = {
                selectedActionPost = null
                selectedActionPostResolving = false
            },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = actionSheetHorizontalPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = {
                            selectedActionPost = null
                            selectedActionPostResolving = false
                            onSaveToDevice(post)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Save to device",
                        )
                    }
                    IconButton(
                        onClick = {
                            selectedActionPost = null
                            selectedActionPostResolving = false
                            onRequestSaveToCodex(post)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save to Codex",
                        )
                    }
                    IconButton(
                        onClick = {
                            copyPostTagsToClipboard(context, post)
                            Toast.makeText(context, "Tags copied", Toast.LENGTH_SHORT).show()
                            selectedActionPost = null
                            selectedActionPostResolving = false
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy tags",
                        )
                    }
                    IconButton(
                        onClick = {
                            val copied = copyPostUrlToClipboard(context, post)
                            val message = if (copied) {
                                "Post URL copied"
                            } else {
                                "No post URL available"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            selectedActionPost = null
                            selectedActionPostResolving = false
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                        )
                    }
                }
                Text(
                    text = post.title?.takeIf { it.isNotBlank() } ?: post.id.sourcePostId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                CreatorProfileActionButton(
                    post = post,
                    onOpenProfile = { profile ->
                        selectedActionPost = null
                        selectedActionPostResolving = false
                        onOpenCreatorProfile(profile)
                    },
                    onOpenLegacyPost = {
                        selectedActionPost = null
                        selectedActionPostResolving = false
                        onOpenLegacyCreatorProfile(post)
                    },
                )
                HorizontalDivider()
                if (selectedActionPostResolving && !post.hasActionableTags()) {
                    Text(
                        text = "Loading tags...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PostTagActionSection(
                        post = post,
                        tagVideoCountProvider = coordinator::tagVideoCount,
                        fetchTagVideoCounts = coordinator::fetchTagVideoCounts,
                        onAddIncludeTerm = { term -> coordinator.addPostIncludeTerm(post, term) },
                        onAddExcludeTerm = { term -> coordinator.addPostExcludeTerm(post, term) },
                        onRemoveIncludeTerm = coordinator::removeIncludeTerm,
                        onRemoveExcludeTerm = coordinator::removeExcludeTerm,
                        onFavoriteTagLongPress = onAddFavoriteTag,
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedActionPost = null
                        selectedActionPostResolving = false
                    },
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    if (showFavoriteTagSheet) {
        FavoriteTagSheet(
            sections = favoriteSections,
            tagVideoCountProvider = coordinator::tagVideoCount,
            fetchTagVideoCounts = coordinator::fetchTagVideoCounts,
            emptyMessage = favoriteTagEmptyMessage,
            onAddTag = { tag ->
                coordinator.addIncludeTag(tag)
            },
            onRemoveTag = { source, tag ->
                onRemoveFavoriteTag(source, tag)
            },
            onDismiss = { showFavoriteTagSheet = false },
        )
    }

    if (showFilterSheet) {
        FilterSheet(
            coordinator = coordinator,
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
            nhentaiFullColorFilter = coordinator.selectedNhentaiFullColorFilter(),
            onNhentaiFullColorFilterChange = { enabled ->
                coordinator.setNhentaiFullColorFilter(enabled)
                applyDraftAndResetScroll()
            },
            nhentaiLanguageFilter = coordinator.selectedNhentaiLanguageFilter(),
            onNhentaiLanguageFilterChange = { filter ->
                coordinator.setNhentaiLanguageFilter(filter)
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
            val resolved = runCatching { resolver(post.id) }.getOrNull() ?: return@launch
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
    previewModel: Any? = null,
    modifier: Modifier = Modifier,
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
    coordinator: SearchCoordinator,
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
    var minScoreInput by remember(coordinator.draftQuery.minScore) {
        mutableStateOf(coordinator.draftQuery.minScore?.toString().orEmpty())
    }
    var selectedPreset by remember(coordinator.draftQuery.dateRange) {
        mutableStateOf(inferPreset(coordinator.draftQuery.dateRange?.fromEpochMs, coordinator.draftQuery.dateRange?.toEpochMs))
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
                        selected = coordinator.draftQuery.sort == mode,
                        onClick = {
                            if (coordinator.draftQuery.sort == mode) return@FilterChip
                            coordinator.setSort(mode)
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
                            coordinator.setDateRangePreset(preset)
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
                    coordinator.setMinScore(minScoreInput.toIntOrNull())
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
                    coordinator.resetFilters()
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
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(suggestions.size) { index ->
                val item = suggestions[index]
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
                if (index != suggestions.lastIndex) {
                    HorizontalDivider()
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
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(suggestions.size) { index ->
                val item = suggestions[index]
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
                if (index != suggestions.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    mode: QueryMode,
    options: List<QueryMode>,
    unifiedSourceCount: Int,
    onModeSelected: (QueryMode) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option == mode,
                onClick = { onModeSelected(option) },
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
                        val source = (option as QueryMode.Source).source
                        SourceChipLogo(source = source)
                    }
                }
            )
        }
    }
}

@Composable
private fun SourceChipLogo(source: SourceKey) {
    SourceChipLogo(
        source = source,
        size = 18.dp,
    )
}

@Composable
private fun SourceChipLogo(
    source: SourceKey,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun svgLogo(rawResId: Int, contentDescription: String) {
        val context = LocalContext.current
        val model = remember(context, rawResId) {
            ImageRequest.Builder(context)
                .data(Uri.parse("android.resource://${context.packageName}/$rawResId"))
                .decoderFactory(SvgDecoder.Factory())
                .build()
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier.height(size),
            contentScale = ContentScale.Fit,
        )
    }

    when (source) {
        SourceKey.PIXIV -> {
            Image(
                painter = painterResource(id = R.drawable.pixiv_logo),
                contentDescription = "Pixiv",
                modifier = modifier.height(size),
                contentScale = ContentScale.Fit,
            )
        }

        SourceKey.GELBOORU -> {
            svgLogo(R.raw.gelbooru_logo, "Gelbooru")
        }

        SourceKey.NHENTAI -> {
            svgLogo(R.raw.nhentai_logo, "NHentai")
        }

        SourceKey.HITOMI -> {
            Image(
                painter = painterResource(id = R.drawable.hitomi_logo),
                contentDescription = "Hitomi",
                modifier = modifier
                    .size(size)
                    .background(
                        color = Color.Black,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(2.dp),
                contentScale = ContentScale.Fit,
            )
        }

        SourceKey.RULE34XXX -> {
            Text(
                text = source.displayName(),
                modifier = modifier,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }

        SourceKey.RULE34PAHEAL -> {
            Text(
                text = source.displayName(),
                modifier = modifier,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }

        SourceKey.RULE34VIDEO -> {
            Text(
                text = source.displayName(),
                modifier = modifier,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }

        SourceKey.RULE34GEN -> {
            Text(
                text = source.displayName(),
                modifier = modifier,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }

        else -> Text(source.displayName())
    }
}

@Composable
private fun SourceBadge(
    source: SourceKey,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (isRule34FamilySource(source)) {
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
            SourceChipLogo(
                source = source,
                size = if (isRule34FamilySource(source)) 0.dp else 12.dp,
            )
        }
    }
}

private fun isRule34FamilySource(source: SourceKey): Boolean {
    return source == SourceKey.RULE34XXX ||
        source == SourceKey.RULE34PAHEAL ||
        source == SourceKey.RULE34VIDEO ||
        source == SourceKey.RULE34GEN
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
private fun StatusRow(coordinator: SearchCoordinator) {
    val filtered = coordinator.statuses.filter { it.state != SourceRunState.SUCCESS }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = messageOverride ?: if (hasPendingChanges) {
                "Draft updated. Press Apply to refresh results."
            } else {
                "No results yet. Add tags and press Apply to start searching."
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onRetry: (() -> Unit)? = null,
    title: String = "Could not load results",
    actionLabel: String = "Retry",
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (onRetry != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
        }
    }
}

data class SearchVisibilityFilters(
    val animatedOnly: Boolean = false,
    val hideLiked: Boolean = false,
    val hideSaved: Boolean = false,
    val animatedDurationRange: AnimatedDurationRange = AnimatedDurationRange.Full,
)

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

private const val PAGINATION_PREFETCH_RATIO = 0.8f
private const val ANIMATED_PREFETCH_MIN_VISIBLE = 12
private const val ANIMATED_DURATION_RESOLVE_BATCH_SIZE = 8
