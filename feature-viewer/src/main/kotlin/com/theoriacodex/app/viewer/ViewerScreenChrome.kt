package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlin.math.hypot

/** Top bar primary title and optional episode subtitle: a watched series splits series and episode cleanly when both are non-blank. */
internal fun ViewerSeries?.topBarTitles(post: Post): Pair<String, String?> {
    if (this is ViewerSeries.Watching && !seriesTitle.isNullOrBlank()) {
        val prefix = "$seriesTitle · "
        val title = post.title
        if (title != null && title.startsWith(prefix)) {
            val episode = title.removePrefix(prefix).trim()
            return if (episode.isNotEmpty()) seriesTitle to episode else seriesTitle to null
        }
    }
    return (post.title?.takeIf { this != null } ?: post.id.source.displayName()) to null
}

/** The dock's Save button; a series entry is not a post to save, so its dock has none. */
@Composable
private fun ViewerDockSave(
    series: ViewerSeries?,
    post: Post,
    saveContext: ViewerSaveContext,
    screen: ViewerScreenState,
    controls: ViewerControls,
    host: ViewerScreenHost,
) {
    if (series != null) return
    ViewerSaveButton(post, saveContext, screen.saveFan, controls::markInteraction) { host.onAction(ViewerAction.Save) }
}

/** Everything drawn over the media: top bar and trail row, center controls (episodes), dock, post strip or episode bar, footer buttons, lock overlay, sheets and save fan. */
@Composable
internal fun BoxScope.ViewerChromeOverlays(
    frame: ViewerFrame,
    postPagerState: PagerState,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    activity: ViewerHostActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    pendingPostId: MutableState<PostId?>,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    loadMoreFromStrip: () -> Unit,
    relatedShelf: ViewerRelatedShelf,
    comments: ViewerComments,
    saveContext: ViewerSaveContext,
    trail: ViewerTrail,
) {
    val selection = frame.selection
    val navigation = frame.navigation
    val watching = frame.uiState.series is ViewerSeries.Watching
    OpenInfoSheetForFocusedComment(comments, selection.post, frame.uiState.controls.panel == ViewerPanel.INFO, controls)
    LaunchedEffect(selection.post.id) { screen.trailForks.follow(selection.post.id) }
    // The selection changes only once a swipe lands; a fork that came back during the slide would open over the post being left.
    val swipingToAnotherPost = swipe.loadTarget?.let { it.postIndex != navigation.currentIndex } == true
    LaunchedEffect(swipingToAnotherPost) { if (swipingToAnotherPost) screen.trailForks.leave() }
    // While scrubbing everything but the timeline steps back. Only its alpha changes, so the strip keeps its scroll position.
    val scrubFade by animateFloatAsState(if (screen.timelineInteractionActive) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer scrub fade")
    val fadesWhileScrubbing = Modifier.graphicsLayer { alpha = scrubFade }
    val rotationOffer = rememberViewerRotation(selection, activity?.viewerRotation, frame.inPictureInPicture)
    val episodeStream = selection.media?.viewerStream(frame.uiState.controls.episode.streamLabelFor(frame.uiState.currentMedia?.key))
    val pictureInPicture = viewerPictureInPictureAction(activity, selection.post, selection.media, frame.uiState.mediaError != null)

    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.BottomCenter)) {
        ViewerBottomStack(frame, postPagerState, controls, pendingPostId, config, loadMoreFromStrip, fadesWhileScrubbing) {
            ViewerEpisodeBar(frame, controls, rotationOffer, pictureInPicture, host.onAction)
        }
    }
    ViewerDockOverlay(watching, frame, screen, controls, activity, quality, qualityHeight, host, config, comments, saveContext, fadesWhileScrubbing)
    ViewerEpisodeCenterOverlay(watching, frame, screen, controls, swipe, config, host.onAction, fadesWhileScrubbing)
    ViewerFooterButtons(frame, screen, activity, rotationOffer, host.onAction)
    ViewerTopBarOverlay(watching, frame, screen, controls, episodeStream, config, trail, host.onAction, fadesWhileScrubbing)
    ViewerRelatedStripOverlay(frame, screen, controls, swipe, pendingPostId, relatedShelf, fadesWhileScrubbing)
    ViewerSaveFanOverlay(screen.saveFan)
    if (watching && !frame.inPictureInPicture) {
        ViewerEpisodeSheets(
            streams = selection.media?.streams.orEmpty(),
            currentStream = episodeStream,
            tracks = screen.activeTracks.tracks,
            playbackRate = closestViewerPlaybackRate(frame.uiState.controls.playback.playbackRate),
            openPanel = frame.uiState.controls.panel,
            onDismiss = { panel -> controls.setPanelOpen(panel, false) },
            onAction = host.onAction,
        )
    }
    if (frame.locked) {
        ViewerLockOverlay(
            unlockVisible = screen.unlockPeekVisible || frame.touchExplorationEnabled,
            onTapScreen = screen::revealUnlock,
            onUnlock = {
                screen.hideUnlock()
                host.onAction(ViewerAction.SetLocked(false))
            },
        )
    }
}

