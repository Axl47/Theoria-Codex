package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.PostId

/** Everything drawn over the media: top bar, post strip, rotate and slideshow buttons, and related drawer. */
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
) {
    val selection = frame.selection
    val navigation = frame.navigation
    OpenInfoSheetForFocusedComment(comments, selection.post, frame.uiState.controls.panel == ViewerPanel.INFO, controls)
    // While scrubbing everything but the timeline steps back. Only its alpha changes, so the strip keeps its scroll position.
    val scrubFade by animateFloatAsState(if (screen.timelineInteractionActive) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer scrub fade")
    val fadesWhileScrubbing = Modifier.graphicsLayer { alpha = scrubFade }
    val mirrored = config.readingModeOf(navigation.pages.getOrNull(navigation.currentIndex)).reversed
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.BottomCenter)) {
        val stripIndex = navigation.originalPosts.indexOfFirst { it.id == frame.posts.getOrNull(postPagerState.currentPage)?.id }
            .takeIf { it >= 0 } ?: navigation.stripAnchorIndex
        ViewerBottomChrome(
            timelinePresent = selection.isAnimated,
            fadesWhileScrubbing = fadesWhileScrubbing,
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
            ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
                pendingPostId.value = navigation.originalPosts[index].id
            }, Modifier.testTag("viewer-post-navigation-strip"),
                onLoadMore = loadMoreFromStrip, onInteraction = { controls.markInteraction() },
                openPages = ViewerStripPages(selection.post.id, selection.overviewItems, selection.mediaIndex, mirrored) {
                    controls.jumpToMedia(selection.post.id, it)
                })
        }
    }
    ViewerFooterButtons(frame, screen, activity, host.onAction)
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.TopCenter).then(fadesWhileScrubbing)) {
        ViewerTopChrome(frame, controls, activity, quality, qualityHeight, selection.post.id in config.likedPostIds, comments.countOf(selection.post), host.onAction)
    }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.TopEnd).padding(top = 72.dp).then(fadesWhileScrubbing)) {
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

/** The rotate button on the left and the slideshow button on the right, hidden while another surface owns the bottom. */
@Composable
private fun BoxScope.ViewerFooterButtons(frame: ViewerFrame, screen: ViewerScreenState, activity: ViewerHostActivity?, onAction: (ViewerAction) -> Unit) {
    val bottomOwned = frame.mediaOverviewVisible || screen.timelineInteractionActive
    // A running slideshow keeps the image clear, including of the rotate hint.
    val rotationSuppressed = bottomOwned || frame.uiState.controls.slideshow
    ViewerRotationControl(frame.selection, activity?.viewerRotation, frame.inPictureInPicture, frame.chromeVisible, rotationSuppressed)
    ViewerSlideshowControl(frame, suppressed = bottomOwned || frame.inPictureInPicture, onAction)
}

/** Binds the frame's state and controls to [ViewerChrome], the top bar and its menus. */
@Composable
private fun ViewerTopChrome(
    frame: ViewerFrame,
    controls: ViewerControls,
    activity: ViewerHostActivity?,
    quality: MutableState<com.theoriacodex.domain.model.VideoQuality>,
    qualityHeight: MutableState<Int?>,
    liked: Boolean,
    commentCount: CommentCount?,
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
        actionsMenuExpanded = uiState.controls.panel == ViewerPanel.ACTIONS_MENU,
        onActionsMenuExpandedChange = { expanded ->
            controls.setActionsMenuExpanded(expanded)
            if (expanded) controls.markInteraction()
        },
        playbackSettingsExpanded = uiState.controls.panel == ViewerPanel.PLAYBACK_SETTINGS,
        onPlaybackSettingsExpandedChange = { expanded ->
            controls.setPlaybackSettingsExpanded(expanded && playbackAvailable)
            if (expanded && playbackAvailable) controls.markInteraction()
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
                controls.markInteraction()
            }
        },
        onInfo = { controls.showInfoSheet(atComments = false) },
        commentCount = commentCount,
        onComments = { controls.showInfoSheet(atComments = true) },
    )
}

/** Opens the info sheet from the top bar, at its top or scrolled to the comments. */
private fun ViewerControls.showInfoSheet(atComments: Boolean) {
    if (atComments) openInfoSheetAtComments() else setInfoSheetVisible(true)
    markInteraction()
}
