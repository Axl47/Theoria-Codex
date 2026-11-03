package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import android.content.res.Configuration
import androidx.compose.animation.core.tween
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import com.theoriacodex.app.MainActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
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
import kotlinx.coroutines.CoroutineScope
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
    val returnProgress = rememberViewerReturnProgress()
    val readReturnProgress = remember(returnProgress) { { returnProgress.value } }
    val returning = LocalViewerReturnTransition.current?.request != null
    val opening by remember(returning, returnProgress) { derivedStateOf { !returning && returnProgress.value > 0f } }
    val dismissDrag = remember { ViewerDismissDrag() }
    val activity = LocalContext.current.viewerActivity()
    val inPictureInPicture = activity?.viewerPictureInPictureActive == true
    val quality = remember(videoQuality) { mutableStateOf(videoQuality) }
    val qualityHeight = remember(uiState.currentMedia?.key) { mutableStateOf<Int?>(null) }
    val pendingPostId = remember(uiState.session) { mutableStateOf<PostId?>(null) }
    val navigation = remember(uiState.pages, uiState.currentPageIndex, uiState.related.navigation, uiState.related.extraPostIds, pendingPostId.value) {
        viewerPostNavigation(uiState, pendingPostId.value)
    }
    val posts = remember(navigation.pages) { navigation.pages.map { it.post } }
    val videoPositions = remember(uiState.session) { ViewerVideoPositions() }
    if (posts.isEmpty()) {
        ViewerNoPosts()
        return
    }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val screen = remember { ViewerScreenState() }
    val postPagerState = key(uiState.session, navigation.branchAnchor) {
        rememberPagerState(initialPage = navigation.currentIndex, pageCount = { posts.size })
    }
    LaunchedEffect(posts, navigation.currentIndex, videoPositions) { videoPositions.retainAround(posts.map { it.id }, navigation.currentIndex) }
    val viewerTransform = remember(uiState.session?.value, uiState.currentMedia?.key) { mutableStateOf(ViewerTransformState()) }
    val selection = rememberViewerSelection(uiState, posts[navigation.currentIndex], pixivUgoiraClient != null)
    val levelControls = rememberViewerLevelControls(
        enabled = !inPictureInPicture && selection.media?.let(::isVideoMediaRef) == true && !uiState.overview.visible,
    )
    val touchExplorationEnabled = rememberViewerTouchExploration()
    val chromeVisible = (uiState.controls.chromeVisible || touchExplorationEnabled) && !inPictureInPicture && !returning && !opening && !dismissDrag.active
    val chromeStage = rememberViewerChromeStage(chromeVisible)
    val controls = rememberViewerControls(onAction, screen, uiState.overview.visible, chromeVisible, opening, returning)
    val frame = remember(uiState, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape) {
        ViewerFrame(uiState, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape)
    }
    val flingVelocity = with(LocalDensity.current) { VIEWER_FLING_VELOCITY.toPx() }
    val swipe = rememberViewerSwipe(frame, postPagerState, invertMultiImageScrollDirection, screen, controls, flingVelocity, scope, onAction)
    // Runs at most once per list size, and only while the source has more to give.
    val paginationRequestSize = remember(navigation.originalPosts.size) { mutableIntStateOf(-1) }
    val loadMoreFromStrip = {
        if (!loadingMoreFromSource && canLoadMoreFromSource && paginationRequestSize.intValue != navigation.originalPosts.size) {
            paginationRequestSize.intValue = navigation.originalPosts.size
            onAction(ViewerAction.LoadMore)
        }
    }
    ViewerFrameEffects(
        frame, screen, controls, swipe, dismissDrag, postPagerState, pendingPostId, canLoadMoreFromSource,
        loadingMoreFromSource, loadMoreFromStrip, onAction, onVisiblePostChanged,
    )
    val viewerAccent = rememberViewerAccent(selection.post, enabled = !opening && !returning)
    CompositionLocalProvider(
        LocalViewerPostStripInset provides if (chromeVisible && !frame.mediaOverviewVisible) 56.dp else 0.dp,
        LocalViewerAccent provides viewerAccent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = (1f - returnProgress.value) * (1f - VIEWER_DISMISS_DIM * dismissDrag.progress)))
                }
        ) {
            if (frame.mediaOverviewVisible && selection.overviewAvailable) {
                ViewerMediaOverviewGrid(
                    post = selection.post,
                    items = selection.overviewItems,
                    selectedMediaIndex = selection.mediaIndex,
                    onMediaSelected = { mediaIndex ->
                        onAction(ViewerAction.SelectOverviewMedia(mediaIndex))
                        screen.pendingMediaJumpByPost[selection.post.id] = mediaIndex
                        controls.setOverviewVisible(false)
                        controls.markInteraction()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ViewerPostPagerSurface(
                    frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls, readReturnProgress, flingVelocity,
                    quality.value, qualityHeight.value, playbackDiagnosticsEnabled, videoPositions, invertMultiImageScrollDirection,
                    pixivUgoiraClient, onAction, onVisibleMediaChanged, onAuthoritativeDurationKnown, onRequestMediaRecovery, scope,
                )
            }
            ViewerChromeOverlays(
                frame, postPagerState, screen, controls, swipe, activity, quality, qualityHeight, pendingPostId, likedPostIds,
                onAction, loadMoreFromStrip, onIncludeRelatedPosts, onDismissRelatedPosts, onRetryRelatedPosts,
            )
        }
    }
    if (uiState.controls.metadataVisible && !inPictureInPicture) {
        ViewerInfoSheet(
            selection, controls, creatorBrowsingSources, saveToCodexQuery, tagVideoCountProvider, fetchTagVideoCounts,
            onAction, onOpenInBrowser, onRemoveIncludeTerm, onRemoveExcludeTerm, onFavoriteTagLongPress, onGoToSearch, onOpenCreatorFallback,
        )
    }
}

