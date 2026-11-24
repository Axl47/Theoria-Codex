package com.theoriacodex.app.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.GalleryReadingMode
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    uiState: ViewerUiState,
    host: ViewerScreenHost,
    config: ViewerScreenConfig = ViewerScreenConfig(),
    saveToCodexQuery: Query? = null,
    saveFromForYou: Boolean = false,
    canLoadMoreFromSource: Boolean = false,
    loadingMoreFromSource: Boolean = false,
    contextActions: ViewerContextActions = ViewerContextActions(),
    relatedShelf: ViewerRelatedShelf = ViewerRelatedShelf.None,
    comments: ViewerComments = ViewerComments.None,
    trail: ViewerTrail = ViewerTrail.None,
) {
    val returnProgress = rememberViewerReturnProgress()
    val returning = LocalViewerReturnTransition.current?.request != null
    val opening by remember(returning, returnProgress) { derivedStateOf { !returning && returnProgress.value > 0f } }
    val dismissDrag = remember { ViewerDismissDrag() }
    val activity = LocalContext.current.viewerActivity()
    val quality = rememberViewerVideoQuality(config.videoQuality, host.onVideoQualitySelected)
    val qualityHeight = remember(uiState.currentMedia?.key) { mutableStateOf<Int?>(null) }
    val session = rememberPresentedSession(uiState.session)
    val pendingPostId = remember(session) { mutableStateOf<PostId?>(null) }
    val navigation = remember(uiState.pages, uiState.currentPageIndex, uiState.related.navigation, uiState.related.extraPostIds, pendingPostId.value) {
        viewerPostNavigation(uiState, pendingPostId.value)
    }
    val posts = remember(navigation.pages) { navigation.pages.map { it.post } }
    val videoPositions = rememberViewerVideoPositions(session, uiState.series)
    if (posts.isEmpty()) {
        ViewerNoPosts()
        return
    }
    CompositionLocalProvider(
        LocalViewerInstantSeek provides config.instantSeek,
        LocalViewerAutoRotateVideos provides config.autoRotateVideos,
    ) {
        ViewerLoadedScreen(
            uiState, session, navigation, posts, returnProgress, returning, opening, dismissDrag, activity, quality, qualityHeight, pendingPostId,
            videoPositions, host, config, ViewerSaveContext(saveToCodexQuery, saveFromForYou), canLoadMoreFromSource, loadingMoreFromSource, contextActions,
            relatedShelf, comments, trail,
        )
    }
}

@Composable
private fun rememberPresentedSession(current: ViewerSessionIdentity?): ViewerSessionIdentity? {
    val presented = remember { object { var last = current } }
    if (current != null) presented.last = current
    return presented.last
}

