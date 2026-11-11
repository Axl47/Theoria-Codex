package com.theoriacodex.app.viewer

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.MainActivity
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey

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
    val returning = LocalViewerReturnTransition.current?.request != null
    val opening by remember(returning, returnProgress) { derivedStateOf { !returning && returnProgress.value > 0f } }
    val dismissDrag = remember { ViewerDismissDrag() }
    val activity = LocalContext.current.viewerActivity()
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
    ViewerLoadedScreen(
        uiState, navigation, posts, returnProgress, returning, opening, dismissDrag, activity, quality, qualityHeight, pendingPostId,
        videoPositions, creatorBrowsingSources, onAction, saveToCodexQuery, pixivUgoiraClient, tagVideoCountProvider,
        fetchTagVideoCounts, canLoadMoreFromSource, loadingMoreFromSource, invertMultiImageScrollDirection, likedPostIds,
        onRequestMediaRecovery, onVisiblePostChanged, onVisibleMediaChanged, onAuthoritativeDurationKnown, onOpenInBrowser,
        onRemoveIncludeTerm, onRemoveExcludeTerm, onFavoriteTagLongPress, onGoToSearch, onOpenCreatorFallback,
        playbackDiagnosticsEnabled, onIncludeRelatedPosts, onDismissRelatedPosts, onRetryRelatedPosts,
    )
}

