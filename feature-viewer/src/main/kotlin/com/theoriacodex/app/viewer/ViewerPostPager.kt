package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.loopsPlayback
import com.theoriacodex.app.viewer.state.ViewerEpisodeChoice
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerResolutionState
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    videoPositions: ViewerVideoPositions,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val selection = frame.selection
    val zoomed by remember(viewerTransform) { derivedStateOf { viewerTransform.value.isZoomed } }
    val vertical = config.readingModeOf(frame.uiState.currentPage, frame.uiState.series) == GalleryReadingMode.VERTICAL
    val verticalGallery = screen.verticalGalleries[selection.post.id]?.takeIf { vertical }
    val layoutZoomed = if (vertical) verticalGallery?.zoomed == true else zoomed
    val reservedBottom = if (selection.isAnimated && frame.chromeVisible) {
        with(LocalDensity.current) { (VIEWER_TIMELINE_HEIGHT + LocalViewerTimelineInset.current).toPx() }
    } else 0f
    Box(
        modifier.fillMaxSize()
            .viewerGestures(
                ViewerGestureConfig(
                    enabled = !layoutZoomed && !frame.inPictureInPicture && !frame.touchExplorationEnabled &&
                        !screen.pendingDismiss && !frame.returning && !frame.opening && !frame.locked,
                    levels = levelControls,
                    volumeAdjustable = selection.media?.let(::isVideoMediaRef) == true,
                    holdScrub = LocalViewerHoldScrub.current,
                    reservedBottomPx = reservedBottom,
                    verticalScroll = verticalGallery?.scrollGesture(pagesPending = frame.uiState.currentPage?.hasPendingGalleryMedia() == true),
                ),
                viewerGestureHandler(
                    swipe, dismissDrag, screen, controls, haptics, flingVelocity, scope, frame.uiState.controls.slideshow,
                    openNextChapter = nextPostOpener(frame.navigation.currentIndex, frame, swipe, config).takeIf { vertical && frame.reading },
                ),
            )
            .graphicsLayer { translationX = swipe.edgeOffset },
    ) {
        key(postPagerState) {
            HorizontalPager(
                state = postPagerState,
                key = { index -> encodeViewerPostId(frame.posts[index].id) },
                userScrollEnabled = false,
                reverseLayout = config.readingModeOf(frame.uiState.currentPage, frame.uiState.series).reversed,
                modifier = Modifier.fillMaxSize(),
            ) { postPage ->
                Box(Modifier.fillMaxSize()) {
                    ViewerPostPage(
                        postPage, frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls, readReturnProgress, quality,
                        qualityHeight, videoPositions, host, config,
                    )
                    if (frame.reading && frame.navigation.pages[postPage].loadingGalleryMedia()) ChapterPagesLoading()
                }
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
    slideshowRunning: Boolean,
    openNextChapter: (() -> Unit)?,
) = object : ViewerGestureHandler {
    override fun onPress() {
        if (slideshowRunning) controls.stopSlideshow()
        swipe.press()
    }
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
    override fun onInfoSwipe() {
        if (openNextChapter != null) return openNextChapter()
        controls.setInfoSheetVisible(true)
        controls.markInteraction()
    }
    override fun onGestureEnd() {
        swipe.cancel()
        if (dismissDrag.active && !screen.pendingDismiss) scope.launch { dismissDrag.recoil() }
    }
    override fun onLevelLimit() = haptics.performHapticFeedback(TheoriaHaptics.Limit)
    override fun onScrubStart() = controls.beginScrub()
    override fun onScrubEnd() = controls.endScrub()
}

@Composable
private fun BoxScope.ChapterPagesLoading() {
    CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = "Loading pages" })
}

internal fun previousPostOpener(index: Int, frame: ViewerFrame, swipe: ViewerSwipeController, config: ViewerScreenConfig): (() -> Unit)? {
    val previous = frame.navigation.pages.getOrNull(index - 1) ?: return null
    return { swipe.navigateTo(ViewerHorizontalSwipeTarget(index - 1, config.entryMediaIndex(previous, frame.uiState.series, back = true))) }
}