@Composable
private fun BoxScope.ViewerDockOverlay(
    watching: Boolean,
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    activity: ViewerHostActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    host: ViewerScreenHost,
    config: ViewerScreenConfig,
    comments: ViewerComments,
    saveContext: ViewerSaveContext,
    fadesWhileScrubbing: Modifier,
) {
    if (watching) return
    val selection = frame.selection
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.BottomCenter).then(fadesWhileScrubbing)) {
        val dock = viewerDockActions(
            frame, controls, activity, quality, qualityHeight, selection.post.id in config.likedPostIds, comments.countOf(selection.post), host.onAction,
            series = frame.uiState.series,
        )
        ViewerDock(dock) { ViewerDockSave(frame.uiState.series, selection.post, saveContext, screen, controls, host) }
    }
}

@Composable
private fun BoxScope.ViewerEpisodeCenterOverlay(
    watching: Boolean,
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    config: ViewerScreenConfig,
    onAction: (ViewerAction) -> Unit,
    fadesWhileScrubbing: Modifier,
) {
    if (!watching) return
    val navigation = frame.navigation
    ViewerChromeVisibility(
        visible = frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible,
        modifier = Modifier.align(Alignment.Center).then(fadesWhileScrubbing),
    ) {
        ViewerEpisodeCenterControls(
            isPlaying = frame.uiState.controls.playback.playing,
            hideCenterPlayback = !frame.uiState.controls.playback.available || screen.activeTracks.loading || frame.uiState.mediaError != null,
            onTogglePlayback = { controls.markInteraction(); onAction(ViewerAction.TogglePlayback) },
            onPrevious = previousPostOpener(navigation.currentIndex, frame, swipe, config)?.let { prev -> { controls.markInteraction(); prev() } },
            onNext = nextPostOpener(navigation.currentIndex, frame, swipe, config)?.let { next -> { controls.markInteraction(); next() } },
        )
    }
}

@Composable
private fun BoxScope.ViewerTopBarOverlay(
    watching: Boolean,
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    episodeStream: com.theoriacodex.domain.model.MediaStream?,
    config: ViewerScreenConfig,
    trail: ViewerTrail,
    onAction: (ViewerAction) -> Unit,
    fadesWhileScrubbing: Modifier,
) {
    val selection = frame.selection
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.TopCenter).then(fadesWhileScrubbing)) {
        val (title, subtitle) = frame.uiState.series.topBarTitles(selection.post)
        ViewerChrome(
            source = title,
            subtitle = subtitle,
            indexLabel = if (selection.mediaItems.size > 1) "${selection.mediaIndex + 1} / ${selection.mediaItems.size}" else null,
            onBack = { controls.requestDismiss() },
            trailing = {
                if (watching) {
                    ViewerEpisodeTopActions(
                        currentStream = episodeStream,
                        tracks = screen.activeTracks.tracks,
                        moreExpanded = frame.uiState.controls.panel == ViewerPanel.ACTIONS_MENU,
                        onOpenPanel = { panel -> controls.setPanelOpen(panel, true); controls.markInteraction() },
                        onMoreExpandedChange = { expanded -> controls.setActionsMenuExpanded(expanded); if (expanded) controls.markInteraction() },
                        onStartOver = { restartViewerEpisode(selection.post, controls, onAction) },
                    )
                }
            },
        ) { ViewerTrailRow(selection.post, frame.posts, trail, config.creatorBrowsingSources, screen.trailForks, controls::markInteraction) }
    }
}

@Composable
private fun BoxScope.ViewerRelatedStripOverlay(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    controls: ViewerControls,
    swipe: ViewerSwipeController,
    pendingPostId: MutableState<PostId?>,
    relatedShelf: ViewerRelatedShelf,
    fadesWhileScrubbing: Modifier,
) {
    val selection = frame.selection
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.TopEnd).padding(top = 112.dp).then(fadesWhileScrubbing)) {
        ViewerRelatedStrip(
            state = relatedShelf.state, posts = relatedShelf.posts, currentPostId = selection.post.id,
            onSelect = { post ->
                controls.markInteraction()
                if (post.id != selection.post.id) {
                    pendingPostId.value = post.id
                    relatedShelf.onInclude()
                }
            },
            onDismiss = { relatedShelf.onDismiss(); controls.markInteraction() },
            onRetry = { relatedShelf.onRetry(); controls.markInteraction() },
            enabled = !swipe.settling && !swipe.dragging,
            onExpandedChange = { screen.relatedStripExpanded = it },
            onInteraction = { controls.markInteraction() },
        )
    }
}

/**
 * Single-switch lock layer over the entire pager surface. Consumes every pointer event so no media gesture,
 * pinch, swipe, or retry tap leaks through; a single tap reveals only the blue closed-padlock Unlock icon button.
 */
