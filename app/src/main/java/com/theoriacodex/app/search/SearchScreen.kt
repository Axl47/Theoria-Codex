package com.theoriacodex.app.search

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.app.media.isAnimatedPost
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.recommend.associatedDisplayTag
import com.theoriacodex.app.recommend.buildSourceTagAffinity
import com.theoriacodex.app.R
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.PixivUgoiraPlayer
import com.theoriacodex.app.viewer.createLoopingExoPlayer
import com.theoriacodex.app.viewer.createTexturePlayerView
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.adapter.TagSuggestion
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SortMode
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.orchestration.SourceRunState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.decode.SvgDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    likedPostIds: Set<PostId> = emptySet(),
    onToggleLike: ((Post) -> Unit)? = null,
    onOpenViewer: (List<Post>, ViewerLaunchContext, Boolean) -> Unit,
    onApplySearch: () -> Unit,
    onRetrySearch: () -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    val queryHash = coordinator.appliedQueryHash
    val isNhentaiSourceMode = coordinator.draftQuery.mode == QueryMode.Source(SourceKey.NHENTAI)
    val animatedFilterActive = animatedOnly && !isNhentaiSourceMode
    LaunchedEffect(isNhentaiSourceMode) {
        if (isNhentaiSourceMode) {
            animatedOnly = false
        }
    }
    val visibleResults = remember(coordinator.results, animatedFilterActive) {
        if (animatedFilterActive) {
            coordinator.results.filter(::isAnimatedPost)
        } else {
            coordinator.results
        }
    }
    val displayTagSeedBySource = remember(
        coordinator.appliedQuery.mode,
        coordinator.appliedQuery.includeTags,
        visibleResults,
    ) {
        when (val mode = coordinator.appliedQuery.mode) {
            is QueryMode.Source -> mapOf(mode.source to coordinator.appliedQuery.includeTags)
            QueryMode.Unified -> {
                val sources = visibleResults
                    .asSequence()
                    .map { post -> post.id.source }
                    .toSet()
                sources.associateWith { coordinator.appliedQuery.includeTags }
            }
        }
    }
    val displayTagAffinityBySource = remember(visibleResults) {
        val documentsBySource = visibleResults
            .groupBy { post -> post.id.source }
            .mapValues { (_, posts) ->
                posts.map { post -> post.canonicalTags }
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

    LaunchedEffect(coordinator.draftQuery.mode, input, searchFieldFocused) {
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
                animatedFilterActive &&
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
        coordinator.hasPendingChanges

    fun commitTagInput() {
        val committed = coordinator.commitTagInput(input)
        if (committed) {
            input = ""
            focusManager.clearFocus()
        }
    }

    fun applyDraftAndResetScroll() {
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
                    { Text("tag or -tag") }
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
                    TextButton(
                        onClick = { commitTagInput() },
                        enabled = coordinator.canCommitTagInput(input),
                    ) {
                        Text("Add")
                    }
                }
            )

            AnimatedVisibility(
                visible = showSearchControls,
                enter = slideInVertically(initialOffsetY = { fullHeight -> fullHeight / 4 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { fullHeight -> fullHeight / 5 }) + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (autocompleteSuggestions.isNotEmpty()) {
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
                        includeTags = coordinator.draftQuery.includeTags,
                        excludeTags = coordinator.draftQuery.excludeTags,
                        onRemoveInclude = coordinator::removeIncludeTag,
                        onRemoveExclude = coordinator::removeExcludeTag,
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
                                    messageOverride = when {
                                        animatedFilterActive &&
                                            coordinator.results.isNotEmpty() &&
                                            (coordinator.loadingMore || coordinator.canLoadMore) -> {
                                            "No animated media yet. Retrying with more pages..."
                                        }

                                        animatedFilterActive && coordinator.results.isNotEmpty() -> {
                                            "No animated media found for the current results."
                                        }

                                        else -> null
                                    },
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
                                    onClick = {
                                        focusManager.clearFocus()
                                        val context = coordinator.buildViewerLaunchContext(
                                            startIndex = index,
                                            scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        )
                                        scope.launch { coordinator.setViewerLaunchContext(context) }
                                        onOpenViewer(visibleResults, context, animatedFilterActive)
                                    },
                                    onLongPress = {
                                        focusManager.clearFocus()
                                        selectedActionPost = post
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
            onDismissRequest = { selectedActionPost = null },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val formatted = formatPostTagsForClipboard(post)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("tags", formatted))
                            Toast.makeText(context, "Tags copied", Toast.LENGTH_SHORT).show()
                            selectedActionPost = null
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedActionPost = null },
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            coordinator = coordinator,
            animatedOnly = animatedOnly,
            onAnimatedOnlyChange = { animatedOnly = it },
            showAnimatedOnlyFilter = !isNhentaiSourceMode,
            nhentaiLanguageFilter = coordinator.selectedNhentaiLanguageFilter(),
            onNhentaiLanguageFilterChange = { filter -> coordinator.setNhentaiLanguageFilter(filter) },
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
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
    ) {
        val title = post.title?.takeIf { it.isNotBlank() } ?: post.id.sourcePostId
        val videoRef = remember(post.id.source, post.id.sourcePostId, post.media, post.full, post.preview) {
            resolveCardVideoRef(post)
        }
        var videoPlaybackFailed by remember(post.id.source, post.id.sourcePostId, videoRef?.url, videoRef?.localPath) {
            mutableStateOf(false)
        }
        val previewUrl = resolveCardPreviewUrl(post)
        val ratio = previewAspectRatio(post)
        val imageModel = remember(context, previewUrl, post.id.source) {
            previewUrl?.let { buildImageRequest(context, it, post.id.source) }
        }
        val mediaCount = postMediaCount(post)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        ) {
            val showUgoira = isPixivUgoiraPost(post) && pixivUgoiraClient != null
            if (showUgoira) {
                PixivUgoiraPlayer(
                    postId = post.id.sourcePostId,
                    client = requireNotNull(pixivUgoiraClient),
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                )
            } else if (videoRef != null && !videoPlaybackFailed) {
                SearchVideoPreview(
                    media = videoRef,
                    sourceKey = post.id.source,
                    previewModel = imageModel,
                    modifier = Modifier.fillMaxSize(),
                    onPlaybackError = { videoPlaybackFailed = true },
                )
            } else if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
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
            val firstTag = displayTag ?: post.canonicalTags.firstOrNull()
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
                            source = post.id.source,
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
            headers = searchRequestHeaders(sourceKey),
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

private fun postMediaCount(post: Post): Int {
    val explicitCount = post.media.count { !it.url.isNullOrBlank() || !it.localPath.isNullOrBlank() }
    return when {
        explicitCount > 0 -> explicitCount
        post.full != null -> 1
        else -> 1
    }
}

private fun resolveCardPreviewUrl(post: Post): String? {
    val full = post.full
    if (full != null && isGifMediaRef(full) && !full.url.isNullOrBlank()) {
        return full.url
    }
    val refs = buildList {
        add(post.preview)
        post.full?.let { add(it) }
        addAll(post.media)
    }
    return refs.firstOrNull { ref ->
        !ref.url.isNullOrBlank() && !isVideoMediaRef(ref)
    }?.url
}

private fun resolveCardVideoRef(post: Post): ImageRef? {
    val refs = buildList {
        addAll(post.media)
        post.full?.let { add(it) }
        add(post.preview)
    }
    return refs.firstOrNull { ref ->
        (!ref.url.isNullOrBlank() || !ref.localPath.isNullOrBlank()) && isVideoMediaRef(ref)
    }
}

private fun formatPostTagsForClipboard(post: Post): String {
    val canonicalPositives = post.canonicalTags.filterNot { it.startsWith("-") }
    val canonicalNegatives = post.canonicalTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val rawPositives = post.rawTags.filterNot { it.startsWith("-") }
    val rawNegatives = post.rawTags
        .filter { it.startsWith("-") }
        .map { it.removePrefix("-") }

    val positives = (canonicalPositives + rawPositives)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("-") }
        .distinct()
    val negatives = (canonicalNegatives + rawNegatives)
        .map { it.trim().removePrefix("-") }
        .filter { it.isNotBlank() }
        .distinct()

    val positiveLine = positives.joinToString(", ")
    val negativeLine = negatives.joinToString(", ") { "-$it" }
    return "$positiveLine\n\n$negativeLine"
}

private fun copyPostUrlToClipboard(context: Context, post: Post): Boolean {
    val pageUrl = post.pageUrl?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("post_url", pageUrl))
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    coordinator: SearchCoordinator,
    animatedOnly: Boolean,
    onAnimatedOnlyChange: (Boolean) -> Unit,
    showAnimatedOnlyFilter: Boolean,
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
            if (showAnimatedOnlyFilter) {
                Text("Media Types", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = animatedOnly,
                            onClick = { onAnimatedOnlyChange(!animatedOnly) },
                            label = { Text("Animated only") },
                        )
                    }
                }
            } else {
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
                                onSortChanged()
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
            val context = LocalContext.current
            val model = remember(context) {
                ImageRequest.Builder(context)
                    .data(Uri.parse("android.resource://${context.packageName}/${R.raw.gelbooru_logo}"))
                    .decoderFactory(SvgDecoder.Factory())
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = "Gelbooru",
                modifier = modifier.size(size),
                contentScale = ContentScale.Fit,
            )
        }

        SourceKey.NHENTAI -> {
            val context = LocalContext.current
            val model = remember(context) {
                ImageRequest.Builder(context)
                    .data(Uri.parse("android.resource://${context.packageName}/${R.raw.nhentai_logo}"))
                    .decoderFactory(SvgDecoder.Factory())
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = "NHentai",
                modifier = modifier.height(size),
                contentScale = ContentScale.Fit,
            )
        }

        else -> Text(source.name)
    }
}

