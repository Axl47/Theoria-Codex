package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.animation.core.tween
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ActionMenuDivider
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.PostActionHeader
import com.theoriacodex.app.ui.components.PostCreatorRows
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.tags.PostTagActionSection
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState
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
    onIncludeRelatedPosts: () -> Unit = {},
    onDismissRelatedPosts: () -> Unit = {},
    onRetryRelatedPosts: () -> Unit = {},
) {
    val relatedState = uiState.related.state
    val relatedPosts = uiState.related.posts
    val returnProgress = rememberViewerReturnProgress()
    val readReturnProgress = remember(returnProgress) { { returnProgress.value } }
    val returning = LocalViewerReturnTransition.current?.request != null
    val opening by remember(returning, returnProgress) { derivedStateOf { !returning && returnProgress.value > 0f } }
    val dismissDrag = remember { ViewerDismissDrag() }
    val activity = LocalContext.current.viewerActivity()
    val inPictureInPicture = activity?.viewerPictureInPictureActive == true
    var quality by remember(videoQuality) { mutableStateOf(videoQuality) }
    var qualityHeight by remember(uiState.currentMedia?.key) { mutableStateOf<Int?>(null) }
    var pendingPostId by remember(uiState.session) { mutableStateOf<PostId?>(null) }
    val navigation = remember(uiState.pages, uiState.currentPageIndex, uiState.related.navigation, uiState.related.extraPostIds, pendingPostId) {
        viewerPostNavigation(uiState, pendingPostId)
    }
    val pages = navigation.pages
    val posts = remember(pages) { pages.map { it.post } }
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
    val currentPostIndex = navigation.currentIndex
    val postPagerState = key(uiState.session, navigation.branchAnchor) {
        rememberPagerState(initialPage = currentPostIndex, pageCount = { posts.size })
    }
    var viewerTransform by remember(uiState.session?.value, uiState.currentMedia?.key) {
        mutableStateOf(ViewerTransformState())
    }
    var interactionSerial by remember { mutableIntStateOf(0) }
    var timelineInteractionActive by remember { mutableStateOf(false) }
    var relatedStripExpanded by remember { mutableStateOf(false) }
    var lastViewerPaginationRequestSize by remember(navigation.originalPosts.size) { mutableIntStateOf(-1) }
    var pendingDismiss by remember { mutableStateOf(false) }
    val pendingMediaJumpByPost = remember { mutableStateMapOf<PostId, Int>() }
    LaunchedEffect(posts, currentPostIndex, videoPositions) { videoPositions.retainAround(posts.map { it.id }, currentPostIndex) }
    val selectedPost = posts[currentPostIndex]
    val selectedPostLiked = selectedPost.id in likedPostIds
    val selectedPostMedia = remember(selectedPost) { postMediaItems(selectedPost) }
    val selectedMediaIndex = uiState.currentPage
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
    val chromeVisible = (uiState.controls.chromeVisible || touchExplorationEnabled) && !inPictureInPicture && !returning && !opening && !dismissDrag.active
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

    fun markInteraction() {
        interactionSerial += 1
        if (!chromeVisible && !opening && !returning) {
            onAction(ViewerAction.ToggleChrome)
        }
    }

    val mediaPagers = remember { mutableMapOf<PostId, PagerState>() }
    val haptics = LocalHapticFeedback.current
    val flingVelocity = with(LocalDensity.current) { VIEWER_FLING_VELOCITY.toPx() }
    fun targetMediaIndex(page: ViewerPageState?) = page?.let { it.pendingMediaIndex ?: it.selectedMediaIndex } ?: 0
    val swipeContext = ViewerSwipeContext(
        postPager = postPagerState,
        selection = ViewerHorizontalSwipeTarget(currentPostIndex, selectedMediaIndex),
        mediaPager = { index -> posts.getOrNull(index)?.let { mediaPagers[it.id] } },
        postIdAt = { index -> posts.getOrNull(index)?.id },
        postIndexOf = { id -> posts.indexOfFirst { it.id == id } },
        target = { origin, direction ->
            val page = pages.getOrNull(origin.postIndex)
            val neighbor = origin.postIndex + if (direction == ViewerHorizontalSwipeDirection.Next) 1 else -1
            page?.let {
                viewerHorizontalSwipeTarget(
                    currentPostIndex = origin.postIndex,
                    currentMediaIndex = origin.mediaIndex,
                    currentMediaCount = postMediaItems(it.post).size,
                    postCount = posts.size,
                    targetPostMediaCount = posts.getOrNull(neighbor)?.let { post -> postMediaItems(post).size } ?: 0,
                    direction = direction,
                    galleryMediaPending = it.hasPendingGalleryMedia(),
                    targetMediaIndex = targetMediaIndex(pages.getOrNull(neighbor)),
                )
            }
        },
        swipeSign = { index ->
            val mediaCount = pages.getOrNull(index)?.swipeMediaCount() ?: 1
            if (viewerMediaPagerReverseLayout(mediaCount, invertMultiImageScrollDirection)) -1f else 1f
        },
        prepareDestination = { target ->
            posts.getOrNull(target.postIndex)?.let { pendingMediaJumpByPost[it.id] = target.mediaIndex }
        },
        commit = { postId, mediaIndex ->
            val index = posts.indexOfFirst { it.id == postId }
            if (index >= 0) {
                if (postId != uiState.currentPage?.post?.id) onAction(ViewerAction.SelectPage(navigation.sourceIndices[index]))
                onAction(ViewerAction.SelectMedia(mediaIndex))
            }
        },
        onInteraction = { markInteraction() },
        onLimit = { haptics.performHapticFeedback(TheoriaHaptics.Limit) },
        flingVelocity = flingVelocity,
    )
    val swipeContextState = rememberUpdatedState(swipeContext)
    val swipe = remember { ViewerSwipeController(scope, swipeContextState) }
    LaunchedEffect(uiState.session) { swipe.reset() }
    val swipeInProgress = swipe.settling
    val dragInProgress = swipe.dragging
    val swipeLoadTarget = swipe.loadTarget

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

    // Pass these local functions as lambdas: a memoized `::function` keeps the values it first captured.
    fun requestDismissViewer() {
        if (pendingDismiss) return
        setPlaybackEnabled(false)
        setInfoSheetVisible(false)
        setActionsMenuExpanded(false)
        setPlaybackSettingsExpanded(false)
        setOverviewVisible(false)
        pendingDismiss = true
    }

    // Nothing may pop the route underneath a return that is already moving.
    BackHandler(enabled = pendingDismiss || returning) {}
    BackHandler(enabled = !pendingDismiss && !returning && mediaOverviewVisible) {
        setOverviewVisible(false)
        markInteraction()
    }
    val backDistance = with(LocalDensity.current) { VIEWER_BACK_DISTANCE.toPx() }
    PredictiveBackHandler(enabled = !pendingDismiss && !returning && !mediaOverviewVisible) { events ->
        var committed = false
        try {
            events.collect { dismissDrag.followBack(it.progress, it.swipeEdge == BackEventCompat.EDGE_LEFT, backDistance) }
            committed = true
        } finally {
            // Cancelling the back swipe cancels this collector, so it recoils in the screen's scope.
            if (committed) requestDismissViewer() else scope.launch { dismissDrag.recoil() }
        }
    }

    val swipeLoadPost = swipeLoadTarget?.let { posts.getOrNull(it.postIndex) }
    LaunchedEffect(swipeLoadPost?.id) {
        swipeLoadPost?.let { onAction(ViewerAction.RequestPageResolution(it.id)) }
    }

    LaunchedEffect(posts, currentPostIndex, selectedMediaIndex, swipeLoadTarget) {
        val keys = planAdjacentViewerPrefetch(
            pages = pages,
            current = uiState.currentMedia?.key,
        )
        val targetKey = swipeLoadTarget?.let { target ->
            pages.getOrNull(target.postIndex)?.media?.getOrNull(target.mediaIndex)?.key
        }
        onAction(ViewerAction.QueuePrefetch(listOfNotNull(targetKey).plus(keys).distinct()))
    }

    fun loadMoreFromStrip() {
        if (loadingMoreFromSource || !canLoadMoreFromSource || lastViewerPaginationRequestSize == navigation.originalPosts.size) return
        lastViewerPaginationRequestSize = navigation.originalPosts.size
        onAction(ViewerAction.LoadMore)
    }

    LaunchedEffect(navigation.originalPosts.size, navigation.stripAnchorIndex, canLoadMoreFromSource, loadingMoreFromSource) {
        if (loadingMoreFromSource || !canLoadMoreFromSource) return@LaunchedEffect
        val triggerIndex = ((navigation.originalPosts.lastIndex.coerceAtLeast(0)) * VIEWER_PAGINATION_PREFETCH_RATIO)
            .toInt()
            .coerceAtLeast(0)
        if (navigation.stripAnchorIndex >= triggerIndex) loadMoreFromStrip()
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        timelineInteractionActive = isActive
        if (isActive) {
            markInteraction()
        }
    }

    // A caught swipe commits while its pages are still moving, so only a resting pager follows selection.
    val swipeActive = swipeInProgress || dragInProgress
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState, swipeActive) {
        if (!swipeActive && postPagerState.currentPage != currentPostIndex) {
            postPagerState.scrollToPage(currentPostIndex)
        }
    }

    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState) {
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
            actionsMenuExpanded || playbackSettingsExpanded || relatedStripExpanded,
        touchExplorationEnabled = touchExplorationEnabled,
        onHide = { onAction(ViewerAction.ToggleChrome) },
    )

    LaunchedEffect(chromeVisible, currentIsSeekableMedia) {
        if (!chromeVisible || !currentIsSeekableMedia) {
            timelineInteractionActive = false
        }
    }

    fun navigateToPost(index: Int) {
        if (index == currentPostIndex) { pendingPostId = null; return }
        if (swipeInProgress || pendingDismiss || returning || opening) return
        swipe.navigateTo(ViewerHorizontalSwipeTarget(index, targetMediaIndex(pages[index])))
    }

    LaunchedEffect(pendingPostId, posts.size, swipeInProgress, opening, uiState.currentPageIndex) {
        val pending = pendingPostId ?: return@LaunchedEffect
        if (pending == uiState.currentPage?.post?.id) { pendingPostId = null; return@LaunchedEffect }
        if (swipeInProgress || opening) return@LaunchedEffect
        val index = posts.indexOfFirst { it.id == pending }
        if (index >= 0 && postPagerState.settledPage != index) {
            navigateToPost(index)
        }
    }

    CompositionLocalProvider(LocalViewerPostStripInset provides if (chromeVisible && !mediaOverviewVisible) 56.dp else 0.dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = (1f - returnProgress.value) * (1f - VIEWER_DISMISS_DIM * dismissDrag.progress)))
            }
    ) {
        if (mediaOverviewVisible && mediaOverviewAvailable) {
            ViewerMediaOverviewGrid(
                post = selectedPost,
                items = selectedMediaOverviewItems,
                selectedMediaIndex = selectedMediaIndex,
                onMediaSelected = { mediaIndex ->
                    onAction(ViewerAction.SelectOverviewMedia(mediaIndex))
                    pendingMediaJumpByPost[selectedPost.id] = mediaIndex
                    setOverviewVisible(false)
                    markInteraction()
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val reservedBottom = if (chromeVisible && selectedCurrentMedia?.let {
                    viewerMediaIsAnimated(selectedPost, it, pixivUgoiraClient != null)
                } == true) {
                with(LocalDensity.current) { (96.dp + LocalViewerPostStripInset.current).toPx() }
            } else 0f
            Box(
                Modifier.fillMaxSize()
                    .viewerGestures(
                        ViewerGestureConfig(
                            enabled = !viewerTransform.isZoomed && !inPictureInPicture && !touchExplorationEnabled &&
                                !pendingDismiss && !returning && !opening,
                            levels = levelControls.takeIf { selectedCurrentMedia?.let(::isVideoMediaRef) == true },
                            reservedBottomPx = reservedBottom,
                        ),
                        object : ViewerGestureHandler {
                            override fun onPress() = swipe.press()
                            override fun onHorizontalDrag(displacement: Float) = swipe.drag(displacement)
                            override fun onHorizontalRelease(displacement: Float, velocity: Float) = swipe.release(displacement, velocity)
                            override fun onDismissDrag(x: Float, y: Float, height: Float) {
                                val wasPast = dismissDrag.pastCommit
                                dismissDrag.followPull(x, y, height)
                                if (dismissDrag.pastCommit && !wasPast) haptics.performHapticFeedback(TheoriaHaptics.Threshold)
                            }
                            override fun onDismissRelease(velocityY: Float) {
                                if (viewerDismissCommits(dismissDrag.progress, velocityY, flingVelocity)) requestDismissViewer()
                            }
                            override fun onInfoSwipe() { setInfoSheetVisible(true); markInteraction() }
                            override fun onGestureEnd() {
                                swipe.cancel()
                                if (dismissDrag.active && !pendingDismiss) scope.launch { dismissDrag.recoil() }
                            }
                            override fun onLevelLimit() = haptics.performHapticFeedback(TheoriaHaptics.Limit)
                        },
                    )
                    .graphicsLayer { translationX = swipe.edgeOffset },
            ) {
            key(postPagerState) {
            HorizontalPager(
                state = postPagerState,
                key = { index -> encodeViewerPostId(posts[index].id) },
                userScrollEnabled = false,
                reverseLayout = viewerMediaPagerReverseLayout(
                    uiState.currentPage?.swipeMediaCount() ?: 1, invertMultiImageScrollDirection,
                ),
                modifier = Modifier.fillMaxSize(),
            ) { postPage ->
            val post = posts[postPage]
            val pageState = pages[postPage]
            val terminalResolutionMessage = pageState.resolution
                .takeIf { resolution ->
                    resolution.status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED &&
                        !resolution.recoverable
                }
                ?.message
            val postMedia = remember(post) { postMediaItems(post) }
            val mediaPagerReverseLayout = viewerMediaPagerReverseLayout(
                mediaCount = pageState.swipeMediaCount(),
                invertMultiImageScrollDirection = invertMultiImageScrollDirection,
            )
            val initialMediaPage = pageState.selectedMediaIndex
                .coerceIn(0, postMedia.lastIndex)
            val mediaPagerState = rememberPagerState(
                initialPage = initialMediaPage,
                pageCount = { postMedia.size },
            )
            DisposableEffect(post.id, mediaPagerState) {
                mediaPagers[post.id] = mediaPagerState
                onDispose { if (mediaPagers[post.id] === mediaPagerState) mediaPagers.remove(post.id) }
            }
            val pendingMediaJump = pendingMediaJumpByPost[post.id]

            LaunchedEffect(initialMediaPage, postMedia.size, swipeActive) {
                if (!swipeActive && mediaPagerState.currentPage != initialMediaPage) {
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
                pendingMediaJumpByPost.remove(post.id)
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
                val loadGeneration = pageState.media
                    .getOrNull(mediaPage)
                    ?.loadGeneration
                    ?: 0L
                val key = ViewerMediaKey(post.id, mediaPage)
                val active = postPage == currentPostIndex && mediaPage == selectedMediaIndex
                ViewerMediaPage(
                    post = post,
                    media = media,
                    returnProgress = if (active) readReturnProgress else restingReturnProgress,
                    dismissDrag = dismissDrag.takeIf { active },
                    mediaIndex = mediaPage,
                    controls = ViewerRendererControls(
                        isActive = active,
                        isPlaying = mediaPlaybackEnabled,
                        isPreloading = swipeLoadTarget == ViewerHorizontalSwipeTarget(postPage, mediaPage),
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
                        onTimelineInteractionActiveChanged = { onTimelineInteractionChanged(it) },
                        onDurationKnown = { duration ->
                            if (isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)) onAuthoritativeDurationKnown(post, duration)
                        },
                    ),
                    transform = viewerTransform,
                    onTransformChanged = { viewerTransform = it },
                    layout = ViewerMediaLayout(isLandscape, inPictureInPicture, touchExplorationEnabled),
                    videoOptions = ViewerVideoOptions(quality, qualityHeight, terminalResolutionMessage, playbackDiagnosticsEnabled),
                    videoPositions = videoPositions,
                    levelControls = levelControls,
                    mediaError = uiState.mediaError?.takeIf { active && it.mediaKey == key },
                    pixivUgoiraClient = pixivUgoiraClient,
                    onAction = onAction,
                    onInteraction = { markInteraction() },
                    onToggleChrome = { onAction(ViewerAction.ToggleChrome); interactionSerial += 1 },
                    imageSession = imageSession,
                    onRequestMediaRecovery = onRequestMediaRecovery,
                )
            }
        }
        }
            }

        }
        ViewerChromeVisibility(chromeVisible && !mediaOverviewVisible, Modifier.align(Alignment.BottomCenter)) {
            val stripIndex = navigation.originalPosts.indexOfFirst { it.id == posts.getOrNull(postPagerState.currentPage)?.id }
                .takeIf { it >= 0 } ?: navigation.stripAnchorIndex
            ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
                pendingPostId = navigation.originalPosts[index].id
            }, Modifier.testTag("viewer-post-navigation-strip"),
                onLoadMore = { loadMoreFromStrip() }, onInteraction = { markInteraction() })
        }
        ViewerChromeVisibility(chromeVisible, Modifier.align(Alignment.TopCenter)) {
            ViewerChrome(
                onPictureInPicture = viewerPictureInPictureAction(activity, selectedPost, selectedCurrentMedia, uiState.mediaError != null),
                playbackMode = uiState.controls.playbackMode,
                onPlaybackModeSelected = { onAction(ViewerAction.SetPlaybackMode(it)) },
                quality = quality,
                qualityHeight = qualityHeight,
                videoVariants = uiState.currentMedia?.ref?.videoVariants.orEmpty(),
                onQualitySelected = { selected, height -> quality = selected; qualityHeight = height },
                source = selectedPost.id.source.displayName(),
                indexLabel = if (selectedPostMedia.size > 1) {
                    "${selectedMediaIndex + 1} / ${selectedPostMedia.size}"
                } else null,
                onBack = { requestDismissViewer() },
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
                onInfo = {
                    setActionsMenuExpanded(false)
                    setInfoSheetVisible(true)
                    markInteraction()
                },
            )
        }
        ViewerChromeVisibility(chromeVisible && !mediaOverviewVisible, Modifier.align(Alignment.TopEnd).padding(top = 72.dp)) {
            ViewerRelatedStrip(
                state = relatedState, posts = relatedPosts, currentPostId = selectedPost.id,
                onSelect = { post ->
                    markInteraction()
                    if (post.id != selectedPost.id) {
                        pendingPostId = post.id
                        onIncludeRelatedPosts()
                    }
                },
                onDismiss = { onDismissRelatedPosts(); markInteraction() },
                onRetry = { onRetryRelatedPosts(); markInteraction() },
                enabled = !swipeInProgress && !dragInProgress,
                onExpandedChange = { relatedStripExpanded = it },
                onInteraction = { markInteraction() },
            )
        }
    }

    }
    if (showInfoSheet) {
        val post = selectedPost
        PreloadCodexSaveRelevance(post, saveToCodexQuery)
        AdaptiveViewerInfo(onDismiss = { setInfoSheetVisible(false) }) {
            PostTagActionSection(
                header = {
                    Column {
                        PostActionHeader(post, showPostId = true)
                        ActionMenuRow(Icons.Default.BookmarkAdd, "Save to Codex") {
                            onAction(ViewerAction.Save)
                            setInfoSheetVisible(false)
                        }
                        ActionMenuRow(Icons.Default.Download, "Save to device", canDownloadCurrentMedia) {
                            onAction(ViewerAction.Download)
                            setInfoSheetVisible(false)
                        }
                        ActionMenuDivider()
                        if (onOpenCreatorFallback != null) {
                            PostCreatorRows(
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
                        ActionMenuRow(Icons.Default.Search, "Go to Search") {
                            setPlaybackEnabled(false)
                            onGoToSearch()
                            setInfoSheetVisible(false)
                        }
                        ActionMenuRow(Icons.Default.Share, "Share") {
                            onAction(ViewerAction.Share)
                            setInfoSheetVisible(false)
                        }
                        if (!post.pageUrl.isNullOrBlank()) {
                            ActionMenuRow(Icons.Default.OpenInBrowser, "Open in browser") {
                                onOpenInBrowser(post)
                                setInfoSheetVisible(false)
                            }
                        }
                        ActionMenuDivider()
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

/** Viewer controls fade rather than popping over the media they share the screen with. */
@Composable
private fun ViewerChromeVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) { content() }
}

private val restingReturnProgress = { 0f }
private val VIEWER_BACK_DISTANCE = 48.dp
private const val VIEWER_DISMISS_DIM = 0.7f
