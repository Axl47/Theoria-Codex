package com.theoriacodex.app.creator

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.ANIMATED_DURATION_MAX_BUCKET
import com.theoriacodex.app.media.ANIMATED_DURATION_MIN_BUCKET
import com.theoriacodex.app.media.AnimatedDurationRange
import com.theoriacodex.app.search.AnimatedDurationRangeControl
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.search.SearchVisibilityFilters
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.search.animatedDurationResolutionCandidates
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.media.copyPostTagsToClipboard
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.media.probeRemoteVideoDurationMs
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchTerm
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorProfileScreen(
    coordinator: CreatorProfileCoordinator,
    likedPostIds: Set<PostId>,
    savedPostIds: Set<PostId>,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    resolveUnknownAnimatedDurations: Boolean = false,
    onToggleLike: (Post) -> Unit,
    onOpenViewer: (List<Post>, ViewerLaunchContext, SearchVisibilityFilters) -> Unit,
    onRequestSaveToCodex: (Post) -> Unit,
    onSaveToDevice: (Post) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAddIncludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onAddExcludeTerm: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val creator = coordinator.activeCreator
    val context = LocalContext.current
    var selectedActionPost by remember { mutableStateOf<Post?>(null) }
    var showProfileShareMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var hideLiked by rememberSaveable { mutableStateOf(false) }
    var hideSaved by rememberSaveable { mutableStateOf(false) }
    var durationMinBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MIN_BUCKET) }
    var durationMaxBucket by rememberSaveable { mutableStateOf(ANIMATED_DURATION_MAX_BUCKET) }
    val scope = rememberCoroutineScope()
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
        coordinator.results,
        visibilityFilters,
        likedPostIds,
        savedPostIds,
        unknownAnimatedDurationPolicy,
    ) {
        filterSearchResults(
            results = coordinator.results,
            filters = visibilityFilters,
            likedPostIds = likedPostIds,
            savedPostIds = savedPostIds,
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
        )
    }
    val durationResolutionRequests = remember(coordinator.activeQueryHash) { mutableSetOf<PostId>() }
    LaunchedEffect(coordinator.results, visibilityFilters, unknownAnimatedDurationPolicy, coordinator.activeQueryHash) {
        if (unknownAnimatedDurationPolicy != UnknownAnimatedDurationPolicy.RESOLVE_IN_BACKGROUND) return@LaunchedEffect
        val candidates = animatedDurationResolutionCandidates(
            results = coordinator.results,
            filters = visibilityFilters,
        ).filter { post -> durationResolutionRequests.add(post.id) }
            .take(CREATOR_PROFILE_DURATION_RESOLVE_BATCH_SIZE)
        candidates.forEach { post ->
            val resolved = runCatchingPreservingCancellation {
                coordinator.resolvePostForCreator(post.id)
            }.getOrNull()
            val candidate = resolved ?: post
            if (animatedDurationMs(candidate) == null) {
                val probedDurationMs = probeRemoteVideoDurationMs(candidate)
                if (probedDurationMs != null) {
                    coordinator.rememberResolvedPost(candidate.copy(durationMs = probedDurationMs))
                }
            }
        }
    }

    LaunchedEffect(
        visibleResults.size,
        coordinator.results.size,
        coordinator.loading,
        coordinator.loadingMore,
        coordinator.canLoadMore,
        animatedOnly,
        animatedDurationFilterActive,
    ) {
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to coordinator.loadingMore
        }.collect { (lastVisibleIndex, loadingMoreState) ->
            if (loadingMoreState) return@collect
            if (coordinator.loading || coordinator.loadingMore || !coordinator.canLoadMore) return@collect

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
                    coordinator.results.isNotEmpty()

            if (shouldTriggerByThreshold || shouldTriggerForAnimatedBuffer) {
                coordinator.loadNextPage()
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
            FloatingActionButton(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = { showFilterSheet = true },
            ) {
                Icon(Icons.Default.FilterList, contentDescription = "Filter creator uploads")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = creator.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = creator.source.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                creator.profileUrl?.takeIf { it.isNotBlank() }?.let { profileUrl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                                    },
                                    onClick = {
                                        showProfileShareMenu = false
                                        copyCreatorProfile(context = context, profileUrl = profileUrl)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            when {
                coordinator.loading && visibleResults.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                coordinator.errorMessage != null && visibleResults.isEmpty() -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(coordinator.errorMessage.orEmpty())
                            TextButton(
                                onClick = {
                                    scope.launch { coordinator.refresh() }
                                },
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }

                visibleResults.isEmpty() -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (
                                coordinator.results.isNotEmpty() &&
                                !visibilityFilters.animatedDurationRange.isFullRange
                            ) {
                                "No animated media found in the selected duration range."
                            } else {
                                "No uploads found for this creator."
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalItemSpacing = 6.dp,
                    ) {
                        itemsIndexed(
                            items = visibleResults,
                            key = { _, post -> "${post.id.source.name}:${post.id.sourcePostId}" },
                        ) { index, post ->
                            SearchResultCard(
                                post = post,
                                pixivUgoiraClient = pixivUgoiraClient,
                                liked = post.id in likedPostIds,
                                onToggleLike = { onToggleLike(post) },
                                onClick = {
                                    onOpenViewer(
                                        visibleResults,
                                        coordinator.buildViewerLaunchContext(
                                            startIndex = index,
                                            scrollOffsetHint = gridState.firstVisibleItemScrollOffset,
                                        ),
                                        visibilityFilters,
                                    )
                                },
                                onLongPress = { selectedActionPost = post },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
    }

    if (selectedActionPost != null) {
        val post = requireNotNull(selectedActionPost)
        ModalBottomSheet(
            onDismissRequest = { selectedActionPost = null },
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            copyPostTagsToClipboard(context, post)
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
                            val message = if (copied) "Post URL copied" else "No post URL available"
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
                )
                HorizontalDivider()
                PostTagActionSection(
                    post = post,
                    onAddIncludeTerm = { term -> onAddIncludeTerm(post, term) },
                    onAddExcludeTerm = { term -> onAddExcludeTerm(post, term) },
                    onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                    onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
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
}

private fun shareCreatorProfile(context: Context, profileUrl: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, profileUrl)
    }
    context.startActivity(Intent.createChooser(intent, "Share creator profile"))
}

private fun copyCreatorProfile(context: Context, profileUrl: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Creator profile", profileUrl))
    Toast.makeText(context, "Creator link copied", Toast.LENGTH_SHORT).show()
}

private const val CREATOR_PROFILE_PREFETCH_RATIO = 0.7f
private const val CREATOR_PROFILE_ANIMATED_PREFETCH_MIN_VISIBLE = 6
private const val CREATOR_PROFILE_DURATION_RESOLVE_BATCH_SIZE = 8