@Composable
private fun ViewerNoPosts() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text("No posts to view", color = MaterialTheme.colorScheme.onBackground)
    }
}

/** State the screen's parts share for as long as the Viewer stays composed. */
@Stable
internal class ViewerScreenState {
    val imageSession = ViewerImageSession()
    val mediaPagers = mutableMapOf<PostId, PagerState>()
    val pendingMediaJumpByPost = mutableStateMapOf<PostId, Int>()
    var interactionSerial by mutableIntStateOf(0)
    var timelineInteractionActive by mutableStateOf(false)
    var relatedStripExpanded by mutableStateOf(false)
    var pendingDismiss by mutableStateOf(false)
}

/**
 * What the user can toggle, judged against the frame these values came from. Use
 * [rememberViewerControls], which replaces the instance when any of them change; an instance held
 * across that change would toggle chrome or dismiss against a state that has passed.
 */
internal class ViewerControls(
    private val onAction: (ViewerAction) -> Unit,
    private val screen: ViewerScreenState,
    private val overviewVisible: Boolean,
    private val chromeVisible: Boolean,
    private val opening: Boolean,
    private val returning: Boolean,
) {
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
        if (visible != overviewVisible) onAction(ViewerAction.ToggleOverview)
    }

    fun markInteraction() {
        screen.interactionSerial += 1
        if (!chromeVisible && !opening && !returning) onAction(ViewerAction.ToggleChrome)
    }

    /** Seeking shows its own feedback: it keeps open controls alive but never opens them. */
    fun markSeekInteraction() {
        screen.interactionSerial += 1
    }

    fun toggleChrome() {
        onAction(ViewerAction.ToggleChrome)
        screen.interactionSerial += 1
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        screen.timelineInteractionActive = isActive
        if (isActive) markInteraction()
    }

    fun requestDismiss() {
        if (screen.pendingDismiss) return
        setPlaybackEnabled(false)
        setInfoSheetVisible(false)
        setActionsMenuExpanded(false)
        setPlaybackSettingsExpanded(false)
        setOverviewVisible(false)
        screen.pendingDismiss = true
    }
}

@Composable
internal fun rememberViewerControls(
    onAction: (ViewerAction) -> Unit,
    screen: ViewerScreenState,
    overviewVisible: Boolean,
    chromeVisible: Boolean,
    opening: Boolean,
    returning: Boolean,
): ViewerControls = remember(onAction, screen, overviewVisible, chromeVisible, opening, returning) {
    ViewerControls(onAction, screen, overviewVisible, chromeVisible, opening, returning)
}

/** The selected post's media, and what that media allows. */
private class ViewerSelection(
    val post: Post,
    val mediaItems: List<ImageRef>,
    val mediaIndex: Int,
    val canPlayUgoira: Boolean,
    val overviewItems: List<ViewerMediaOverviewItem>,
) {
    val media: ImageRef? = mediaItems.getOrNull(mediaIndex)
    val isSeekable = media?.let { media ->
        isVideoMediaRef(media) || isGifMediaRef(media) || (isPixivUgoiraMedia(post, media) && canPlayUgoira)
    } == true
    val canDownload = media?.let { media ->
        (isPixivUgoiraMedia(post, media) && canPlayUgoira) || !media.url.isNullOrBlank()
    } == true
    val isAnimated = media?.let { viewerMediaIsAnimated(post, it, canPlayUgoira) } == true
    val overviewAvailable = viewerMediaOverviewAvailable(overviewItems)
}

@Composable
private fun rememberViewerSelection(uiState: ViewerUiState, post: Post, canPlayUgoira: Boolean): ViewerSelection {
    val mediaItems = remember(post) { postMediaItems(post) }
    val mediaIndex = uiState.currentPage?.selectedMediaIndex?.coerceIn(0, mediaItems.lastIndex) ?: 0
    val overviewItems = remember(post) { viewerMediaOverviewItems(post) }
    return remember(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems) {
        ViewerSelection(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems)
    }
}

