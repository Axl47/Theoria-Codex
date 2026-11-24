package com.theoriacodex.app.viewer

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun rememberViewerSwipe(
    frame: ViewerFrame,
    postPagerState: PagerState,
    config: ViewerScreenConfig,
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
        target = { origin, direction -> swipeTargetFrom(origin, direction, pages, posts.size, config, frame.uiState.series) },
        swipeSign = { index -> if (config.readingModeOf(pages.getOrNull(index), frame.uiState.series).reversed) -1f else 1f },
        prepareDestination = { target ->
            posts.getOrNull(target.postIndex)?.let { screen.pendingMediaJumpByPost[it.id] = target.mediaIndex }
        },
        commit = { postId, mediaIndex ->
            val index = posts.indexOfFirst { it.id == postId }
            if (index >= 0) onAction(ViewerAction.Navigate(frame.navigation.sourceIndices[index], mediaIndex))
        },
        onInteraction = { controls.markPassiveInteraction() },
        onLimit = { haptics.performHapticFeedback(TheoriaHaptics.Limit) },
        flingVelocity = flingVelocity,
    )
    val swipeContextState = rememberUpdatedState(swipeContext)
    val swipe = remember { ViewerSwipeController(scope, swipeContextState) }
    LaunchedEffect(frame.uiState.session) { swipe.reset() }
    return swipe
}

/** Where a swipe from [origin] lands. A vertical gallery scrolls its own pages, so a swipe always changes post. */
private fun swipeTargetFrom(
    origin: ViewerHorizontalSwipeTarget,
    direction: ViewerHorizontalSwipeDirection,
    pages: List<ViewerPageState>,
    postCount: Int,
    config: ViewerScreenConfig,
    series: ViewerSeries?,
): ViewerHorizontalSwipeTarget? {
    val page = pages.getOrNull(origin.postIndex) ?: return null
    val back = direction != ViewerHorizontalSwipeDirection.Next
    val neighbor = pages.getOrNull(origin.postIndex + if (back) -1 else 1)
    val vertical = config.readingModeOf(page, series) == GalleryReadingMode.VERTICAL
    return viewerHorizontalSwipeTarget(
        currentPostIndex = origin.postIndex,
        currentMediaIndex = if (vertical) 0 else origin.mediaIndex,
        currentMediaCount = if (vertical) 1 else page.media.size,
        postCount = postCount,
        targetPostMediaCount = neighbor?.media?.size ?: 0,
        direction = direction,
        galleryMediaPending = !vertical && page.hasPendingGalleryMedia(),
        targetMediaIndex = neighbor?.let { config.entryMediaIndex(it, series, back) } ?: 0,
    )
}

/** Every effect that follows the frame, in the order they must start. Per-page effects live in the page. */
@Composable
internal fun ViewerFrameEffects(
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
    config: ViewerScreenConfig,
) {
    val uiState = frame.uiState
    val showInfoSheet = uiState.controls.panel == ViewerPanel.INFO && !frame.inPictureInPicture
    ViewerSelectionEffects(frame, screen, controls, swipe.settling, onAction)
    ViewerBackHandlers(frame, screen, controls, dismissDrag)
    ViewerNavigationEffects(
        frame, swipe, postPagerState, controls, canLoadMoreFromSource, loadingMoreFromSource, loadMoreFromStrip, onAction, onVisiblePostChanged,
        config,
    )
    ViewerChromeAutoHideEffect(
        chromeVisible = frame.chromeVisible,
        interactionSerial = screen.interactionSerial,
        interactionBlocked = screen.timelineInteractionActive || frame.mediaOverviewVisible || showInfoSheet ||
            uiState.controls.panel?.inChrome == true || screen.relatedStripExpanded || screen.saveFan.holdsChrome ||
            screen.trailForks.busy != null,
        touchExplorationEnabled = frame.touchExplorationEnabled,
        onHide = { onAction(ViewerAction.ToggleChrome) },
    )
    LaunchedEffect(frame.chromeVisible, frame.selection.isSeekable) {
        if (!frame.chromeVisible || !frame.selection.isSeekable) screen.timelineInteractionActive = false
    }
    ViewerUnlockPeekEffect(frame, screen)
    ViewerPendingPostEffect(frame, screen, swipe, postPagerState, pendingPostId, config)
}