/** The screen once there is at least one post to show. */
@Composable
private fun ViewerLoadedScreen(
    uiState: ViewerUiState,
    navigation: ViewerPostNavigation,
    posts: List<Post>,
    returnProgress: State<Float>,
    returning: Boolean,
    opening: Boolean,
    dismissDrag: ViewerDismissDrag,
    activity: MainActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    pendingPostId: MutableState<PostId?>,
    videoPositions: ViewerVideoPositions,
    creatorBrowsingSources: Set<SourceKey>,
    onAction: (ViewerAction) -> Unit,
    saveToCodexQuery: Query?,
    pixivUgoiraClient: PixivUgoiraClient?,
    tagVideoCountProvider: (SourceKey, String) -> Int?,
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?>,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    invertMultiImageScrollDirection: Boolean,
    likedPostIds: Set<PostId>,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
    onVisiblePostChanged: ((Post, Int) -> Unit)?,
    onVisibleMediaChanged: ((Post, Int) -> Unit)?,
    onAuthoritativeDurationKnown: (Post, Long) -> Unit,
    onOpenInBrowser: (Post) -> Unit,
    onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    onFavoriteTagLongPress: ((SourceKey, String) -> Unit)?,
    onGoToSearch: () -> Unit,
    onOpenCreatorFallback: ((Post) -> Unit)?,
    playbackDiagnosticsEnabled: Boolean,
    onIncludeRelatedPosts: () -> Unit,
    onDismissRelatedPosts: () -> Unit,
    onRetryRelatedPosts: () -> Unit,
) {
    val readReturnProgress = remember(returnProgress) { { returnProgress.value } }
    val inPictureInPicture = activity?.viewerPictureInPictureActive == true
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val screen = remember { ViewerScreenState() }
    val postPagerState = key(uiState.session, navigation.branchAnchor) {
        rememberPagerState(initialPage = navigation.currentIndex, pageCount = { posts.size })
    }
    LaunchedEffect(posts, navigation.currentIndex, videoPositions) { videoPositions.retainAround(posts.map { it.id }, navigation.currentIndex) }
    val viewerTransform = remember(uiState.session?.value, uiState.currentMedia?.key) { mutableStateOf(ViewerTransformState()) }
    val selection = rememberViewerSelection(uiState, posts[navigation.currentIndex], pixivUgoiraClient != null)
    // Brightness holds for every media and the overview (PiP hands it back); the remembered volume returns for video only.
    val levelControls = rememberViewerLevelControls(!inPictureInPicture, restoreVolume = selection.media?.let(::isVideoMediaRef) == true && !uiState.overview.visible)
    val holdScrub = remember { ViewerHoldScrub() }
    val touchExplorationEnabled = rememberViewerTouchExploration()
    val chromeVisible = viewerChromeVisible(uiState, touchExplorationEnabled, inPictureInPicture, returning, opening, dismissDrag)
    val chromeStage = rememberViewerChromeStage(chromeVisible)
    val controls = rememberViewerControls(onAction, screen, uiState.overview.visible, chromeVisible, opening, returning)
    val frame = remember(uiState, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape) {
        ViewerFrame(uiState, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape)
    }
    val flingVelocity = with(LocalDensity.current) { VIEWER_FLING_VELOCITY.toPx() }
    val swipe = rememberViewerSwipe(frame, postPagerState, invertMultiImageScrollDirection, screen, controls, flingVelocity, scope, onAction)
    val loadMoreFromStrip = rememberViewerLoadMore(navigation.originalPosts.size, canLoadMoreFromSource, loadingMoreFromSource, onAction)
    ViewerFrameEffects(
        frame, screen, controls, swipe, dismissDrag, postPagerState, pendingPostId, canLoadMoreFromSource,
        loadingMoreFromSource, loadMoreFromStrip, onAction, onVisiblePostChanged,
    )
    val viewerAccent = rememberViewerAccent(selection.post, enabled = !opening && !returning)
    CompositionLocalProvider(
        LocalViewerPostStripInset provides if (chromeVisible && !frame.mediaOverviewVisible) VIEWER_POST_STRIP_HEIGHT else 0.dp,
        LocalViewerAccent provides viewerAccent,
        LocalViewerHoldScrub provides holdScrub,
    ) {
        ViewerBackdrop(returnProgress, dismissDrag) {
            if (frame.mediaOverviewVisible && selection.overviewAvailable) {
                ViewerOverview(selection, screen, controls, onAction)
            } else {
                ViewerPostPagerSurface(
                    frame, postPagerState, viewerTransform, screen, controls, swipe, dismissDrag, levelControls, readReturnProgress, flingVelocity,
                    quality.value, qualityHeight.value, playbackDiagnosticsEnabled, videoPositions, invertMultiImageScrollDirection,
                    pixivUgoiraClient, onAction, onVisibleMediaChanged, onAuthoritativeDurationKnown, onRequestMediaRecovery, scope,
                )
            }
            ViewerChromeOverlays(
                frame, postPagerState, screen, controls, swipe, activity, quality, qualityHeight, pendingPostId, likedPostIds,
                invertMultiImageScrollDirection, onAction, loadMoreFromStrip, onIncludeRelatedPosts, onDismissRelatedPosts, onRetryRelatedPosts,
            )
        }
    }
    if (uiState.controls.metadataVisible && !inPictureInPicture) {
        ViewerInfoSheet(
            selection, controls, creatorBrowsingSources, saveToCodexQuery, tagVideoCountProvider, fetchTagVideoCounts,
            onAction, onOpenInBrowser, onRemoveIncludeTerm, onRemoveExcludeTerm, onFavoriteTagLongPress, onGoToSearch, onOpenCreatorFallback,
            liked = selection.post.id in likedPostIds,
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
    val wanted = uiState.controls.chromeVisible || touchExplorationEnabled
    return wanted && !inPictureInPicture && !returning && !opening && !dismissDrag.active
}

/** Runs at most once per list size, and only while the source has more to give. */
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

/** Dims what is behind the Viewer as it opens, returns, or is pulled away. */
@Composable
private fun ViewerBackdrop(
    returnProgress: State<Float>,
    dismissDrag: ViewerDismissDrag,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = (1f - returnProgress.value) * (1f - VIEWER_DISMISS_DIM * dismissDrag.progress)))
            },
        content = content,
    )
}

@Composable
private fun ViewerOverview(
    selection: ViewerSelection,
    screen: ViewerScreenState,
    controls: ViewerControls,
    onAction: (ViewerAction) -> Unit,
) {
    ViewerMediaOverviewGrid(
        post = selection.post,
        items = selection.overviewItems,
        selectedMediaIndex = selection.mediaIndex,
        onMediaSelected = { mediaIndex ->
            controls.jumpToMedia(selection.post.id, mediaIndex)
            controls.setOverviewVisible(false)
        },
        modifier = Modifier.fillMaxSize(),
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

private const val VIEWER_DISMISS_DIM = 0.7f