/** One composition's view of the screen, kept as one instance while none of these values change. */
private class ViewerFrame(
    val uiState: ViewerUiState,
    val navigation: ViewerPostNavigation,
    val posts: List<Post>,
    val selection: ViewerSelection,
    val chromeVisible: Boolean,
    val chromeStage: Int,
    val opening: Boolean,
    val returning: Boolean,
    val inPictureInPicture: Boolean,
    val touchExplorationEnabled: Boolean,
    val isLandscape: Boolean,
) {
    val mediaOverviewVisible = uiState.overview.visible && !inPictureInPicture
}

private fun targetMediaIndex(page: ViewerPageState?) = page?.let { it.pendingMediaIndex ?: it.selectedMediaIndex } ?: 0

@Composable
private fun rememberViewerSwipe(
    frame: ViewerFrame,
    postPagerState: PagerState,
    invertMultiImageScrollDirection: Boolean,
    screen: ViewerScreenState,
    controls: ViewerControls,
    flingVelocity: Float,
    scope: CoroutineScope,
    onAction: (ViewerAction) -> Unit,
): ViewerSwipeController {
    val haptics = LocalHapticFeedback.current
    val pages = frame.navigation.pages
    val posts = frame.posts
    val swipeContext = ViewerSwipeContext(
        postPager = postPagerState,
        selection = ViewerHorizontalSwipeTarget(frame.navigation.currentIndex, frame.selection.mediaIndex),
        mediaPager = { index -> posts.getOrNull(index)?.let { screen.mediaPagers[it.id] } },
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
            posts.getOrNull(target.postIndex)?.let { screen.pendingMediaJumpByPost[it.id] = target.mediaIndex }
        },
        commit = { postId, mediaIndex ->
            val index = posts.indexOfFirst { it.id == postId }
            if (index >= 0) {
                if (postId != frame.uiState.currentPage?.post?.id) onAction(ViewerAction.SelectPage(frame.navigation.sourceIndices[index]))
                onAction(ViewerAction.SelectMedia(mediaIndex))
            }
        },
        onInteraction = { controls.markInteraction() },
        onLimit = { haptics.performHapticFeedback(TheoriaHaptics.Limit) },
        flingVelocity = flingVelocity,
    )
    val swipeContextState = rememberUpdatedState(swipeContext)
    val swipe = remember { ViewerSwipeController(scope, swipeContextState) }
    LaunchedEffect(frame.uiState.session) { swipe.reset() }
    return swipe
}

/** Every effect that follows the frame, in the order they must start. Per-page effects live in the page. */
@Composable
private fun ViewerFrameEffects(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    postPagerState: PagerState,
    pendingPostId: MutableState<PostId?>,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    loadMoreFromStrip: () -> Unit,
    onAction: (ViewerAction) -> Unit,
    onVisiblePostChanged: ((Post, Int) -> Unit)?,
) {
    val uiState = frame.uiState
    val showInfoSheet = uiState.controls.metadataVisible && !frame.inPictureInPicture
    ViewerSelectionEffects(frame, screen, controls, swipe.settling, onAction)
    ViewerBackHandlers(frame, screen, controls, dismissDrag)
    ViewerNavigationEffects(frame, swipe, postPagerState, controls, canLoadMoreFromSource, loadingMoreFromSource, loadMoreFromStrip, onAction, onVisiblePostChanged)
    ViewerChromeAutoHideEffect(
        chromeVisible = frame.chromeVisible,
        interactionSerial = screen.interactionSerial,
        interactionBlocked = screen.timelineInteractionActive || frame.mediaOverviewVisible || showInfoSheet ||
            uiState.controls.actionsMenuVisible || uiState.controls.playbackSettingsVisible || screen.relatedStripExpanded,
        touchExplorationEnabled = frame.touchExplorationEnabled,
        onHide = { onAction(ViewerAction.ToggleChrome) },
    )
    LaunchedEffect(frame.chromeVisible, frame.selection.isSeekable) {
        if (!frame.chromeVisible || !frame.selection.isSeekable) screen.timelineInteractionActive = false
    }
    ViewerPendingPostEffect(frame, screen, swipe, postPagerState, pendingPostId)
}

