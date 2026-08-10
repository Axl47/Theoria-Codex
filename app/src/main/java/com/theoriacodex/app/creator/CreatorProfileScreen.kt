package com.theoriacodex.app.creator

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.media.copyTextToClipboard
import com.theoriacodex.app.media.showClipboardCopyConfirmation
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFilterFab
import com.theoriacodex.app.ui.components.FeedFilterSheet
import com.theoriacodex.app.ui.components.FeedLoadingState
import com.theoriacodex.app.ui.components.PostActionSheet
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import com.theoriacodex.app.ui.components.TwoColumnPostStaggeredGrid
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.creator.state.CreatorUiState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorProfileScreen(
    state: CreatorUiState,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    onToggleLike: (Post) -> Unit,
    onAction: (CreatorAction) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onPostUrlCopied: (Post) -> Unit = {},
    onOpenUrl: (String) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
) {
    val creator = state.creator
    val context = LocalContext.current
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showProfileShareMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var hideLiked by rememberSaveable { mutableStateOf(false) }
    var hideSaved by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableIntStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    val gridState = rememberLazyStaggeredGridState()
    val animatedDurationRange = remember(durationMinBucket, durationMaxBucket) {
        AnimatedDurationRange(
            minBucket = durationMinBucket,
            maxBucket = durationMaxBucket,
        )
    }
    val animatedDurationFilterActive = !animatedDurationRange.isFullRange
    val visibilityFilters = remember(animatedOnly, hideLiked, hideSaved, animatedDurationRange) {
        SearchVisibilityFilters(
            animatedOnly = animatedOnly,
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
    val visibleResults = remember(
        state.results,
        visibilityFilters,
        likedPostIds,
        savedPostIds,
        unknownAnimatedDurationPolicy,
    ) {
        filterSearchResults(
            results = state.results,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            savedPostIds = savedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        )
    }
    LaunchedEffect(state.results, animatedDurationFilterActive, unknownAnimatedDurationPolicy, state.queryHash) {
        val queryHash = state.queryHash
        if (queryHash != null && animatedDurationFilterActive &&
            unknownAnimatedDurationPolicy == UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND
        ) {
            onAction(CreatorAction.RequestAnimatedDurationEnrichment(queryHash))
        }
    }

    LaunchedEffect(
        visibleResults.size,
        state.results.size,
        state.isRefreshing,
        state.isPaging,
        state.canLoadMore,
        animatedOnly,
        animatedDurationFilterActive,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to state.isPaging
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (state.isRefreshing || state.isPaging || !state.canLoadMore) return@collect

            val totalVisible = visibleResults.size
            val shouldTriggerByThreshold = if (totalVisible > 0 && lastVisibleIndex >= 0) {
                val triggerIndex = ((totalVisible - 1) * CREATOR_PROFILE_PREFETCH_RATIO)
                    .toInt()
                    .coerceAtLeast(0)
                lastVisibleIndex >= triggerIndex
            } else {
                false
            }

            val shouldTriggerForAnimatedBuffer =
                (animatedOnly || animatedDurationFilterActive) &&
                    totalVisible < CREATOR_PROFILE_ANIMATED_PREFETCH_MIN_VISIBLE &&
                    state.results.isNotEmpty()

            if (shouldTriggerByThreshold || shouldTriggerForAnimatedBuffer) {
                onAction(CreatorAction.LoadNextPage)
            }
        }
    }

    if (creator == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No creator selected")
        }
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FeedFilterFab(
                modifier = Modifier.padding(bottom = 8.dp),
                active = animatedOnly || hideLiked || hideSaved || animatedDurationFilterActive,
                contentDescription = "Filter creator uploads",
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
            SecondaryScreenAppBar(
                title = creator.displayName,
                subtitle = creator.source.displayName(),
                onBack = { onAction(CreatorAction.Back) },
            ) {
                creator.profileUrl?.takeIf { it.isNotBlank() }?.let { profileUrl ->
                    IconButton(onClick = { onOpenUrl(profileUrl) }) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = "Open creator profile in browser",
                        )
                    }
                    Box {
                        IconButton(onClick = { showProfileShareMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share creator profile",
                            )
                        }
                        DropdownMenu(
                            expanded = showProfileShareMenu,
                            onDismissRequest = { showProfileShareMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showProfileShareMenu = false
                                    shareCreatorProfile(context = context, profileUrl = profileUrl)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy link") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    showProfileShareMenu = false
                                    copyCreatorProfile(context = context, profileUrl = profileUrl)
                                },
                            )
                        }
                    }
                }
            }

            when {
                state.isRefreshing && visibleResults.isEmpty() -> {
                    FeedLoadingState()
                }

                state.errorMessage != null && visibleResults.isEmpty() -> {
                    FeedErrorTile(
                        message = state.errorMessage.orEmpty(),
                        onRetry = { onAction(CreatorAction.Refresh) },
                    )
                }

                visibleResults.isEmpty() -> {
                    FeedEmptyTile(
                        message = if (
                                state.results.isNotEmpty() &&
                                !visibilityFilters.animatedDurationRange.isFullRange
                            ) {
                                "No animated media found in the selected duration range."
                            } else {
                                "No uploads found for this creator."
                            },
                    )
                }

                else -> {
                    TwoColumnPostStaggeredGrid(
                        posts = visibleResults,
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                    ) { index, post ->
                        SearchResultCard(
                            post = post,
                            pixivUgoiraClient = pixivUgoiraClient,
                            liked = post.id in likedPostIds,
                            onToggleLike = { onToggleLike(post) },
                            onClick = {
                                onAction(
                                    CreatorAction.OpenResult(
                                        index = index,
                                        scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        visibleResults = visibleResults,
                                        visibilityFilters = visibilityFilters,
                                    )
                                )
                            },
                            onLongPress = { selectedActionPost = post },
                        )
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FeedFilterSheet(onDismiss = { showFilterSheet = false }) {
                Text("Visibility", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = animatedOnly,
                        onClick = { animatedOnly = !animatedOnly },
                        label = { Text("Animated only") },
                    )
                    FilterChip(
                        selected = hideLiked,
                        onClick = { hideLiked = !hideLiked },
                        label = { Text("Hide liked") },
                    )
                    FilterChip(
                        selected = hideSaved,
                        onClick = { hideSaved = !hideSaved },
                        label = { Text("Hide saved") },
                    )
                }
                AnimatedDurationRangeControl(
                    range = animatedDurationRange,
                    onRangeChange = { range ->
                        durationMinBucket = range.normalizedMinBucket
                        durationMaxBucket = range.normalizedMaxBucket
                    },
                )
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showFilterSheet = false },
                ) {
                    Text("Done")
                }
        }
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        PostActionSheet(
            post = post,
            onDismiss = { selectedActionPost = null },
            onSaveToDevice = { onSaveToDevice(post) },
            onSaveToCodex = { onRequestSaveToCodex(post) },
            onPostUrlCopied = onPostUrlCopied,
            tagContent = {
                PostTagActionSection(
                    post = post,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                )
            },
        )
    }
}

private fun shareCreatorProfile(context: Context, profileUrl: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, profileUrl)
    }
    context.startActivity(Intent.createChooser(intent, "Share creator profile"))
}

private fun copyCreatorProfile(context: Context, profileUrl: String) {
    if (copyTextToClipboard(context, "Creator profile", profileUrl)) {
        showClipboardCopyConfirmation(context, "Creator link copied")
    } else {
        Toast.makeText(context, "Could not copy creator link", Toast.LENGTH_SHORT).show()
    }
}

private const val CREATOR_PROFILE_PREFETCH_RATIO = 0.7f
private const val CREATOR_PROFILE_ANIMATED_PREFETCH_MIN_VISIBLE = 6