@Composable
internal fun viewerKeepsScreenOn(frame: ViewerFrame, screen: ViewerScreenState): Boolean {
    val videoShown = frame.selection.isVideo && !(frame.mediaOverviewVisible && frame.selection.overviewAvailable)
    return viewerKeepsScreenOn(
        slideshow = frame.uiState.controls.slideshow,
        reading = frame.reading,
        videoPlaying = videoShown && frame.uiState.controls.playback.playing,
        activity = { listOf(screen.touchSerial, screen.interactionSerial, frame.selection.post.id, frame.selection.mediaIndex) },
    )
}

@Composable
internal fun viewerKeepsScreenOn(slideshow: Boolean, reading: Boolean, videoPlaying: Boolean, activity: () -> Any): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var readingIdle by remember { mutableStateOf(false) }
    val currentActivity by rememberUpdatedState(activity)
    LaunchedEffect(reading, resumed) {
        readingIdle = false
        if (!reading || !resumed) return@LaunchedEffect
        snapshotFlow { currentActivity() }.collectLatest {
            readingIdle = false
            delay(VIEWER_READING_IDLE_MS)
            readingIdle = true
        }
    }
    return visible && videoPlaying || resumed && (slideshow || reading && !readingIdle)
}

/** Auto-hides the revealed Unlock button after the accessibility-adjusted timeout; clears immediately when unlocked. */
@Composable
private fun ViewerUnlockPeekEffect(frame: ViewerFrame, screen: ViewerScreenState) {
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(frame.locked) {
        if (!frame.locked) screen.hideUnlock()
    }
    LaunchedEffect(frame.locked, screen.unlockPeekVisible, screen.unlockPeekSerial, frame.touchExplorationEnabled) {
        if (!frame.locked || !screen.unlockPeekVisible || frame.touchExplorationEnabled) return@LaunchedEffect
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            VIEWER_UNLOCK_PEEK_MS, containsIcons = true, containsControls = true,
        ) ?: VIEWER_UNLOCK_PEEK_MS
        delay(timeout)
        screen.hideUnlock()
    }
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
    ViewerHeldBackHandler(holding = screen.pendingDismiss || frame.returning, screen)
    BackHandler(enabled = !screen.pendingDismiss && !frame.returning && frame.locked) {
        screen.revealUnlock()
    }
    BackHandler(enabled = !screen.pendingDismiss && !frame.returning && !frame.locked && frame.mediaOverviewVisible, onBack = controls::back)
    val backDistance = with(LocalDensity.current) { VIEWER_BACK_DISTANCE.toPx() }
    PredictiveBackHandler(enabled = !screen.pendingDismiss && !frame.returning && !frame.locked && !frame.mediaOverviewVisible) { events ->
        var committed = false
        try {
            events.collect { dismissDrag.followBack(it.progress, it.swipeEdge == BackEventCompat.EDGE_LEFT, backDistance) }
            committed = true
        } finally {
            // Cancelling the back swipe cancels this collector, so it recoils in the screen's scope. A second Back can
            // commit here before the frame that enables the hold, so it is held like one the hold caught.
            when {
                !committed -> scope.launch { dismissDrag.recoil() }
                screen.pendingDismiss -> screen.heldBack = true
                else -> controls.requestDismiss()
            }
        }
    }
}