@Composable
private fun ViewerSelectionEffects(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipeInProgress: Boolean,
    onAction: (ViewerAction) -> Unit,
) {
    val uiState = frame.uiState
    val post = frame.selection.post
    LaunchedEffect(uiState.session) {
        screen.pendingDismiss = false
    }
    LaunchedEffect(frame.selection.isSeekable, frame.chromeVisible) {
        if (!frame.selection.isSeekable) controls.setPlaybackSettingsExpanded(false)
        if (!frame.chromeVisible) {
            controls.setActionsMenuExpanded(false)
            controls.setPlaybackSettingsExpanded(false)
        }
    }
    LaunchedEffect(frame.selection.overviewAvailable) {
        if (!frame.selection.overviewAvailable) controls.setOverviewVisible(false)
    }
    LaunchedEffect(
        post.id.source,
        post.id.sourcePostId,
        post.full?.url,
        post.full?.localPath,
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
    LaunchedEffect(screen.pendingDismiss, swipeInProgress, frame.opening) {
        if (!screen.pendingDismiss || swipeInProgress || frame.opening) return@LaunchedEffect
        withFrameNanos { }
        onAction(ViewerAction.Dismiss)
    }
}

@Composable
private fun ViewerBackHandlers(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    dismissDrag: ViewerDismissDrag,
) {
    val scope = rememberCoroutineScope()
    // Nothing may pop the route underneath a return that is already moving.
    BackHandler(enabled = screen.pendingDismiss || frame.returning) {}
    BackHandler(enabled = !screen.pendingDismiss && !frame.returning && frame.mediaOverviewVisible) {
        controls.setOverviewVisible(false)
        controls.markInteraction()
    }
    val backDistance = with(LocalDensity.current) { VIEWER_BACK_DISTANCE.toPx() }
    PredictiveBackHandler(enabled = !screen.pendingDismiss && !frame.returning && !frame.mediaOverviewVisible) { events ->
        var committed = false
        try {
            events.collect { dismissDrag.followBack(it.progress, it.swipeEdge == BackEventCompat.EDGE_LEFT, backDistance) }
            committed = true
        } finally {
            // Cancelling the back swipe cancels this collector, so it recoils in the screen's scope.
            if (committed) controls.requestDismiss() else scope.launch { dismissDrag.recoil() }
        }
    }
}

@Composable
private fun ViewerNavigationEffects(
    frame: ViewerFrame,
    swipe: ViewerSwipeController,
    postPagerState: PagerState,
    controls: ViewerControls,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    loadMoreFromStrip: () -> Unit,
    onAction: (ViewerAction) -> Unit,
    onVisiblePostChanged: ((Post, Int) -> Unit)?,
) {
    val uiState = frame.uiState
    val navigation = frame.navigation
    val pages = navigation.pages
    val posts = frame.posts
    val currentPostIndex = navigation.currentIndex
    val swipeLoadTarget = swipe.loadTarget
    val swipeLoadPost = swipeLoadTarget?.let { posts.getOrNull(it.postIndex) }
    LaunchedEffect(swipeLoadPost?.id) {
        swipeLoadPost?.let { onAction(ViewerAction.RequestPageResolution(it.id)) }
    }
    LaunchedEffect(posts, currentPostIndex, frame.selection.mediaIndex, swipeLoadTarget) {
        val keys = planAdjacentViewerPrefetch(pages = pages, current = uiState.currentMedia?.key)
        val targetKey = swipeLoadTarget?.let { target ->
            pages.getOrNull(target.postIndex)?.media?.getOrNull(target.mediaIndex)?.key
        }
        onAction(ViewerAction.QueuePrefetch(listOfNotNull(targetKey).plus(keys).distinct()))
    }
    LaunchedEffect(navigation.originalPosts.size, navigation.stripAnchorIndex, canLoadMoreFromSource, loadingMoreFromSource) {
        if (loadingMoreFromSource || !canLoadMoreFromSource) return@LaunchedEffect
        val triggerIndex = ((navigation.originalPosts.lastIndex.coerceAtLeast(0)) * VIEWER_PAGINATION_PREFETCH_RATIO)
            .toInt()
            .coerceAtLeast(0)
        if (navigation.stripAnchorIndex >= triggerIndex) loadMoreFromStrip()
    }
    // A caught swipe commits while its pages are still moving, so only a resting pager follows selection.
    val swipeActive = swipe.settling || swipe.dragging
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState, swipeActive) {
        if (!swipeActive && postPagerState.currentPage != currentPostIndex) {
            postPagerState.scrollToPage(currentPostIndex)
        }
    }
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState) {
        controls.setOverviewVisible(false)
        controls.markInteraction()
    }
    LaunchedEffect(frame.selection.post.id, currentPostIndex, uiState.currentPage?.pendingMediaIndex) {
        if (uiState.currentPage?.pendingMediaIndex == null) {
            onVisiblePostChanged?.invoke(frame.selection.post, frame.selection.mediaIndex + 1)
        }
    }
}

/** Moves to the post a strip or drawer tap asked for, once nothing else is moving the pager. */
@Composable
private fun ViewerPendingPostEffect(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    swipe: ViewerSwipeController,
    postPagerState: PagerState,
    pendingPostId: MutableState<PostId?>,
) {
    val uiState = frame.uiState
    val swipeInProgress = swipe.settling
    LaunchedEffect(pendingPostId.value, frame.posts.size, swipeInProgress, frame.opening, uiState.currentPageIndex) {
        val pending = pendingPostId.value ?: return@LaunchedEffect
        if (pending == uiState.currentPage?.post?.id) { pendingPostId.value = null; return@LaunchedEffect }
        if (swipeInProgress || frame.opening) return@LaunchedEffect
        val index = frame.posts.indexOfFirst { it.id == pending }
        if (index < 0 || postPagerState.settledPage == index) return@LaunchedEffect
        if (index == frame.navigation.currentIndex) { pendingPostId.value = null; return@LaunchedEffect }
        if (screen.pendingDismiss || frame.returning) return@LaunchedEffect
        swipe.navigateTo(ViewerHorizontalSwipeTarget(index, targetMediaIndex(frame.navigation.pages[index])))
    }
}