internal fun nextPostOpener(index: Int, frame: ViewerFrame, swipe: ViewerSwipeController, config: ViewerScreenConfig): (() -> Unit)? {
    val next = frame.navigation.pages.getOrNull(index + 1) ?: return null
    return { swipe.navigateTo(ViewerHorizontalSwipeTarget(index + 1, config.entryMediaIndex(next, frame.uiState.series))) }
}

@Composable
private fun ViewerVerticalPostPage(
    postPage: Int,
    pageState: ViewerPageState,
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    readReturnProgress: () -> Float,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
) {
    ViewerVerticalGallery(
        postPage, pageState, frame, screen, controls, host,
        dismissDrag = dismissDrag.takeIf { postPage == frame.navigation.currentIndex },
        readReturnProgress = readReturnProgress,
        onOpenNext = nextPostOpener(postPage, frame, swipe, config).takeUnless { pageState.hasPendingGalleryMedia() },
    )
}

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
    videoPositions: ViewerVideoPositions,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
) {
    val post = frame.posts[postPage]
    val pageState = frame.navigation.pages[postPage]
    if (config.readingModeOf(pageState, frame.uiState.series) == GalleryReadingMode.VERTICAL) {
        return ViewerVerticalPostPage(postPage, pageState, frame, screen, controls, swipe, dismissDrag, readReturnProgress, host, config)
    }
    val postMedia = pageState.media
    val knownDurationMs by remember(post, host.durations) { host.durations.observe(post) }.collectAsStateWithLifecycle(null)
    val initialMediaPage = pageState.selectedMediaIndex.coerceIn(0, postMedia.lastIndex)
    val mediaPagerState = rememberPagerState(initialPage = initialMediaPage, pageCount = { postMedia.size })
    DisposableEffect(post.id, mediaPagerState) {
        screen.mediaPagers[post.id] = mediaPagerState
        onDispose { if (screen.mediaPagers[post.id] === mediaPagerState) screen.mediaPagers.remove(post.id) }
    }
    ViewerPostPageEffects(postPage, post, pageState, postMedia.size, initialMediaPage, mediaPagerState, frame, postPagerState, screen, controls, swipe, host.onAction, host.onVisibleMediaChanged)
    HorizontalPager(
        state = mediaPagerState,
        userScrollEnabled = false,
        reverseLayout = config.readingModeOf(pageState, frame.uiState.series).reversed,
        modifier = Modifier.fillMaxSize(),
    ) { mediaPage ->
        val media = postMedia[mediaPage].ref
        val loadGeneration = pageState.media.getOrNull(mediaPage)?.loadGeneration ?: 0L
        val key = ViewerMediaKey(post.id, mediaPage)
        val active = postPage == frame.navigation.currentIndex && mediaPage == frame.selection.mediaIndex
        ViewerMediaPage(
            post = post,
            media = media,
            returnProgress = if (active) readReturnProgress else restingReturnProgress,
            dismissDrag = dismissDrag.takeIf { active },
            mediaIndex = mediaPage,
            controls = viewerRendererControls(
                frame, active, swipe.loadTarget == ViewerHorizontalSwipeTarget(postPage, mediaPage), loadGeneration,
                expectedDurationMs = knownDurationMs?.takeIf { carriesPostDuration(post, media) },
            ),
            events = host.rememberPageEvents(frame, key, loadGeneration, controls, post, media),
            transform = viewerTransform,
            onTransformChanged = { viewerTransform.value = it },
            layout = ViewerMediaLayout(frame.mediaEdgeToEdge, frame.inPictureInPicture, frame.touchExplorationEnabled),
            videoOptions = host.rememberVideoOptions(key, pageState.resolution, frame.uiState.controls.episode, frame.uiState.session, screen, quality, qualityHeight, config.playbackDiagnosticsEnabled),
            videoPositions = videoPositions,
            levelControls = levelControls,
            mediaError = frame.uiState.mediaError.takeIf { active }?.takeIf { it.mediaKey == key },
            pixivUgoiraClient = config.pixivUgoiraClient,
            onAction = host.onAction,
            onInteraction = { controls.markInteraction() },
            onPassiveInteraction = { controls.markPassiveInteraction() },
            onToggleChrome = { controls.toggleChrome() },
            imageSession = screen.imageSession,
            onRequestMediaRecovery = host.onRequestMediaRecovery,
        )
    }
}