@Composable
private fun ViewerLoadedScreen(
    uiState: ViewerUiState,
    session: ViewerSessionIdentity?,
    navigation: ViewerPostNavigation,
    posts: List<Post>,
    returnProgress: State<Float>,
    returning: Boolean,
    opening: Boolean,
    dismissDrag: ViewerDismissDrag,
    activity: ViewerHostActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    pendingPostId: MutableState<PostId?>,
    videoPositions: ViewerVideoPositions,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    saveContext: ViewerSaveContext,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    contextActions: ViewerContextActions,
    relatedShelf: ViewerRelatedShelf,
    comments: ViewerComments,
    trail: ViewerTrail,
) {
    val onAction = host.onAction
    val readReturnProgress = remember(returnProgress) { { returnProgress.value } }
    val inPictureInPicture = activity?.viewerPictureInPictureActive == true
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val screen = remember { ViewerScreenState() }
    DisposableEffect(screen) { onDispose { screen.trailForks.close() } }
    val postPagerState = key(session, navigation.branchAnchor) {
        rememberPagerState(initialPage = navigation.currentIndex, pageCount = { posts.size })
    }
    LaunchedEffect(posts, navigation.currentIndex, videoPositions) { videoPositions.retainAround(posts.map { it.id }, navigation.currentIndex) }
    val viewerTransform = remember(session, uiState.currentMedia?.key, uiState.controls.slideshow) { mutableStateOf(ViewerTransformState()) }
    LaunchedEffect(uiState.controls.slideshow) { if (uiState.controls.slideshow) screen.verticalGalleries.values.forEach { it.resetZoom() } }
    val selection = rememberViewerSelection(uiState, navigation.pages[navigation.currentIndex], config.pixivUgoiraClient != null)
    WarmViewerPageThumbnails(selection.post.id, selection.overviewItems, selection.mediaIndex)
    val levelControls = rememberViewerLevelControls(!inPictureInPicture, restoreVolume = selection.media?.let(::isVideoMediaRef) == true && !uiState.overview.visible)
    val holdScrub = remember { ViewerHoldScrub() }
    val touchExplorationEnabled = rememberViewerTouchExploration()
    val chromeVisible = viewerChromeVisible(uiState, touchExplorationEnabled, inPictureInPicture, returning, opening, dismissDrag)
    val chromeStage = rememberViewerChromeStage(chromeVisible)
    val controls = rememberViewerControls(onAction, screen, uiState.overview.visible, chromeVisible, opening, returning)
    val frame = remember(uiState, navigation, posts, selection, chromeVisible, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape) {
        ViewerFrame(uiState, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape)
    }
    val flingVelocity = with(LocalDensity.current) { VIEWER_FLING_VELOCITY.toPx() }
    val swipe = rememberViewerSwipe(frame, postPagerState, config, screen, controls, flingVelocity, scope, onAction)
    val loadMoreFromStrip = rememberViewerLoadMore(navigation.originalPosts.size, canLoadMoreFromSource, loadingMoreFromSource, onAction)
    ViewerFrameEffects(
        frame, screen, controls, swipe, dismissDrag, postPagerState, pendingPostId, canLoadMoreFromSource,
        loadingMoreFromSource, loadMoreFromStrip, onAction, host.onVisiblePostChanged, config,
    )
    SideEffect { screen.activeTracks.choice = uiState.controls.episode }
    val viewerAccent = rememberViewerAccent(selection.post, enabled = !opening && !returning)
    val timelineInset = viewerTimelineInset(chromeVisible, frame.watching)
    val bottomRowHeight = if (frame.watching) VIEWER_EPISODE_CONTROLS_HEIGHT else VIEWER_POST_STRIP_HEIGHT
    val subtitleBottomLift = viewerFooterHeight(selection, chromeVisible, bottomRowHeight, timelineInset)
    CompositionLocalProvider(
        LocalViewerTimelineInset provides timelineInset,
        LocalViewerSubtitleBottomLift provides subtitleBottomLift,
        LocalViewerAccent provides viewerAccent,
        LocalViewerHoldScrub provides holdScrub,
        LocalViewerActiveTracks provides screen.activeTracks.takeIf { frame.watching },
        LocalViewerPlayingVideos provides screen.playingVideos,
    ) {
        ViewerBackdrop(returnProgress, dismissDrag, viewerKeepsScreenOn(frame, screen), onTouch = { screen.touchSerial += 1 }) {
            ViewerContentSurface(
                frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls,
                readReturnProgress, flingVelocity, quality, qualityHeight, videoPositions, host, config, scope,
            )
            ViewerChromeOverlays(
                frame, postPagerState, screen, controls, swipe, activity, quality, qualityHeight, pendingPostId, host, config,
                loadMoreFromStrip, relatedShelf, comments, saveContext, trail,
            )
        }
    }
    if (uiState.controls.panel == ViewerPanel.INFO && !inPictureInPicture) {
        ViewerInfoSheet(selection, controls, host, config, uiState.series, saveContext.query, contextActions, comments)
    }
}

@Composable
private fun viewerTimelineInset(chromeVisible: Boolean, watching: Boolean): androidx.compose.ui.unit.Dp = when {
    !chromeVisible -> 0.dp
    watching -> WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    else -> viewerDockInset()
}