/** The swipeable post pager, under the screen-wide gesture recognizer. */
@Composable
private fun ViewerPostPagerSurface(
    frame: ViewerFrame,
    postPagerState: PagerState,
    viewerTransform: MutableState<ViewerTransformState>,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    levelControls: ViewerLevelControls,
    readReturnProgress: () -> Float,
    flingVelocity: Float,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    playbackDiagnosticsEnabled: Boolean,
    videoPositions: ViewerVideoPositions,
    invertMultiImageScrollDirection: Boolean,
    pixivUgoiraClient: PixivUgoiraClient?,
    onAction: (ViewerAction) -> Unit,
    onVisibleMediaChanged: ((Post, Int) -> Unit)?,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
    scope: CoroutineScope,
) {
    val haptics = LocalHapticFeedback.current
    val selection = frame.selection
    val reservedBottom = if (frame.chromeVisible && selection.isAnimated) {
        with(LocalDensity.current) { (VIEWER_TIMELINE_HEIGHT + LocalViewerPostStripInset.current).toPx() }
    } else 0f
    Box(
        Modifier.fillMaxSize()
            .viewerGestures(
                ViewerGestureConfig(
                    enabled = !viewerTransform.value.isZoomed && !frame.inPictureInPicture && !frame.touchExplorationEnabled &&
                        !screen.pendingDismiss && !frame.returning && !frame.opening,
                    levels = levelControls.takeIf { selection.media?.let(::isVideoMediaRef) == true },
                    reservedBottomPx = reservedBottom,
                ),
                viewerGestureHandler(swipe, dismissDrag, screen, controls, haptics, flingVelocity, scope),
            )
            .graphicsLayer { translationX = swipe.edgeOffset },
    ) {
        key(postPagerState) {
            HorizontalPager(
                state = postPagerState,
                key = { index -> encodeViewerPostId(frame.posts[index].id) },
                userScrollEnabled = false,
                reverseLayout = viewerMediaPagerReverseLayout(
                    frame.uiState.currentPage?.swipeMediaCount() ?: 1, invertMultiImageScrollDirection,
                ),
                modifier = Modifier.fillMaxSize(),
            ) { postPage ->
                ViewerPostPage(
                    postPage, frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls, readReturnProgress, quality,
                    qualityHeight, playbackDiagnosticsEnabled, videoPositions, invertMultiImageScrollDirection, pixivUgoiraClient,
                    onAction, onVisibleMediaChanged, onAuthoritativeDurationKnown, onRequestMediaRecovery,
                )
            }
        }
    }
}

private fun viewerGestureHandler(
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    screen: ViewerScreenState,
    controls: ViewerControls,
    haptics: HapticFeedback,
    flingVelocity: Float,
    scope: CoroutineScope,
) = object : ViewerGestureHandler {
    override fun onPress() = swipe.press()
    override fun onHorizontalDrag(displacement: Float) = swipe.drag(displacement)
    override fun onHorizontalRelease(displacement: Float, velocity: Float) = swipe.release(displacement, velocity)
    override fun onDismissDrag(x: Float, y: Float, height: Float) {
        val wasPast = dismissDrag.pastCommit
        dismissDrag.followPull(x, y, height)
        if (dismissDrag.pastCommit && !wasPast) haptics.performHapticFeedback(TheoriaHaptics.Threshold)
    }
    override fun onDismissRelease(velocityY: Float) {
        if (viewerDismissCommits(dismissDrag.progress, velocityY, flingVelocity)) controls.requestDismiss()
    }
    override fun onInfoSwipe() { controls.setInfoSheetVisible(true); controls.markInteraction() }
    override fun onGestureEnd() {
        swipe.cancel()
        if (dismissDrag.active && !screen.pendingDismiss) scope.launch { dismissDrag.recoil() }
    }
    override fun onLevelLimit() = haptics.performHapticFeedback(TheoriaHaptics.Limit)
}