@Composable
private fun ViewerScreenHost.rememberPageEvents(
    frame: ViewerFrame,
    key: ViewerMediaKey,
    loadGeneration: Long,
    controls: ViewerControls,
    post: Post,
    media: ImageRef,
): ViewerRendererEvents {
    val session = frame.uiState.session
    val series = frame.uiState.series
    return remember(session, key, loadGeneration, this, controls, post, media, series) {
        viewerRendererEvents(
            session = session, key = key, generation = loadGeneration,
            onAction = onAction, onPlaybackProgress = episodeProgress(series, post),
            onTimelineInteractionActiveChanged = { controls.onTimelineInteractionChanged(it) },
            onDurationKnown = { duration -> if (carriesPostDuration(post, media)) durations.onPlayerDuration(post, duration) },
        )
    }
}

@Composable
private fun ViewerScreenHost.rememberVideoOptions(
    key: ViewerMediaKey,
    resolution: ViewerResolutionState,
    episode: ViewerEpisodeChoice,
    session: ViewerSessionIdentity?,
    screen: ViewerScreenState,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    diagnosticsEnabled: Boolean,
): ViewerVideoOptions = remember(key, resolution, episode, session, screen, quality, qualityHeight, diagnosticsEnabled, this) {
    ViewerVideoOptions(
        quality, qualityHeight, episode.streamLabelFor(key), resolution.terminalMessage(), diagnosticsEnabled,
        ViewerEpisodeOptions(screen.streamAttempts.forSession(session), episode.pickedStream(key), resolution.resolvedAtMs) { label ->
            onAction(ViewerAction.FallBackToStream(key, label))
        },
    )
}

internal fun ViewerScreenHost.episodeProgress(series: ViewerSeries?, post: Post): ((ViewerPlaybackProgress) -> Unit)? =
    onPlaybackProgress?.takeIf { series is ViewerSeries.Watching }?.let { report -> { progress -> report(post, progress) } }

private fun ViewerResolutionState.terminalMessage(): String? = takeIf { it.status == com.theoriacodex.app.viewer.state.ViewerResolutionStatus.FAILED && !it.recoverable }
    ?.message

private fun viewerRendererControls(
    frame: ViewerFrame, active: Boolean, preloading: Boolean, loadGeneration: Long, expectedDurationMs: Long?,
): ViewerRendererControls {
    val controls = frame.uiState.controls
    val watching = frame.watching
    return ViewerRendererControls(
        isActive = active,
        isPlaying = controls.playback.playing,
        isPreloading = preloading,
        showControls = (active || preloading) && frame.chromeStage.value >= VIEWER_CHROME_STAGE_TIMELINE,
        showPassiveProgress = active && !frame.inPictureInPicture && !frame.chromeVisible,
        loopPlayback = frame.uiState.loopsPlayback,
        playbackRate = closestViewerPlaybackRate(controls.playback.playbackRate).speed,
        restartRequest = controls.playback.restartRequest,
        loadGeneration = loadGeneration,
        systemControlsReady = frame.systemControlsReady,
        expectedDurationMs = expectedDurationMs,
        showLeadingPlaybackButton = !watching,
        showSpeedChip = !watching,
        dimBackdrop = watching,
        fillScreen = watching && controls.fillScreen && frame.isLandscape && !frame.inPictureInPicture,
    )
}

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
    LaunchedEffect(mediaPagerState.settledPage, currentPostIndex, pageState.pendingMediaIndex, swipe.dragging, swipe.settling, mediaCount) {
        if (swipeActive || pageState.pendingMediaIndex != null) return@LaunchedEffect
        reportSettledMedia(isCurrentPost, mediaPagerState.settledPage, selectedMediaIndex, post, onAction, onVisibleMediaChanged)
        if (postPage == postPagerState.currentPage) controls.markPassiveInteraction()
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
