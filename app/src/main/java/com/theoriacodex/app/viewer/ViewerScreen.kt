package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.creator.CreatorProfileActionButton
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.post.displayTitleOrNull
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.codex.PreloadCodexSaveRelevance
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ViewerScreen(
    uiState: ViewerUiState,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    saveToCodexQuery: Query? = null,
    pixivUgoiraClient: PixivUgoiraClient? = null,
    tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    videoQuality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.AUTO,
    invertMultiImageScrollDirection: Boolean = false,
    onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit = {},
    likedPostIds: Set<PostId> = emptySet(),
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)? = null,
    onVisiblePostChanged: ((Post, Int) -> Unit)? = null,
    onVisibleMediaChanged: ((Post, Int) -> Unit)? = null,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit = { _, _ -> },
    onOpenInBrowser: (Post) -> Unit,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)? = null,
    playbackDiagnosticsEnabled: Boolean = com.theoriacodex.app.BuildConfig.BENCHMARK_FIXTURES_ENABLED,
) {
    val returnProgress = rememberViewerReturnProgress()
    val returning = LocalViewerReturnTransition.current?.request != null
    val opening = !returning && returnProgress > 0f
    val activity = LocalContext.current.viewerActivity()
    val inPictureInPicture = activity?.viewerPictureInPictureActive == true
    var quality by remember(videoQuality) { mutableStateOf(videoQuality) }
    var qualityHeight by remember(uiState.currentMedia?.key) { mutableStateOf<Int?>(null) }
    val posts = remember(uiState.pages) { uiState.pages.map { page -> page.post } }
    val videoPositions = remember(uiState.session) { ViewerVideoPositions() }
    if (posts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("No posts to view", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val imageSession = remember { ViewerImageSession() }
    val initialIndex = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
    val postPagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { posts.size },
    )
    var viewerTransform by remember(uiState.session?.value, uiState.currentMedia?.key) {
        mutableStateOf(ViewerTransformState())
    }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var timelineInteractionActive by remember { mutableStateOf(false) }
    var lastViewerPaginationRequestSize by remember(posts.size) { mutableIntStateOf(-1) }
    var pendingDismiss by remember { mutableStateOf(false) }
    var swipeInProgress by remember { mutableStateOf(false) }
    var dragInProgress by remember { mutableStateOf(false) }
    val pendingMediaJumpByPost = remember { mutableStateMapOf<Int, Int>() }
    val currentPostIndex = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
    SideEffect { videoPositions.retainAround(posts.map { it.id }, currentPostIndex) }
    val selectedPost = posts[currentPostIndex]
    val selectedPostLiked = selectedPost.id in likedPostIds
    val selectedPostMedia = remember(selectedPost) { postMediaItems(selectedPost) }
    val selectedMediaIndex = uiState.pages
        .getOrNull(currentPostIndex)
        ?.selectedMediaIndex
        ?.coerceIn(0, selectedPostMedia.lastIndex)
        ?: 0
    val selectedCurrentMedia = selectedPostMedia.getOrNull(selectedMediaIndex)
    val levelControls = rememberViewerLevelControls(
        enabled = !inPictureInPicture && selectedCurrentMedia?.let(::isVideoMediaRef) == true && !uiState.overview.visible,
    )
    val currentIsSeekableMedia = selectedCurrentMedia?.let { media ->
        isVideoMediaRef(media) ||
            isGifMediaRef(media) ||
            (isPixivUgoiraMedia(selectedPost, media) && pixivUgoiraClient != null)
    } == true
    val canDownloadCurrentMedia = selectedCurrentMedia?.let { media ->
        (isPixivUgoiraMedia(selectedPost, media) && pixivUgoiraClient != null) || !media.url.isNullOrBlank()
    } == true
    val selectedMediaOverviewItems = remember(selectedPost) { viewerMediaOverviewItems(selectedPost) }
    val mediaOverviewAvailable = viewerMediaOverviewAvailable(selectedMediaOverviewItems)
    val touchExplorationEnabled = rememberViewerTouchExploration()
    val chromeVisible = (uiState.controls.chromeVisible || touchExplorationEnabled) && !inPictureInPicture && !returning && !opening
    val showInfoSheet = uiState.controls.metadataVisible && !inPictureInPicture
    val mediaPlaybackEnabled = uiState.controls.playback.playing
    val playbackRestartRequest = uiState.controls.playback.restartRequest
    val playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate)
    val actionsMenuExpanded = uiState.controls.actionsMenuVisible
    val playbackSettingsExpanded = uiState.controls.playbackSettingsVisible
    val mediaOverviewVisible = uiState.overview.visible && !inPictureInPicture

    fun setInfoSheetVisible(visible: Boolean) {
        onAction(if (visible) ViewerAction.ShowMetadata else ViewerAction.HideMetadata)
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        onAction(if (enabled) ViewerAction.Play else ViewerAction.Pause)
    }

    fun setActionsMenuExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowActionsMenu else ViewerAction.HideActionsMenu)
    }

    fun setPlaybackSettingsExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowPlaybackSettings else ViewerAction.HidePlaybackSettings)
    }

    fun setOverviewVisible(visible: Boolean) {
        if (visible != uiState.overview.visible) {
            onAction(ViewerAction.ToggleOverview)
        }
    }

    LaunchedEffect(uiState.session) {
        pendingDismiss = false
    }

    LaunchedEffect(currentIsSeekableMedia, chromeVisible) {
        if (!currentIsSeekableMedia) {
            setPlaybackSettingsExpanded(false)
        }
        if (!chromeVisible) {
            setActionsMenuExpanded(false)
            setPlaybackSettingsExpanded(false)
        }
    }

    LaunchedEffect(mediaOverviewAvailable) {
        if (!mediaOverviewAvailable) {
            setOverviewVisible(false)
        }
    }

    LaunchedEffect(
        selectedPost.id.source,
        selectedPost.id.sourcePostId,
        selectedPost.full?.url,
        selectedPost.full?.localPath,
        uiState.currentPage?.resolution?.status,
    ) {
        val status = uiState.currentPage?.resolution?.status
        if (
            status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.IDLE ||
            status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED &&
            uiState.currentPage?.resolution?.recoverable == true
        ) {
            onAction(ViewerAction.RequestCurrentPageResolution)
        }
    }

    LaunchedEffect(pendingDismiss, swipeInProgress, opening) {
        if (!pendingDismiss || swipeInProgress || opening) return@LaunchedEffect
        withFrameNanos { }
        onAction(ViewerAction.Dismiss)
    }

    fun requestDismissViewer() {
        if (pendingDismiss) return
        setPlaybackEnabled(false)
        setInfoSheetVisible(false)
        setActionsMenuExpanded(false)
        setPlaybackSettingsExpanded(false)
        setOverviewVisible(false)
        pendingDismiss = true
    }

    fun markInteraction() {
        interactionSerial += 1
        if (!chromeVisible && !opening && !returning) {
            onAction(ViewerAction.ToggleChrome)
        }
    }

    BackHandler {
        if (pendingDismiss || returning) return@BackHandler
        if (mediaOverviewVisible) {
            setOverviewVisible(false)
            markInteraction()
        } else {
            requestDismissViewer()
        }
    }

    LaunchedEffect(posts, currentPostIndex, selectedMediaIndex) {
        val keys = planAdjacentViewerPrefetch(
            pages = uiState.pages,
            current = uiState.currentMedia?.key,
        )
        onAction(ViewerAction.QueuePrefetch(keys))
    }

    LaunchedEffect(posts.size, currentPostIndex, canLoadMoreFromSource, loadingMoreFromSource) {
        if (loadingMoreFromSource || !canLoadMoreFromSource) return@LaunchedEffect
        val triggerIndex = ((posts.lastIndex.coerceAtLeast(0)) * VIEWER_PAGINATION_PREFETCH_RATIO)
            .toInt()
            .coerceAtLeast(0)
        if (currentPostIndex >= triggerIndex && lastViewerPaginationRequestSize != posts.size) {
            lastViewerPaginationRequestSize = posts.size
            onAction(ViewerAction.LoadMore)
        }
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        timelineInteractionActive = isActive
        if (isActive) {
            markInteraction()
        }
    }

    LaunchedEffect(uiState.currentPageIndex, posts.size) {
        val targetPage = uiState.currentPageIndex.coerceIn(0, posts.lastIndex)
        if (postPagerState.currentPage != targetPage) {
            postPagerState.scrollToPage(targetPage)
        }
        setOverviewVisible(false)
        markInteraction()
    }

    LaunchedEffect(selectedPost.id, currentPostIndex, uiState.currentPage?.pendingMediaIndex) {
        if (uiState.currentPage?.pendingMediaIndex == null) {
            onVisiblePostChanged?.invoke(selectedPost, selectedMediaIndex + 1)
        }
    }

    ViewerChromeAutoHideEffect(
        chromeVisible = chromeVisible,
        interactionSerial = interactionSerial,
        interactionBlocked = timelineInteractionActive || mediaOverviewVisible || showInfoSheet ||
            actionsMenuExpanded || playbackSettingsExpanded,
        touchExplorationEnabled = touchExplorationEnabled,
        onHide = { onAction(ViewerAction.ToggleChrome) },
    )

    LaunchedEffect(chromeVisible, currentIsSeekableMedia) {
        if (!chromeVisible || !currentIsSeekableMedia) {
            timelineInteractionActive = false
        }
    }

    fun navigateToPost(index: Int) {
        if (index == currentPostIndex || swipeInProgress || pendingDismiss || returning || opening) return
        markInteraction()
        swipeInProgress = true
        scope.launch {
            try {
                postPagerState.animateScrollToPage(index, animationSpec = tween(300))
                onAction(ViewerAction.SelectPage(index))
            } finally {
                swipeInProgress = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - returnProgress))
    ) {
        if (mediaOverviewVisible && mediaOverviewAvailable) {
            ViewerMediaOverviewGrid(
                post = selectedPost,
                items = selectedMediaOverviewItems,
                selectedMediaIndex = selectedMediaIndex,
                onMediaSelected = { mediaIndex ->
                    onAction(ViewerAction.SelectOverviewMedia(mediaIndex))
                    pendingMediaJumpByPost[currentPostIndex] = mediaIndex
                    setOverviewVisible(false)
                    markInteraction()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            HorizontalPager(
                state = postPagerState,
                userScrollEnabled = false,
                reverseLayout = viewerMediaPagerReverseLayout(
                    uiState.currentPage?.swipeMediaCount() ?: 1, invertMultiImageScrollDirection,
                ),
                modifier = Modifier.fillMaxSize().padding(bottom = if (chromeVisible) 56.dp else 0.dp),
            ) { postPage ->
            val post = posts[postPage]
            val pageState = uiState.pages[postPage]
            val terminalResolutionMessage = uiState.pages
                .getOrNull(postPage)
                ?.resolution
                ?.takeIf { resolution ->
                    resolution.status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED &&
                        !resolution.recoverable
                }
                ?.message
            val postMedia = remember(post) { postMediaItems(post) }
            val mediaPagerReverseLayout = viewerMediaPagerReverseLayout(
                mediaCount = pageState.swipeMediaCount(),
                invertMultiImageScrollDirection = invertMultiImageScrollDirection,
            )
            val initialMediaPage = uiState.pages
                .getOrNull(postPage)
                ?.selectedMediaIndex
                ?.coerceIn(0, postMedia.lastIndex)
                ?: 0
            val mediaPagerState = rememberPagerState(
                initialPage = initialMediaPage,
                pageCount = { postMedia.size },
            )
            val pendingMediaJump = pendingMediaJumpByPost[postPage]

            LaunchedEffect(initialMediaPage, postMedia.size) {
                if (mediaPagerState.currentPage != initialMediaPage) {
                    mediaPagerState.scrollToPage(initialMediaPage)
                }
            }

            LaunchedEffect(pendingMediaJump, postMedia.size, pageState.pendingMediaIndex) {
                if (pendingMediaJump == null || pageState.pendingMediaIndex != null) return@LaunchedEffect
                val targetPage = pendingMediaJump.coerceIn(0, postMedia.lastIndex)
                mediaPagerState.scrollToPage(targetPage)
                if (postPage == currentPostIndex && selectedMediaIndex != targetPage) {
                    onAction(ViewerAction.SelectMedia(targetPage))
                }
                pendingMediaJumpByPost.remove(postPage)
            }

            LaunchedEffect(mediaPagerState.settledPage, currentPostIndex, pageState.pendingMediaIndex, dragInProgress, swipeInProgress) {
                if (dragInProgress || swipeInProgress || pageState.pendingMediaIndex != null) return@LaunchedEffect
                if (postPage == currentPostIndex && mediaPagerState.settledPage != selectedMediaIndex) {
                    onAction(ViewerAction.SelectMedia(mediaPagerState.settledPage))
                }
                if (postPage == currentPostIndex) {
                    onVisibleMediaChanged?.invoke(post, mediaPagerState.settledPage + 1)
                }
                if (postPage == postPagerState.currentPage) {
                    markInteraction()
                }
            }

            fun swipeTarget(direction: ViewerHorizontalSwipeDirection): ViewerHorizontalSwipeTarget? {
                val targetPostIndex = when (direction) {
                    ViewerHorizontalSwipeDirection.Previous -> postPage - 1
                    ViewerHorizontalSwipeDirection.Next -> postPage + 1
                }
                val targetPostMediaCount = posts.getOrNull(targetPostIndex)
                    ?.let { postMediaItems(it).size }
                    ?: 0
                return viewerHorizontalSwipeTarget(
                    currentPostIndex = postPage,
                    currentMediaIndex = selectedMediaIndex,
                    currentMediaCount = postMedia.size,
                    postCount = posts.size,
                    targetPostMediaCount = targetPostMediaCount,
                    direction = direction,
                    galleryMediaPending = pageState.hasPendingGalleryMedia(),
                    targetMediaIndex = uiState.pages.getOrNull(targetPostIndex)?.let {
                        it.pendingMediaIndex ?: it.selectedMediaIndex
                    } ?: 0,
                )
            }

            fun cancelSwipe() {
                if (!dragInProgress || swipeInProgress) return
                swipeInProgress = true
                dragInProgress = false
                scope.launch {
                    try {
                        postPagerState.animateScrollToPage(currentPostIndex, animationSpec = tween(180))
                        mediaPagerState.animateScrollToPage(selectedMediaIndex, animationSpec = tween(180))
                    } finally { swipeInProgress = false }
                }
            }

            val navigateFromHorizontalSwipe: (ViewerHorizontalSwipeDirection) -> Unit = navigate@{ direction ->
                if (viewerTransform.isZoomed || swipeInProgress || pendingDismiss || returning || opening) return@navigate
                val target = swipeTarget(direction) ?: run { cancelSwipe(); return@navigate }
                markInteraction()
                swipeInProgress = true
                dragInProgress = false
                scope.launch {
                    try {
                        if (target.postIndex == postPage) {
                            mediaPagerState.animateScrollToPage(target.mediaIndex, animationSpec = tween(300))
                        } else {
                            pendingMediaJumpByPost[target.postIndex] = target.mediaIndex
                            postPagerState.animateScrollToPage(target.postIndex, animationSpec = tween(300))
                            // Commit after motion so state synchronization cannot snap the pager mid-swipe.
                            onAction(ViewerAction.SelectPage(target.postIndex))
                            onAction(ViewerAction.SelectMedia(target.mediaIndex))
                        }
                    } finally {
                        swipeInProgress = false
                    }
                }
            }

            HorizontalPager(
                state = mediaPagerState,
                userScrollEnabled = false,
                reverseLayout = mediaPagerReverseLayout,
                modifier = Modifier.fillMaxSize(),
                contentPadding = if (isLandscape) {
                    PaddingValues(0.dp)
                } else {
                    PaddingValues(top = 40.dp)
                },
                pageSpacing = if (isLandscape) 0.dp else 8.dp,
            ) { mediaPage ->
                val media = postMedia[mediaPage]
                val loadGeneration = uiState.pages
                    .getOrNull(postPage)
                    ?.media
                    ?.getOrNull(mediaPage)
                    ?.loadGeneration
                    ?: 0L
                val key = ViewerMediaKey(post.id, mediaPage)
                val active = postPage == currentPostIndex && mediaPage == selectedMediaIndex
                ViewerMediaPage(
                    post = post,
                    media = media,
                    returnProgress = if (active) returnProgress else 0f,
                    mediaIndex = mediaPage,
                    mediaCount = pageState.swipeMediaCount(),
                    controls = ViewerRendererControls(
                        isActive = active,
                        isPlaying = mediaPlaybackEnabled,
                        showControls = chromeVisible && active,
                        showPassiveProgress = !chromeVisible && active && !inPictureInPicture,
                        loopPlayback = uiState.controls.playbackMode == com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP,
                        playbackRate = playbackRate.speed,
                        restartRequest = playbackRestartRequest,
                        loadGeneration = loadGeneration,
                    ),
                    events = viewerRendererEvents(
                        session = uiState.session, key = key, generation = loadGeneration,
                        onAction = onAction,
                        onTimelineInteractionActiveChanged = ::onTimelineInteractionChanged,
                        onDurationKnown = { duration ->
                            if (isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)) onAuthoritativeDurationKnown(post, duration)
                        },
                    ),
                    transform = viewerTransform,
                    onTransformChanged = { viewerTransform = it },
                    layout = ViewerMediaLayout(isLandscape, inPictureInPicture, touchExplorationEnabled, invertMultiImageScrollDirection),
                    videoOptions = ViewerVideoOptions(quality, qualityHeight, terminalResolutionMessage, playbackDiagnosticsEnabled),
                    videoPositions = videoPositions,
                    levelControls = levelControls,
                    mediaError = uiState.mediaError?.takeIf { active && it.mediaKey == key },
                    pixivUgoiraClient = pixivUgoiraClient,
                    onAction = onAction,
                    onInteraction = ::markInteraction,
                    onToggleChrome = { onAction(ViewerAction.ToggleChrome); interactionSerial += 1 },
                    imageSession = imageSession,
                    onNavigate = navigateFromHorizontalSwipe,
                    onSwipeProgress = progress@{ displacement ->
                        if (swipeInProgress || pendingDismiss || returning || opening || viewerTransform.isZoomed) return@progress
                        dragInProgress = true
                        val direction = if (displacement < 0f) ViewerHorizontalSwipeDirection.Next else ViewerHorizontalSwipeDirection.Previous
                        val target = swipeTarget(direction)
                        val mediaTarget = target?.takeIf { it.postIndex == postPage }
                        val postTarget = target?.takeIf { it.postIndex != postPage }
                        mediaPagerState.dragViewerPage(selectedMediaIndex, mediaTarget?.mediaIndex, displacement)
                        postPagerState.dragViewerPage(currentPostIndex, postTarget?.postIndex, displacement)
                    },
                    onSwipeCancelled = ::cancelSwipe,
                    onRequestMediaRecovery = onRequestMediaRecovery,
                )
            }
        }
        }

        if (chromeVisible && !mediaOverviewVisible) {
            ViewerPostStrip(posts, postPagerState.currentPage, ::navigateToPost, Modifier.align(Alignment.BottomCenter))
        }
        if (chromeVisible) {
            ViewerChrome(
                onPictureInPicture = viewerPictureInPictureAction(activity, selectedPost, selectedCurrentMedia, uiState.mediaError != null),
                playbackMode = uiState.controls.playbackMode,
                onPlaybackModeSelected = { onAction(ViewerAction.SetPlaybackMode(it)) },
                quality = quality,
                qualityHeight = qualityHeight,
                videoVariants = uiState.currentMedia?.ref?.videoVariants.orEmpty(),
                onQualitySelected = { selected, height -> quality = selected; qualityHeight = height },
                modifier = Modifier.align(Alignment.TopCenter),
                source = selectedPost.id.source.displayName(),
                indexLabel = if (selectedPostMedia.size > 1) {
                    "${selectedMediaIndex + 1} / ${selectedPostMedia.size}"
                } else null,
                onBack = ::requestDismissViewer,
                liked = selectedPostLiked,
                onToggleLike = {
                    onAction(ViewerAction.ToggleLike)
                    markInteraction()
                },
                actionsMenuExpanded = actionsMenuExpanded,
                onActionsMenuExpandedChange = { expanded ->
                    setActionsMenuExpanded(expanded)
                    if (expanded) {
                        setPlaybackSettingsExpanded(false)
                        markInteraction()
                    }
                },
                playbackSettingsExpanded = playbackSettingsExpanded,
                onPlaybackSettingsExpandedChange = { expanded ->
                    setPlaybackSettingsExpanded(expanded && uiState.controls.playback.available)
                    if (expanded && uiState.controls.playback.available) {
                        setActionsMenuExpanded(false)
                        markInteraction()
                    }
                },
                playbackSettingsEnabled = uiState.controls.playback.available,
                playbackRateEnabled = currentIsSeekableMedia,
                playbackRate = playbackRate,
                onPlaybackRateSelected = { selectedRate ->
                    onAction(ViewerAction.SetPlaybackRate(selectedRate.speed))
                    markInteraction()
                },
                mediaOverviewAvailable = mediaOverviewAvailable,
                mediaOverviewVisible = mediaOverviewVisible,
                onToggleMediaOverview = {
                    if (mediaOverviewAvailable) {
                        setOverviewVisible(!mediaOverviewVisible)
                        setActionsMenuExpanded(false)
                        setPlaybackSettingsExpanded(false)
                        markInteraction()
                    }
                },
                invertScrollOptionVisible = selectedPostMedia.size > 1,
                invertMultiImageScrollDirection = invertMultiImageScrollDirection,
                onInvertMultiImageScrollDirectionChange = onInvertMultiImageScrollDirectionChange,
                onDownload = { onAction(ViewerAction.Download) },
                downloadEnabled = canDownloadCurrentMedia,
                onInfo = {
                    setActionsMenuExpanded(false)
                    setInfoSheetVisible(true)
                    markInteraction()
                },
            )
        }
        if (!inPictureInPicture && !mediaOverviewVisible) {
            ViewerLevelFeedbackOverlay(levelControls, Modifier.align(Alignment.Center))
        }
    }

    if (showInfoSheet) {
        val post = selectedPost
        PreloadCodexSaveRelevance(post, saveToCodexQuery)
        AdaptiveViewerInfo(onDismiss = { setInfoSheetVisible(false) }) {
            PostTagActionSection(
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Info", style = MaterialTheme.typography.titleMedium)
                            Row {
                                IconButton(onClick = {
                                    onAction(ViewerAction.Save)
                                    setInfoSheetVisible(false)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkAdd,
                                        contentDescription = "Save to Codex",
                                    )
                                }
                                IconButton(onClick = {
                                    setPlaybackEnabled(false)
                                    onGoToSearch()
                                    setInfoSheetVisible(false)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Go to Search",
                                    )
                                }
                                IconButton(onClick = {
                                    onAction(ViewerAction.Share)
                                    setInfoSheetVisible(false)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                    )
                                }
                                if (!post.pageUrl.isNullOrBlank()) {
                                    IconButton(onClick = {
                                        onOpenInBrowser(post)
                                        setInfoSheetVisible(false)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInBrowser,
                                            contentDescription = "Open in browser",
                                        )
                                    }
                                }
                            }
                        }

                        post.displayTitleOrNull()?.let { title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = "${post.id.source.displayName()} • ${post.id.sourcePostId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onOpenCreatorFallback != null) {
                            CreatorProfileActionButton(
                                post = post,
                                creatorBrowsingSources = creatorBrowsingSources,
                                onOpenProfile = { profile ->
                                    setPlaybackEnabled(false)
                                    onAction(ViewerAction.OpenCreator(profile))
                                    setInfoSheetVisible(false)
                                },
                                onOpenLegacyPost = {
                                    setPlaybackEnabled(false)
                                    onOpenCreatorFallback(post)
                                    setInfoSheetVisible(false)
                                },
                            )
                        }

                    }
                },
                footer = {

                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            setPlaybackEnabled(false)
                            onGoToSearch()
                            setInfoSheetVisible(false)
                        },
                    ) {
                        Text("Go to Search")
                    }
                },
                post = post,
                tagVideoCountProvider = tagVideoCountProvider,
                fetchTagVideoCounts = fetchTagVideoCounts,
                onAddIncludeTerm = { term ->
                    onAction(
                        ViewerAction.IncludeTag(
                            PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)
                        )
                    )
                    true
                },
                onAddExcludeTerm = { term ->
                    onAction(
                        ViewerAction.ExcludeTag(
                            PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)
                        )
                    )
                    true
                },
                onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
                onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
                onFavoriteTagLongPress = onFavoriteTagLongPress,
            )
        }
    }

}


private const val VIEWER_PAGINATION_PREFETCH_RATIO = 0.8f