@Composable
private fun SourceBadge(
    source: SourceKey,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            SourceChipLogo(
                source = source,
                size = 12.dp,
            )
        }
    }
}

@Composable
private fun TagRow(
    includeTags: List<String>,
    excludeTags: List<String>,
    onRemoveInclude: (String) -> Unit,
    onRemoveExclude: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(includeTags.size) { index ->
            val tag = includeTags[index]
            AssistChip(
                onClick = { onRemoveInclude(tag) },
                label = { Text(tag) }
            )
        }
        items(excludeTags.size) { index ->
            val tag = excludeTags[index]
            AssistChip(
                onClick = { onRemoveExclude(tag) },
                label = { Text("-$tag") }
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
            val text = when (status.state) {
                SourceRunState.EXCLUDED -> "${status.source.name} excluded"
                SourceRunState.FAILED -> {
                    val reason = status.failureReason?.name ?: "UNKNOWN"
                    "${status.source.name} failed ($reason)"
                }
                SourceRunState.SUCCESS -> "${status.source.name} OK"
            }
            AssistChip(onClick = {}, label = { Text(text) })
        }
    }
}

private fun buildSourceAuthErrorMessage(statuses: List<com.theoriacodex.domain.orchestration.SourceRunStatus>): String? {
    val authSources = statuses
        .filter { status ->
            status.state == SourceRunState.FAILED &&
                (status.failureReason == SourceFailureReason.AUTH_REQUIRED ||
                    status.failureReason == SourceFailureReason.AUTH_EXPIRED)
        }
        .map { it.source.name }
        .distinct()
    if (authSources.isEmpty()) return null

    val names = authSources.joinToString(", ")
    val verb = if (authSources.size == 1) "requires" else "require"
    return "$names $verb authentication. Connect the account in Settings > Source Accounts."
}