/** One post's media pager, with the effects that keep it in step with the selection. */
@Composable
private fun ViewerPostPage(
    postPage: Int,
    frame: ViewerFrame,
    postPagerState: PagerState,
    viewerTransform: MutableState<ViewerTransformState>,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    levelControls: ViewerLevelControls,
    readReturnProgress: () -> Float,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    playbackDiagnosticsEnabled: Boolean,
    videoPositions: ViewerVideoPositions,
    invertMultiImageScrollDirection: Boolean,
    pixivUgoiraClient: PixivUgoiraClient?,
    onAction: (ViewerAction) -> Unit,
    onVisibleMediaChanged: ((Post, Int) -> Unit)?,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
) {
    val uiState = frame.uiState
    val currentPostIndex = frame.navigation.currentIndex
    val selectedMediaIndex = frame.selection.mediaIndex
    val post = frame.posts[postPage]
    val pageState = frame.navigation.pages[postPage]
    val terminalResolutionMessage = pageState.resolution
        .takeIf { resolution ->
            resolution.status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED && !resolution.recoverable
        }
        ?.message
    val postMedia = remember(post) { postMediaItems(post) }
    val initialMediaPage = pageState.selectedMediaIndex.coerceIn(0, postMedia.lastIndex)
    val mediaPagerState = rememberPagerState(initialPage = initialMediaPage, pageCount = { postMedia.size })
    DisposableEffect(post.id, mediaPagerState) {
        screen.mediaPagers[post.id] = mediaPagerState
        onDispose { if (screen.mediaPagers[post.id] === mediaPagerState) screen.mediaPagers.remove(post.id) }
    }
    val swipeActive = swipe.settling || swipe.dragging
    LaunchedEffect(initialMediaPage, postMedia.size, swipeActive) {
        if (!swipeActive && mediaPagerState.currentPage != initialMediaPage) {
            mediaPagerState.scrollToPage(initialMediaPage)
        }
    }
    val pendingMediaJump = screen.pendingMediaJumpByPost[post.id]
    LaunchedEffect(pendingMediaJump, postMedia.size, pageState.pendingMediaIndex) {
        if (pendingMediaJump == null || pageState.pendingMediaIndex != null) return@LaunchedEffect
        val targetPage = pendingMediaJump.coerceIn(0, postMedia.lastIndex)
        mediaPagerState.scrollToPage(targetPage)
        if (postPage == currentPostIndex && selectedMediaIndex != targetPage) {
            onAction(ViewerAction.SelectMedia(targetPage))
        }
        screen.pendingMediaJumpByPost.remove(post.id)
    }
    LaunchedEffect(mediaPagerState.settledPage, currentPostIndex, pageState.pendingMediaIndex, swipe.dragging, swipe.settling) {
        if (swipe.dragging || swipe.settling || pageState.pendingMediaIndex != null) return@LaunchedEffect
        if (postPage == currentPostIndex && mediaPagerState.settledPage != selectedMediaIndex) {
            onAction(ViewerAction.SelectMedia(mediaPagerState.settledPage))
        }
        if (postPage == currentPostIndex) {
            onVisibleMediaChanged?.invoke(post, mediaPagerState.settledPage + 1)
        }
        if (postPage == postPagerState.currentPage) {
            controls.markInteraction()
        }
    }
    HorizontalPager(
        state = mediaPagerState,
        userScrollEnabled = false,
        reverseLayout = viewerMediaPagerReverseLayout(
            mediaCount = pageState.swipeMediaCount(),
            invertMultiImageScrollDirection = invertMultiImageScrollDirection,
        ),
        modifier = Modifier.fillMaxSize(),
        contentPadding = if (frame.isLandscape) PaddingValues(0.dp) else PaddingValues(top = 40.dp),
        pageSpacing = if (frame.isLandscape) 0.dp else 8.dp,
    ) { mediaPage ->
        val media = postMedia[mediaPage]
        val loadGeneration = pageState.media.getOrNull(mediaPage)?.loadGeneration ?: 0L
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
                isPlaying = uiState.controls.playback.playing,
                isPreloading = swipe.loadTarget == ViewerHorizontalSwipeTarget(postPage, mediaPage),
                showControls = frame.chromeStage >= VIEWER_CHROME_STAGE_TIMELINE && active,
                showPassiveProgress = !frame.chromeVisible && active && !frame.inPictureInPicture,
                loopPlayback = uiState.controls.playbackMode == com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP,
                playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate).speed,
                restartRequest = uiState.controls.playback.restartRequest,
                loadGeneration = loadGeneration,
                systemControlsReady = !frame.opening,
            ),
            events = viewerRendererEvents(
                session = uiState.session, key = key, generation = loadGeneration,
                onAction = onAction,
                onTimelineInteractionActiveChanged = { controls.onTimelineInteractionChanged(it) },
                onDurationKnown = { duration ->
                    if (isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)) onAuthoritativeDurationKnown(post, duration)
                },
            ),
            transform = viewerTransform.value,
            onTransformChanged = { viewerTransform.value = it },
            layout = ViewerMediaLayout(frame.isLandscape, frame.inPictureInPicture, frame.touchExplorationEnabled),
            videoOptions = ViewerVideoOptions(quality, qualityHeight, terminalResolutionMessage, playbackDiagnosticsEnabled),
            videoPositions = videoPositions,
            levelControls = levelControls,
            mediaError = uiState.mediaError?.takeIf { active && it.mediaKey == key },
            pixivUgoiraClient = pixivUgoiraClient,
            onAction = onAction,
            onInteraction = { controls.markInteraction() },
            onSeekInteraction = { controls.markSeekInteraction() },
            onToggleChrome = { controls.toggleChrome() },
            imageSession = screen.imageSession,
            onRequestMediaRecovery = onRequestMediaRecovery,
        )
    }
}