@Composable
private fun ViewerContentSurface(
    frame: ViewerFrame,
    postPagerState: androidx.compose.foundation.pager.PagerState,
    viewerTransform: MutableState<ViewerTransformState>,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    dismissDrag: ViewerDismissDrag,
    levelControls: ViewerLevelControls,
    readReturnProgress: () -> Float,
    flingVelocity: Float,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    videoPositions: ViewerVideoPositions,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (frame.mediaOverviewVisible && frame.selection.overviewAvailable) {
        ViewerOverview(frame, config, controls, host)
    } else {
        ViewerPostPagerSurface(
            frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls, readReturnProgress, flingVelocity,
            quality.value, qualityHeight.value, videoPositions, host, config, scope,
            modifier = if (frame.locked) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

private fun viewerChromeVisible(
    uiState: ViewerUiState,
    touchExplorationEnabled: Boolean,
    inPictureInPicture: Boolean,
    returning: Boolean,
    opening: Boolean,
    dismissDrag: ViewerDismissDrag,
): Boolean {
    val locked = uiState.controls.locked && uiState.series is ViewerSeries.Watching
    val wanted = (uiState.controls.chromeVisible || touchExplorationEnabled) && !locked
    return wanted && !inPictureInPicture && !returning && !opening && !dismissDrag.active
}

@Composable
private fun rememberViewerLoadMore(
    postCount: Int,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    onAction: (ViewerAction) -> Unit,
): () -> Unit {
    val requestedAtSize = remember(postCount) { mutableIntStateOf(-1) }
    return {
        if (!loadingMoreFromSource && canLoadMoreFromSource && requestedAtSize.intValue != postCount) {
            requestedAtSize.intValue = postCount
            onAction(ViewerAction.LoadMore)
        }
    }
}

@Composable
private fun ViewerBackdrop(
    returnProgress: State<Float>,
    dismissDrag: ViewerDismissDrag,
    keepScreenOn: Boolean,
    onTouch: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (keepScreenOn) Modifier.keepScreenOn() else Modifier)
            .pointerInput(onTouch) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onTouch()
                }
            }
            .drawBehind {
                drawRect(Color.Black.copy(alpha = (1f - returnProgress.value) * (1f - VIEWER_DISMISS_DIM * dismissDrag.progress)))
            },
        content = content,
    )
}

@Composable
private fun ViewerOverview(
    frame: ViewerFrame,
    config: ViewerScreenConfig,
    controls: ViewerControls,
    host: ViewerScreenHost,
) {
    val selection = frame.selection
    val page = frame.navigation.pages[frame.navigation.currentIndex]
    val readingModes = ViewerReadingModeChoice(
        selected = config.readingModeOf(page, frame.uiState.series),
        available = GalleryReadingMode.entries.filter { it != GalleryReadingMode.VERTICAL || page.verticalReadingAvailable() },
        onSelect = { mode ->
            host.onGalleryReadingModeSelected(selection.post.id, mode)
            controls.markPassiveInteraction()
        },
    )
    ViewerMediaOverviewGrid(
        post = selection.post,
        items = selection.overviewItems,
        selectedMediaIndex = selection.mediaIndex,
        onMediaSelected = { mediaIndex ->
            controls.jumpToMedia(selection.post.id, mediaIndex)
            controls.setOverviewVisible(false)
        },
        modifier = Modifier.fillMaxSize(),
        readingModes = readingModes,
    )
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

internal const val VIEWER_CHROME_STAGE_TOP_BAR = 1
internal const val VIEWER_CHROME_STAGE_TIMELINE = 2
internal const val VIEWER_CHROME_STAGE_STRIPS = 3

@Composable
internal fun rememberViewerChromeStage(visible: Boolean): State<Int> {
    val revealed = remember { mutableIntStateOf(if (visible) VIEWER_CHROME_STAGE_STRIPS else 0) }
    val currentVisible = rememberUpdatedState(visible)
    LaunchedEffect(visible) {
        if (!visible) revealed.intValue = 0
        while (visible && revealed.intValue < VIEWER_CHROME_STAGE_STRIPS) {
            withFrameNanos { }
            revealed.intValue += 1
        }
    }
    return remember { derivedStateOf { if (currentVisible.value) revealed.intValue else 0 } }
}

private const val VIEWER_DISMISS_DIM = 0.7f
