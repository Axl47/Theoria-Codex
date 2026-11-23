package com.theoriacodex.app.viewer

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerMediaKind
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId

/** Where the info sheet opens: at its top, or scrolled to the comments. */
internal enum class InfoSheetTarget { Top, Comments }

/** State the screen's parts share for as long as the Viewer stays composed. */
@Stable
internal class ViewerScreenState {
    val imageSession = ViewerImageSession()
    /** The selected video's tracks, for the episode controls. */
    val activeTracks = ViewerActiveTracks()
    val streamAttempts = ViewerStreamAttempts()
    val mediaPagers = mutableMapOf<PostId, PagerState>()
    /** Galleries shown as one vertical column, by post; observed, so the screen's gestures follow a new one at once. */
    val verticalGalleries = mutableStateMapOf<PostId, ViewerVerticalGalleryState>()
    /** Each vertical gallery's scroll and zoom, kept while its column leaves composition (the page overview replaces it). */
    val verticalGalleryPositions = mutableMapOf<PostId, ViewerVerticalGalleryState>()
    val pendingMediaJumpByPost = mutableStateMapOf<PostId, Int>()
    var interactionSerial by mutableIntStateOf(0)
    var timelineInteractionActive by mutableStateOf(false)
    /** Set while a hold-scrub has shown chrome that was hidden, so the scrub's end can hide it again. */
    var chromeShownForScrub = false
    var relatedStripExpanded by mutableStateOf(false)
    var pendingDismiss by mutableStateOf(false)

    /** The dock's Save fan; lives here so the auto-hide can see when the Save button holds the chrome. */
    val saveFan = ViewerSaveFanState()

    /** This level's trail forks; closed when the screen leaves. */
    val trailForks = ViewerTrailForks()

    /** Read when the info sheet opens; the sheet resets it on close, so any other way of opening it starts at the top. */
    var infoSheetTarget by mutableStateOf(InfoSheetTarget.Top)
}

/**
 * What the user can toggle, judged against the frame these values came from. Use
 * [rememberViewerControls], which replaces the instance when any of them change; an instance held
 * across that change would toggle chrome or dismiss against a state that has passed.
 */
internal class ViewerControls(
    private val onAction: (ViewerAction) -> Unit,
    private val screen: ViewerScreenState,
    private val overviewVisible: Boolean,
    private val chromeVisible: Boolean,
    private val opening: Boolean,
    private val returning: Boolean,
) {
    fun setInfoSheetVisible(visible: Boolean) = setPanelOpen(ViewerPanel.INFO, visible)

    /** Opens the info sheet scrolled to the post's comments. */
    fun openInfoSheetAtComments() {
        screen.infoSheetTarget = InfoSheetTarget.Comments
        setInfoSheetVisible(true)
    }

    /** What the open info sheet was asked to show; consumed once as the sheet opens. */
    val infoSheetTarget: InfoSheetTarget get() = screen.infoSheetTarget

    /** The level's trail forks, for the info sheet's tags. */
    val trailForks: ViewerTrailForks get() = screen.trailForks

    fun resetInfoSheetTarget() {
        screen.infoSheetTarget = InfoSheetTarget.Top
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        onAction(if (enabled) ViewerAction.Play else ViewerAction.Pause)
    }

    fun setActionsMenuExpanded(expanded: Boolean) = setPanelOpen(ViewerPanel.ACTIONS_MENU, expanded)

    fun setPlaybackSettingsExpanded(expanded: Boolean) = setPanelOpen(ViewerPanel.PLAYBACK_SETTINGS, expanded)

    /** Opening a panel closes the one that was open. */
    fun setPanelOpen(panel: ViewerPanel, open: Boolean) {
        onAction(if (open) ViewerAction.ShowPanel(panel) else ViewerAction.HidePanel(panel))
    }

    fun setOverviewVisible(visible: Boolean) {
        if (visible != overviewVisible) onAction(ViewerAction.ToggleOverview)
    }

    fun stopSlideshow() {
        onAction(ViewerAction.SetSlideshow(false))
    }

    fun markInteraction() {
        screen.interactionSerial += 1
        if (!chromeVisible && !opening && !returning) onAction(ViewerAction.ToggleChrome)
    }

    /** For seeking, zooming, and navigation: keeps open controls alive but never opens them; only a tap does. */
    fun markPassiveInteraction() {
        screen.interactionSerial += 1
    }

    /** A hold-scrub needs the timeline on screen; chrome it had to show is hidden again by [endScrub]. */
    fun beginScrub() {
        screen.chromeShownForScrub = !chromeVisible && !opening && !returning
        markInteraction()
    }

    /** Hides by value rather than toggling: begin and end can share one frame's controls, whose visibility is stale. */
    fun endScrub() {
        if (screen.chromeShownForScrub) onAction(ViewerAction.SetChromeVisible(false))
        screen.chromeShownForScrub = false
    }

    fun toggleChrome() {
        onAction(ViewerAction.ToggleChrome)
        screen.interactionSerial += 1
    }

    /** Moves the open post to one of its pages at once, for controls that pick a page directly. */
    fun jumpToMedia(postId: PostId, mediaIndex: Int) {
        onAction(ViewerAction.SelectMedia(mediaIndex))
        screen.pendingMediaJumpByPost[postId] = mediaIndex
        markInteraction()
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        screen.timelineInteractionActive = isActive
        if (isActive) markInteraction()
    }

    fun requestDismiss() {
        if (screen.pendingDismiss) return
        onAction(ViewerAction.PrepareDismiss)
        screen.pendingDismiss = true
    }
}

