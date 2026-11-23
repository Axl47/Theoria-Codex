package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** What the top bar names: a series chapter by its title, any other post by its source. */
private fun ViewerScreenConfig.topBarTitle(post: Post): String =
    post.title?.takeIf { seriesChapters } ?: post.id.source.displayName()

/** The dock's Save button; a series chapter is not a post to save, so its dock has none. */
@Composable
private fun ViewerDockSave(
    config: ViewerScreenConfig,
    post: Post,
    saveContext: ViewerSaveContext,
    screen: ViewerScreenState,
    controls: ViewerControls,
    host: ViewerScreenHost,
) {
    if (config.seriesChapters) return
    ViewerSaveButton(post, saveContext, screen.saveFan, controls::markInteraction) { host.onAction(ViewerAction.Save) }
}

/** Everything drawn over the media: top bar and trail row, dock, post strip, rotate and slideshow buttons, related drawer and the save fan. */
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
    OpenInfoSheetForFocusedComment(comments, selection.post, frame.uiState.controls.panel == ViewerPanel.INFO, controls)
    LaunchedEffect(selection.post.id) { screen.trailForks.follow(selection.post.id) }
    // The selection changes only once a swipe lands; a fork that came back during the slide would open over the post being left.
    val swipingToAnotherPost = swipe.loadTarget?.let { it.postIndex != navigation.currentIndex } == true
    LaunchedEffect(swipingToAnotherPost) { if (swipingToAnotherPost) screen.trailForks.leave() }
    // While scrubbing everything but the timeline steps back. Only its alpha changes, so the strip keeps its scroll position.
    val scrubFade by animateFloatAsState(if (screen.timelineInteractionActive) 0f else 1f, tween(TheoriaMotion.SHORT_MS), label = "Viewer scrub fade")
    val fadesWhileScrubbing = Modifier.graphicsLayer { alpha = scrubFade }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_STRIPS && !frame.mediaOverviewVisible, Modifier.align(Alignment.BottomCenter)) {
        ViewerBottomStack(frame, postPagerState, controls, pendingPostId, config, loadMoreFromStrip, fadesWhileScrubbing)
    }
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.BottomCenter).then(fadesWhileScrubbing)) {
        val dock = viewerDockActions(
            frame, controls, activity, quality, qualityHeight, selection.post.id in config.likedPostIds, comments.countOf(selection.post), host.onAction,
            collectable = !config.seriesChapters,
        )
        ViewerDock(dock) { ViewerDockSave(config, selection.post, saveContext, screen, controls, host) }
    }
    ViewerFooterButtons(frame, screen, activity, host.onAction)
    ViewerChromeVisibility(frame.chromeStage >= VIEWER_CHROME_STAGE_TOP_BAR, Modifier.align(Alignment.TopCenter).then(fadesWhileScrubbing)) {
        ViewerChrome(
            source = config.topBarTitle(selection.post),
            indexLabel = if (selection.mediaItems.size > 1) "${selection.mediaIndex + 1} / ${selection.mediaItems.size}" else null,
            onBack = { controls.requestDismiss() },
        ) { ViewerTrailRow(selection.post, frame.posts, trail, config.creatorBrowsingSources, screen.trailForks, controls::markInteraction) }
    }
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
    ViewerSaveFanOverlay(screen.saveFan)
}

/** The post strip and, on multi-page posts, the page scrubber below it, lifted above the dock and any timeline. */
@Composable
private fun ViewerBottomStack(
    frame: ViewerFrame,
    postPagerState: PagerState,
    controls: ViewerControls,
    pendingPostId: MutableState<PostId?>,
    config: ViewerScreenConfig,
    loadMoreFromStrip: () -> Unit,
    fadesWhileScrubbing: Modifier,
) {
    val selection = frame.selection
    val navigation = frame.navigation
    val mirrored = config.readingModeOf(navigation.pages.getOrNull(navigation.currentIndex)).reversed
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
        ViewerPostStrip(navigation.originalPosts, stripIndex, { index ->
            pendingPostId.value = navigation.originalPosts[index].id
        }, Modifier.testTag("viewer-post-navigation-strip"),
            onLoadMore = loadMoreFromStrip, onInteraction = { controls.markInteraction() },
            openPages = ViewerStripPages(selection.post.id, selection.overviewItems, selection.mediaIndex, mirrored) {
                controls.jumpToMedia(selection.post.id, it)
            }, chapters = config.seriesChapters, chapterNumbers = config.chapterNumbers)
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