@Composable
private fun BoxScope.ViewerLockOverlay(
    unlockVisible: Boolean,
    onTapScreen: () -> Unit,
    onUnlock: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(VIEWER_LOCK_OVERLAY_TAG)
            .pointerInput(Unit) { awaitEachGesture { consumeLockedGesture(onTapScreen) } },
    )
    ViewerChromeVisibility(
        visible = unlockVisible,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        IconButton(
            onClick = onUnlock,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .testTag(VIEWER_UNLOCK_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Unlock controls",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Consumes one complete touch sequence while locked, calling [onTap] only for a clean single-finger tap. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeLockedGesture(onTap: () -> Unit) {
    val slop = viewConfiguration.touchSlop
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    down.consume()
    var moved = false
    var multiTouch = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.size > 1) multiTouch = true
        event.changes.forEach { it.consume() }
        val primary = event.changes.firstOrNull { it.id == down.id }
        if (primary != null && hypot(primary.position.x - down.position.x, primary.position.y - down.position.y) > slop) {
            moved = true
        }
        if (event.changes.none { it.pressed }) {
            if (!moved && !multiTouch) onTap()
            return
        }
    }
}

/**
 * The post strip and, on multi-page posts, the page scrubber below it, lifted above the dock and any timeline. While a
 * series is watched, [episodeControls] take the strip's place: every episode shows the same cover.
 */
@Composable
private fun ViewerBottomStack(
    frame: ViewerFrame,
    postPagerState: PagerState,
    controls: ViewerControls,
    pendingPostId: MutableState<PostId?>,
    config: ViewerScreenConfig,
    loadMoreFromStrip: () -> Unit,
    fadesWhileScrubbing: Modifier,
    episodeControls: @Composable () -> Unit,
) {
    val selection = frame.selection
    val navigation = frame.navigation
    val mirrored = config.readingModeOf(navigation.pages.getOrNull(navigation.currentIndex), frame.uiState.series).reversed
    val stripIndex = navigation.originalPosts.indexOfFirst { it.id == frame.posts.getOrNull(postPagerState.currentPage)?.id }
        .takeIf { it >= 0 } ?: navigation.stripAnchorIndex
    ViewerBottomChrome(
        timelinePresent = selection.isAnimated,
        fadesWhileScrubbing = fadesWhileScrubbing,
        lift = LocalViewerTimelineInset.current + if (selection.isAnimated) viewerTimelineFooterHeight() else 0.dp,
        pageBar = {
            if (selection.overviewItems.size > 1) {
                // Keyed by post so a drag held across a post change is cancelled instead of landing on the new post.
                key(selection.post.id) {
                    ViewerPageScrubber(
                        source = selection.post.id.source,
                        items = selection.overviewItems,
                        aspectRatio = viewerAspectRatio(selection.post),
                        pageIndex = selection.mediaIndex,
                        mirrored = mirrored,
                        onPageSelected = { controls.jumpToMedia(selection.post.id, it) },
                        onInteractionActiveChanged = { controls.onTimelineInteractionChanged(it) },
                    )
                }
            }
        },
    ) {
        if (frame.uiState.series is ViewerSeries.Watching) {
            episodeControls()
        } else {
            ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
                pendingPostId.value = navigation.originalPosts[index].id
            }, Modifier.testTag("viewer-post-navigation-strip"),
                onLoadMore = loadMoreFromStrip, onInteraction = { controls.markInteraction() },
                openPages = ViewerStripPages(selection.post.id, selection.overviewItems, selection.mediaIndex, mirrored) {
                    controls.jumpToMedia(selection.post.id, it)
                }, chapters = frame.uiState.series is ViewerSeries.Reading, stripMarks = config.stripMarks)
        }
    }
}

/**
 * The rotate button on the left and the slideshow button on the right, hidden while another surface owns the bottom.
 * A watched series places Rotate inline when chrome is visible (keeping only the hidden-chrome hint floating) and offers no slideshow.
 */
@Composable
private fun BoxScope.ViewerFooterButtons(
    frame: ViewerFrame,
    screen: ViewerScreenState,
    activity: ViewerHostActivity?,
    rotationOffer: ViewerRotationOffer?,
    onAction: (ViewerAction) -> Unit,
) {
    val bottomOwned = frame.mediaOverviewVisible || screen.timelineInteractionActive
    val watching = frame.uiState.series is ViewerSeries.Watching
    // A running slideshow, active lock, or visible episode bar (which has Rotate inline) suppresses the floating rotate button.
    val rotationSuppressed = bottomOwned || frame.uiState.controls.slideshow || frame.locked || (watching && frame.chromeVisible)
    val bottomRow = if (watching) VIEWER_EPISODE_CONTROLS_HEIGHT else VIEWER_POST_STRIP_HEIGHT
    ViewerRotationControl(frame.selection, activity?.viewerRotation, frame.inPictureInPicture, frame.chromeVisible, rotationSuppressed, bottomRow, existingOffer = rotationOffer)
    ViewerSlideshowControl(frame, suppressed = bottomOwned || frame.inPictureInPicture || watching, onAction)
}

internal const val VIEWER_LOCK_OVERLAY_TAG = "viewer-lock-overlay"