/** Everything drawn over the media: top bar, post strip, and related drawer. */
@Composable
private fun BoxScope.ViewerChromeOverlays(
    frame: ViewerFrame,
    postPagerState: PagerState,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    activity: MainActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    pendingPostId: MutableState<PostId?>,
    likedPostIds: Set<PostId>,
    onAction: (ViewerAction) -> Unit,
    loadMoreFromStrip: () -> Unit,
    onIncludeRelatedPosts: () -> Unit,
    onDismissRelatedPosts: () -> Unit,
    onRetryRelatedPosts: () -> Unit,
) {
    val selection = frame.selection
    val navigation = frame.navigation
    // While scrubbing everything but the timeline steps back. Only its alpha changes, so the strip keeps its scroll position.
    val scrubFade by animateFloatAsState(if (screen.timelineInteractionActive) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer scrub fade")
    val fadesWhileScrubbing = Modifier.graphicsLayer { alpha = scrubFade }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.BottomCenter).then(fadesWhileScrubbing)) {
        val stripIndex = navigation.originalPosts.indexOfFirst { it.id == frame.posts.getOrNull(postPagerState.currentPage)?.id }
            .takeIf { it >= 0 } ?: navigation.stripAnchorIndex
        ViewerBottomChrome(
            timelinePresent = selection.isAnimated,
            pageCount = selection.mediaItems.size,
            pageIndex = selection.mediaIndex,
        ) {
            ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
                pendingPostId.value = navigation.originalPosts[index].id
            }, Modifier.testTag("viewer-post-navigation-strip"),
                onLoadMore = loadMoreFromStrip, onInteraction = { controls.markInteraction() })
        }
    }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.TopCenter).then(fadesWhileScrubbing)) {
        ViewerTopBar(frame, controls, activity, quality, qualityHeight, selection.post.id in likedPostIds, onAction)
    }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.TopEnd).padding(top = 72.dp).then(fadesWhileScrubbing)) {
        ViewerRelatedStrip(
            state = frame.uiState.related.state, posts = frame.uiState.related.posts, currentPostId = selection.post.id,
            onSelect = { post ->
                controls.markInteraction()
                if (post.id != selection.post.id) {
                    pendingPostId.value = post.id
                    onIncludeRelatedPosts()
                }
            },
            onDismiss = { onDismissRelatedPosts(); controls.markInteraction() },
            onRetry = { onRetryRelatedPosts(); controls.markInteraction() },
            enabled = !swipe.settling && !swipe.dragging,
            onExpandedChange = { screen.relatedStripExpanded = it },
            onInteraction = { controls.markInteraction() },
        )
    }
}

@Composable
private fun ViewerTopBar(
    frame: ViewerFrame,
    controls: ViewerControls,
    activity: MainActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    liked: Boolean,
    onAction: (ViewerAction) -> Unit,
) {
    val uiState = frame.uiState
    val selection = frame.selection
    val playbackAvailable = uiState.controls.playback.available
    ViewerChrome(
        onPictureInPicture = viewerPictureInPictureAction(activity, selection.post, selection.media, uiState.mediaError != null),
        playbackMode = uiState.controls.playbackMode,
        onPlaybackModeSelected = { onAction(ViewerAction.SetPlaybackMode(it)) },
        quality = quality.value,
        qualityHeight = qualityHeight.value,
        videoVariants = uiState.currentMedia?.ref?.videoVariants.orEmpty(),
        onQualitySelected = { selected, height -> quality.value = selected; qualityHeight.value = height },
        source = selection.post.id.source.displayName(),
        indexLabel = if (selection.mediaItems.size > 1) "${selection.mediaIndex + 1} / ${selection.mediaItems.size}" else null,
        onBack = { controls.requestDismiss() },
        liked = liked,
        onToggleLike = {
            onAction(ViewerAction.ToggleLike)
            controls.markInteraction()
        },
        actionsMenuExpanded = uiState.controls.actionsMenuVisible,
        onActionsMenuExpandedChange = { expanded ->
            controls.setActionsMenuExpanded(expanded)
            if (expanded) {
                controls.setPlaybackSettingsExpanded(false)
                controls.markInteraction()
            }
        },
        playbackSettingsExpanded = uiState.controls.playbackSettingsVisible,
        onPlaybackSettingsExpandedChange = { expanded ->
            controls.setPlaybackSettingsExpanded(expanded && playbackAvailable)
            if (expanded && playbackAvailable) {
                controls.setActionsMenuExpanded(false)
                controls.markInteraction()
            }
        },
        playbackSettingsEnabled = playbackAvailable,
        playbackRateEnabled = selection.isSeekable,
        playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate),
        onPlaybackRateSelected = { selectedRate ->
            onAction(ViewerAction.SetPlaybackRate(selectedRate.speed))
            controls.markInteraction()
        },
        mediaOverviewAvailable = selection.overviewAvailable,
        mediaOverviewVisible = frame.mediaOverviewVisible,
        onToggleMediaOverview = {
            if (selection.overviewAvailable) {
                controls.setOverviewVisible(!frame.mediaOverviewVisible)
                controls.setActionsMenuExpanded(false)
                controls.setPlaybackSettingsExpanded(false)
                controls.markInteraction()
            }
        },
        onInfo = {
            controls.setActionsMenuExpanded(false)
            controls.setInfoSheetVisible(true)
            controls.markInteraction()
        },
    )
}