@Composable
internal fun rememberViewerControls(
    onAction: (ViewerAction) -> Unit,
    screen: ViewerScreenState,
    overviewVisible: Boolean,
    chromeVisible: Boolean,
    opening: Boolean,
    returning: Boolean,
): ViewerControls = remember(onAction, screen, overviewVisible, chromeVisible, opening, returning) {
    ViewerControls(onAction, screen, overviewVisible, chromeVisible, opening, returning)
}

/** The selected post's media, and what that media allows. */
internal class ViewerSelection(
    val post: Post,
    val mediaItems: List<ImageRef>,
    val mediaIndex: Int,
    val canPlayUgoira: Boolean,
    val overviewItems: List<ViewerMediaOverviewItem>,
) {
    val media: ImageRef? = mediaItems.getOrNull(mediaIndex)
    private val kind = media?.let { viewerRendererMediaKind(post, it, canPlayUgoira) }
    val isSeekable = kind?.seekable == true
    val canDownload = kind == ViewerMediaKind.UGOIRA || !media?.url.isNullOrBlank()
    val isAnimated = kind?.animated == true
    val overviewAvailable = viewerMediaOverviewAvailable(overviewItems)
}

@Composable
internal fun rememberViewerSelection(uiState: ViewerUiState, page: ViewerPageState, canPlayUgoira: Boolean): ViewerSelection {
    val post = page.post
    // The page's media and its kinds follow its post, so a readiness change rebuilds neither list.
    val mediaItems = remember(post) { page.media.map(ViewerMediaState::ref) }
    val mediaIndex = uiState.currentPage?.selectedMediaIndex?.coerceIn(0, mediaItems.lastIndex) ?: 0
    val overviewItems = remember(post) { viewerMediaOverviewItems(page) }
    return remember(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems) {
        ViewerSelection(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems)
    }
}

/** One composition's view of the screen, kept as one instance while none of these values change. */
internal class ViewerFrame(
    val uiState: ViewerUiState,
    val navigation: ViewerPostNavigation,
    val posts: List<Post>,
    val selection: ViewerSelection,
    val chromeVisible: Boolean,
    val chromeStage: Int,
    val opening: Boolean,
    val returning: Boolean,
    val inPictureInPicture: Boolean,
    val touchExplorationEnabled: Boolean,
    val isLandscape: Boolean,
) {
    val mediaOverviewVisible = uiState.overview.visible && !inPictureInPicture

    // Media pages drop the portrait top gap in landscape and in PiP, where it would frame the media in black bars.
    val mediaEdgeToEdge = isLandscape || inPictureInPicture
}
