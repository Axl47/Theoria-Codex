package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.isAuthoritativeDurationMedia
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The swipeable post pager, under the screen-wide gesture recognizer. */
@Composable
internal fun ViewerPostPagerSurface(
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
    val post = frame.posts[postPage]
    val pageState = frame.navigation.pages[postPage]
    val postMedia = remember(post) { postMediaItems(post) }
    val initialMediaPage = pageState.selectedMediaIndex.coerceIn(0, postMedia.lastIndex)
    val mediaPagerState = rememberPagerState(initialPage = initialMediaPage, pageCount = { postMedia.size })
    DisposableEffect(post.id, mediaPagerState) {
        screen.mediaPagers[post.id] = mediaPagerState
        onDispose { if (screen.mediaPagers[post.id] === mediaPagerState) screen.mediaPagers.remove(post.id) }
    }
    ViewerPostPageEffects(postPage, post, pageState, postMedia.size, initialMediaPage, mediaPagerState, frame, postPagerState, screen, controls, swipe, onAction, onVisibleMediaChanged)
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
        val active = postPage == frame.navigation.currentIndex && mediaPage == frame.selection.mediaIndex
        ViewerMediaPage(
            post = post,
            media = media,
            returnProgress = if (active) readReturnProgress else restingReturnProgress,
            dismissDrag = dismissDrag.takeIf { active },
            mediaIndex = mediaPage,
            controls = viewerRendererControls(frame, active, swipe.loadTarget == ViewerHorizontalSwipeTarget(postPage, mediaPage), loadGeneration),
            events = viewerRendererEvents(
                session = frame.uiState.session, key = key, generation = loadGeneration,
                onAction = onAction,
                onTimelineInteractionActiveChanged = { controls.onTimelineInteractionChanged(it) },
                onDurationKnown = { duration -> reportAuthoritativeDuration(post, media, duration, onAuthoritativeDurationKnown) },
            ),
            transform = viewerTransform.value,
            onTransformChanged = { viewerTransform.value = it },
            layout = ViewerMediaLayout(frame.isLandscape, frame.inPictureInPicture, frame.touchExplorationEnabled),
            videoOptions = ViewerVideoOptions(quality, qualityHeight, pageState.terminalResolutionMessage(), playbackDiagnosticsEnabled),
            videoPositions = videoPositions,
            levelControls = levelControls,
            mediaError = frame.uiState.mediaError.takeIf { active }?.takeIf { it.mediaKey == key },
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

private fun reportAuthoritativeDuration(post: Post, media: ImageRef, duration: Long, report: (Post, Long) -> Unit) {
    if (isPixivUgoiraMedia(post, media) || isAuthoritativeDurationMedia(post, media)) report(post, duration)
}

private fun ViewerPageState.terminalResolutionMessage(): String? = resolution
    .takeIf { it.status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED && !it.recoverable }
    ?.message

private fun viewerRendererControls(frame: ViewerFrame, active: Boolean, preloading: Boolean, loadGeneration: Long): ViewerRendererControls {
    val controls = frame.uiState.controls
    return ViewerRendererControls(
        isActive = active,
        isPlaying = controls.playback.playing,
        isPreloading = preloading,
        showControls = frame.chromeStage >= VIEWER_CHROME_STAGE_TIMELINE && active,
        showPassiveProgress = !frame.chromeVisible && active && !frame.inPictureInPicture,
        loopPlayback = controls.playbackMode == com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP,
        playbackRate = closestViewerPlaybackRate(controls.playback.playbackRate).speed,
        restartRequest = controls.playback.restartRequest,
        loadGeneration = loadGeneration,
        systemControlsReady = !frame.opening,
    )
}

/** Keeps one post's media pager and the selection in step; their order and keys are load-bearing. */
@Composable
private fun ViewerPostPageEffects(
    postPage: Int,
    post: Post,
    pageState: ViewerPageState,
    mediaCount: Int,
    initialMediaPage: Int,
    mediaPagerState: PagerState,
    frame: ViewerFrame,
    postPagerState: PagerState,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    onAction: (ViewerAction) -> Unit,
    onVisibleMediaChanged: ((Post, Int) -> Unit)?,
) {
    val currentPostIndex = frame.navigation.currentIndex
    val selectedMediaIndex = frame.selection.mediaIndex
    val isCurrentPost = postPage == currentPostIndex
    val swipeActive = swipe.settling || swipe.dragging
    LaunchedEffect(initialMediaPage, mediaCount, swipeActive) {
        if (!swipeActive && mediaPagerState.currentPage != initialMediaPage) {
            mediaPagerState.scrollToPage(initialMediaPage)
        }
    }
    val pendingMediaJump = screen.pendingMediaJumpByPost[post.id]
    LaunchedEffect(pendingMediaJump, mediaCount, pageState.pendingMediaIndex) {
        if (pendingMediaJump != null && pageState.pendingMediaIndex == null) {
            jumpToPendingMedia(pendingMediaJump, mediaCount, mediaPagerState, isCurrentPost, selectedMediaIndex, onAction)
            screen.pendingMediaJumpByPost.remove(post.id)
        }
    }
    LaunchedEffect(mediaPagerState.settledPage, currentPostIndex, pageState.pendingMediaIndex, swipe.dragging, swipe.settling) {
        if (swipeActive || pageState.pendingMediaIndex != null) return@LaunchedEffect
        reportSettledMedia(isCurrentPost, mediaPagerState.settledPage, selectedMediaIndex, post, onAction, onVisibleMediaChanged)
        if (postPage == postPagerState.currentPage) controls.markInteraction()
    }
}

private suspend fun jumpToPendingMedia(
    pendingMediaJump: Int,
    mediaCount: Int,
    mediaPagerState: PagerState,
    isCurrentPost: Boolean,
    selectedMediaIndex: Int,
    onAction: (ViewerAction) -> Unit,
) {
    val targetPage = pendingMediaJump.coerceIn(0, mediaCount - 1)
    mediaPagerState.scrollToPage(targetPage)
    if (isCurrentPost && selectedMediaIndex != targetPage) onAction(ViewerAction.SelectMedia(targetPage))
}

private fun reportSettledMedia(
    isCurrentPost: Boolean,
    settledPage: Int,
    selectedMediaIndex: Int,
    post: Post,
    onAction: (ViewerAction) -> Unit,
    onVisibleMediaChanged: ((Post, Int) -> Unit)?,
) {
    if (!isCurrentPost) return
    if (settledPage != selectedMediaIndex) onAction(ViewerAction.SelectMedia(settledPage))
    onVisibleMediaChanged?.invoke(post, settledPage + 1)
}

private val restingReturnProgress = { 0f }