@Composable
private fun ViewerInfoSheet(
    selection: ViewerSelection,
    controls: ViewerControls,
    creatorBrowsingSources: Set<SourceKey>,
    saveToCodexQuery: Query?,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    onAction: (ViewerAction) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)?,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
) {
    val post = selection.post
    PreloadCodexSaveRelevance(post, saveToCodexQuery)
    AdaptiveViewerInfo(onDismiss = { controls.setInfoSheetVisible(false) }) {
        PostTagActionSection(
            header = {
                ViewerInfoActions(selection, controls, creatorBrowsingSources, onAction, onOpenInBrowser, onGoToSearch, onOpenCreatorFallback)
            },
            post = post,
            tagVideoCountProvider = tagVideoCountProvider,
            fetchTagVideoCounts = fetchTagVideoCounts,
            onAddIncludeTerm = { term ->
                onAction(ViewerAction.IncludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onAddExcludeTerm = { term ->
                onAction(ViewerAction.ExcludeTag(PostTaxonomyTerm(term.value, term.facet, term.sourceNamespace)))
                true
            },
            onRemoveIncludeTerm = { term -> onRemoveIncludeTerm(post, term) },
            onRemoveExcludeTerm = { term -> onRemoveExcludeTerm(post, term) },
            onFavoriteTagLongPress = onFavoriteTagLongPress,
        )
    }
}

@Composable
private fun ViewerInfoActions(
    selection: ViewerSelection,
    controls: ViewerControls,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
) {
    val post = selection.post
    Column {
        PostActionHeader(post, showPostId = true)
        ActionMenuRow(Icons.Default.BookmarkAdd, "Save to Codex") {
            onAction(ViewerAction.Save)
            controls.setInfoSheetVisible(false)
        }
        ActionMenuRow(Icons.Default.Download, "Save to device", selection.canDownload) {
            onAction(ViewerAction.Download)
            controls.setInfoSheetVisible(false)
        }
        ActionMenuDivider()
        if (onOpenCreatorFallback != null) {
            PostCreatorRows(
                post = post,
                creatorBrowsingSources = creatorBrowsingSources,
                onOpenProfile = { profile ->
                    controls.setPlaybackEnabled(false)
                    onAction(ViewerAction.OpenCreator(profile))
                    controls.setInfoSheetVisible(false)
                },
                onOpenLegacyPost = {
                    controls.setPlaybackEnabled(false)
                    onOpenCreatorFallback(post)
                    controls.setInfoSheetVisible(false)
                },
            )
        }
        ActionMenuRow(Icons.Default.Search, "Go to Search") {
            controls.setPlaybackEnabled(false)
            onGoToSearch()
            controls.setInfoSheetVisible(false)
        }
        ActionMenuRow(Icons.Default.Share, "Share") {
            onAction(ViewerAction.Share)
            controls.setInfoSheetVisible(false)
        }
        if (!post.pageUrl.isNullOrBlank()) {
            ActionMenuRow(Icons.Default.OpenInBrowser, "Open in browser") {
                onOpenInBrowser(post)
                controls.setInfoSheetVisible(false)
            }
        }
        ActionMenuDivider()
    }
}

/** Viewer controls fade rather than popping over the media they share the screen with. */
@Composable
internal fun ViewerChromeVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) { content() }
}

internal const val VIEWER_CHROME_STAGE_TOP_BAR = 1
internal const val VIEWER_CHROME_STAGE_TIMELINE = 2
internal const val VIEWER_CHROME_STAGE_STRIPS = 3

/**
 * How much of the chrome may compose: parts arrive one frame apart, so showing it costs several
 * short frames instead of one long one while video plays. Hiding is immediate.
 */
@Composable
internal fun rememberViewerChromeStage(visible: Boolean): Int {
    var stage by remember { mutableIntStateOf(if (visible) VIEWER_CHROME_STAGE_STRIPS else 0) }
    LaunchedEffect(visible) {
        if (!visible) stage = 0
        while (visible && stage < VIEWER_CHROME_STAGE_STRIPS) {
            withFrameNanos { }
            stage += 1
        }
    }
    return if (visible) stage else 0
}

private val restingReturnProgress = { 0f }
private val VIEWER_BACK_DISTANCE = 48.dp
private const val VIEWER_DISMISS_DIM = 0.7f
