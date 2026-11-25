package com.theoriacodex.app.viewer

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import android.os.SystemClock
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
import com.theoriacodex.app.viewer.state.dwellApplies
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
            screen.trailForks.busy != null || screen.trailForks.chromeHolds > 0,
        touchExplorationEnabled = frame.touchExplorationEnabled,
        onHide = { onAction(ViewerAction.ToggleChrome) },
    )
    LaunchedEffect(frame.chromeVisible, frame.selection.isSeekable) {
        if (!frame.chromeVisible || !frame.selection.isSeekable) screen.timelineInteractionActive = false
    }
    ViewerUnlockPeekEffect(frame, screen)
    ViewerDwellEffect(frame, screen, onAction)
    ViewerPendingPostEffect(frame, screen, swipe, postPagerState, pendingPostId, config)
}

@Composable
internal fun viewerKeepsScreenOn(frame: ViewerFrame, screen: ViewerScreenState): Boolean =
    viewerKeepsScreenOn(
        slideshow = frame.uiState.controls.slideshow,
        reading = frame.reading,
        videoPlaying = screen.playingVideos.held.intValue > 0,
        loopingClipPlaying = screen.playingVideos.looping.intValue > 0,
        activity = viewerActivity(frame, screen),
    )

internal fun viewerActivity(frame: ViewerFrame, screen: ViewerScreenState): () -> Any =
    { listOf(screen.touchSerial, screen.interactionSerial, frame.selection.post.id, frame.selection.mediaIndex) }

@Composable
private fun ViewerDwellEffect(frame: ViewerFrame, screen: ViewerScreenState, onAction: (ViewerAction) -> Unit) {
    val dwellApplies = frame.uiState.dwellApplies
    val activity by rememberUpdatedState(viewerActivity(frame, screen))
    val dispatch by rememberUpdatedState(onAction)
    LaunchedEffect(dwellApplies) {
        if (!dwellApplies) return@LaunchedEffect
        snapshotFlow { activity() }.collect {
            dispatch(ViewerAction.RestartDwell(SystemClock.uptimeMillis(), interacting = screen.touching))
        }
    }
}

@Stable
internal class ViewerPlayingVideos {
    val held = mutableIntStateOf(0)
    val looping = mutableIntStateOf(0)
}

internal val LocalViewerPlayingVideos = staticCompositionLocalOf<ViewerPlayingVideos?> { null }

@Composable
internal fun CountViewerPlayingVideo(playing: Boolean, looping: Boolean) {
    val count = LocalViewerPlayingVideos.current?.let { if (looping) it.looping else it.held }
    DisposableEffect(count, playing) {
        if (playing) count?.let { it.intValue += 1 }
        onDispose { if (playing) count?.let { it.intValue -= 1 } }
    }
}

@Composable
internal fun viewerKeepsScreenOn(
    slideshow: Boolean,
    reading: Boolean,
    videoPlaying: Boolean,
    loopingClipPlaying: Boolean,
    activity: () -> Any,
): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val readingHeld = reading && resumed
    val clipHeld = loopingClipPlaying && visible
    var idle by remember { mutableStateOf(false) }
    val currentActivity by rememberUpdatedState(activity)
    LaunchedEffect(readingHeld, visible) {
        idle = false
        if (!visible) return@LaunchedEffect
        snapshotFlow { currentActivity() }.collectLatest {
            idle = false
            delay(VIEWER_IDLE_RELEASE_MS)
            idle = true
        }
    }
    return visible && videoPlaying || (readingHeld || clipHeld) && !idle || resumed && slideshow
}

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
            when {
                !committed -> scope.launch { dismissDrag.recoil() }
                screen.pendingDismiss -> screen.heldBack = true
                else -> controls.requestDismiss()
            }
        }
    }
}

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
    val swipeActive = swipe.settling || swipe.dragging
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState, swipeActive) {
        if (!swipeActive && postPagerState.currentPage != currentPostIndex) {
            postPagerState.scrollToPage(currentPostIndex)
        }
    }
    LaunchedEffect(uiState.currentPageIndex, posts.size, postPagerState) {
        controls.markPassiveInteraction()
    }
    LaunchedEffect(frame.selection.post.id, currentPostIndex, uiState.currentPage?.pendingMediaIndex) {
        if (uiState.currentPage?.pendingMediaIndex == null) {
            onVisiblePostChanged?.invoke(frame.selection.post, frame.selection.mediaIndex + 1)
        }
    }
}

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

internal const val VIEWER_IDLE_RELEASE_MS = 10 * 60_000L