private fun buildSourceFailureMessage(statuses: List<com.theoriacodex.domain.orchestration.SourceRunStatus>): String? {
    val failures = statuses.filter { status ->
        status.state == SourceRunState.FAILED &&
            status.failureReason != SourceFailureReason.AUTH_REQUIRED &&
            status.failureReason != SourceFailureReason.AUTH_EXPIRED
    }
    if (failures.isEmpty()) return null

    val details = failures.take(3).map { status ->
        val reason = status.failureReason
            ?.name
            ?.replace('_', ' ')
            ?.lowercase()
            ?.replaceFirstChar { it.uppercase() }
        val rawMessage = status.errorMessage?.trim().orEmpty()
        when {
            rawMessage.isNotBlank() -> "${status.source.name}: $rawMessage"
            reason != null -> "${status.source.name}: $reason"
            else -> "${status.source.name}: Request failed"
        }
    }
    val suffix = if (failures.size > 3) "\n+${failures.size - 3} more source errors" else ""
    return details.joinToString(separator = "\n") + suffix
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
                "No results yet. Add tags or use Explore quick queries."
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

private fun previewAspectRatio(post: Post): Float {
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
    val builder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .allowHardware(false)
    searchRequestHeaders(sourceKey).forEach { (name, value) ->
        builder.addHeader(name, value)
    }
    return builder.build()
}

private fun searchRequestHeaders(sourceKey: SourceKey): Map<String, String> {
    return when (sourceKey) {
        SourceKey.PIXIV -> mapOf(
            "Referer" to "https://www.pixiv.net/",
            "User-Agent" to "Mozilla/5.0",
        )

        SourceKey.GELBOORU -> mapOf(
            "Referer" to "https://gelbooru.com/",
            "User-Agent" to "Mozilla/5.0",
        )

        SourceKey.AIBOORU -> mapOf(
            "Referer" to "https://aibooru.online/",
            "User-Agent" to "Mozilla/5.0",
        )

        SourceKey.NHENTAI -> mapOf(
            "Referer" to "https://nhentai.net/",
            "User-Agent" to "Mozilla/5.0",
        )
    }
}

private const val PAGINATION_PREFETCH_RATIO = 0.8f
private const val ANIMATED_PREFETCH_MIN_VISIBLE = 12