/**
 * Nothing may pop the route underneath a return that is already moving, so a Back pressed while [holding] is kept in
 * [ViewerScreenState.heldBack] and runs when the Viewer leaves composition at the end of its return, so two quick Backs
 * still mean two steps back. At most one held Back runs. A hold that ends while the Viewer stays, such as a new Viewer
 * opening and clearing the return, drops it: the screen on top is no longer the one that Back was meant for.
 *
 * The hold is an overlay: it outranks every ordinary Back handler, including those the screen underneath registers as
 * it returns, and no lifecycle turns it off, since popping the Viewer stops its destination before the return ends.
 */
@Composable
internal fun ViewerHeldBackHandler(holding: Boolean, screen: ViewerScreenState) {
    val owner = LocalOnBackPressedDispatcherOwner.current ?: return
    val events = (owner as? NavigationEventDispatcherOwner)?.navigationEventDispatcher ?: return
    val view = LocalView.current
    val hold = remember(screen) {
        object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, false) {
            override fun onBackCompleted() {
                screen.heldBack = true
            }
        }
    }
    SideEffect { hold.isBackEnabled = holding }
    LaunchedEffect(holding) {
        if (!holding) screen.heldBack = false
    }
    DisposableEffect(events, hold) {
        events.addHandler(hold, NavigationEventDispatcher.PRIORITY_OVERLAY)
        onDispose {
            hold.remove()
            if (!screen.heldBack) return@onDispose
            screen.heldBack = false
            // Posted so every handler leaving with the Viewer is gone first; a Viewer leaving with its activity drops it.
            view.post { if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) owner.onBackPressedDispatcher.onBackPressed() }
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
    config: ViewerScreenConfig,
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
    // A vertical gallery fills the window's width, as its pages' own requests assume (ViewerImageRenderer).
    val windowWidth = LocalWindowInfo.current.containerSize.width
    LaunchedEffect(posts, currentPostIndex, frame.selection.mediaIndex, swipeLoadTarget, windowWidth, config, uiState.series) {
        val targetKey = swipeLoadTarget?.let { target ->
            pages.getOrNull(target.postIndex)?.media?.getOrNull(target.mediaIndex)?.key
        }
        onAction(
            viewerPrefetchAction(pages, uiState.currentMedia?.key, targetKey) { page ->
                windowWidth.takeIf { config.readingModeOf(page, uiState.series) == GalleryReadingMode.VERTICAL }
            },
        )
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
    // Selecting another page already closes the overview in the reducer; a feed page arriving must not.
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState) {
        controls.markPassiveInteraction()
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
    config: ViewerScreenConfig,
) {
    val swipeInProgress = swipe.settling
    LaunchedEffect(pendingPostId.value, frame.posts.size, swipeInProgress, frame.opening, frame.uiState.currentPageIndex) {
        advanceToPendingPost(frame, screen, swipe, swipeInProgress, postPagerState, pendingPostId, config)
    }
}

private fun advanceToPendingPost(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    swipe: ViewerSwipeController,
    swipeInProgress: Boolean,
    postPagerState: PagerState,
    pendingPostId: MutableState<PostId?>,
    config: ViewerScreenConfig,
) {
    val pending = pendingPostId.value ?: return
    if (pending == frame.uiState.currentPage?.post?.id) {
        pendingPostId.value = null
        return
    }
    if (swipeInProgress || frame.opening) return
    val index = frame.posts.indexOfFirst { it.id == pending }
    if (index < 0 || postPagerState.settledPage == index) return
    if (index == frame.navigation.currentIndex) {
        pendingPostId.value = null
        return
    }
    if (screen.pendingDismiss || frame.returning) return
    swipe.navigateTo(ViewerHorizontalSwipeTarget(index, config.entryMediaIndex(frame.navigation.pages[index], frame.uiState.series)))
}

private val VIEWER_BACK_DISTANCE = 48.dp
internal const val VIEWER_UNLOCK_PEEK_MS = 3_000L

internal const val VIEWER_READING_IDLE_MS = 10 * 60_000L
