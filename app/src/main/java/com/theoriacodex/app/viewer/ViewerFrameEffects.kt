package com.theoriacodex.app.viewer

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun rememberViewerSwipe(
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
    val swipeInProgress = swipe.settling
    LaunchedEffect(pendingPostId.value, frame.posts.size, swipeInProgress, frame.opening, frame.uiState.currentPageIndex) {
        advanceToPendingPost(frame, screen, swipe, swipeInProgress, postPagerState, pendingPostId)
    }
}

private fun advanceToPendingPost(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    swipe: ViewerSwipeController,
    swipeInProgress: Boolean,
    postPagerState: PagerState,
    pendingPostId: MutableState<PostId?>,
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
    swipe.navigateTo(ViewerHorizontalSwipeTarget(index, targetMediaIndex(frame.navigation.pages[index])))
}

private val VIEWER_BACK_DISTANCE = 48.dp
