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

internal enum class InfoSheetTarget { Top, Comments }

@Stable
internal class ViewerScreenState {
    val imageSession = ViewerImageSession()
    val activeTracks = ViewerActiveTracks()
    val streamAttempts = ViewerStreamAttempts()
    val mediaPagers = mutableMapOf<PostId, PagerState>()
    val verticalGalleries = mutableStateMapOf<PostId, ViewerVerticalGalleryState>()
    val verticalGalleryPositions = mutableMapOf<PostId, ViewerVerticalGalleryState>()
    val pendingMediaJumpByPost = mutableStateMapOf<PostId, Int>()
    var interactionSerial by mutableIntStateOf(0)
    var touchSerial by mutableIntStateOf(0)
    val playingVideos = ViewerPlayingVideos()
    var timelineInteractionActive by mutableStateOf(false)
    var chromeShownForScrub by mutableStateOf(false)
    var relatedStripExpanded by mutableStateOf(false)
    var pendingDismiss by mutableStateOf(false)
    var heldBack = false

    val saveFan = ViewerSaveFanState()

    val trailForks = ViewerTrailForks()

    var infoSheetTarget by mutableStateOf(InfoSheetTarget.Top)

    var unlockPeekVisible by mutableStateOf(false)
    var unlockPeekSerial by mutableIntStateOf(0)

    fun revealUnlock() {
        unlockPeekVisible = true
        unlockPeekSerial += 1
    }

    fun hideUnlock() {
        unlockPeekVisible = false
    }
}

internal fun restartViewerEpisode(post: Post, controls: ViewerControls, onAction: (ViewerAction) -> Unit) {
    controls.jumpToMedia(post.id, 0)
    onAction(ViewerAction.RestartPlayback)
    controls.setPanelOpen(ViewerPanel.INFO, false)
    controls.setPanelOpen(ViewerPanel.ACTIONS_MENU, false)
}

internal class ViewerControls(
    private val onAction: (ViewerAction) -> Unit,
    private val screen: ViewerScreenState,
    private val overviewVisible: Boolean,
    private val chromeVisible: State<Boolean>,
    private val opening: Boolean,
    private val returning: Boolean,
) {
    fun setInfoSheetVisible(visible: Boolean) = setPanelOpen(ViewerPanel.INFO, visible)

    fun openInfoSheetAtComments() {
        screen.infoSheetTarget = InfoSheetTarget.Comments
        setInfoSheetVisible(true)
    }

    val infoSheetTarget: InfoSheetTarget get() = screen.infoSheetTarget

    val trailForks: ViewerTrailForks get() = screen.trailForks

    fun resetInfoSheetTarget() {
        screen.infoSheetTarget = InfoSheetTarget.Top
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        onAction(if (enabled) ViewerAction.Play else ViewerAction.Pause)
    }

    fun setActionsMenuExpanded(expanded: Boolean) = setPanelOpen(ViewerPanel.ACTIONS_MENU, expanded)

    fun setPlaybackSettingsExpanded(expanded: Boolean) = setPanelOpen(ViewerPanel.PLAYBACK_SETTINGS, expanded)

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
        if (!chromeVisible.value && !opening && !returning) onAction(ViewerAction.ToggleChrome)
    }

    fun markPassiveInteraction() {
        screen.interactionSerial += 1
    }

    fun beginScrub() {
        screen.chromeShownForScrub = !chromeVisible.value && !opening && !returning
        markInteraction()
    }

    fun endScrub() {
        if (screen.chromeShownForScrub) onAction(ViewerAction.SetChromeVisible(false))
        screen.chromeShownForScrub = false
    }

    fun toggleChrome() {
        onAction(ViewerAction.ToggleChrome)
        screen.interactionSerial += 1
    }

    fun jumpToMedia(postId: PostId, mediaIndex: Int) {
        onAction(ViewerAction.SelectMedia(mediaIndex))
        screen.pendingMediaJumpByPost[postId] = mediaIndex
        markInteraction()
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        screen.timelineInteractionActive = isActive
        if (isActive) markInteraction()
    }

    fun back() {
        if (!overviewVisible) return requestDismiss()
        setOverviewVisible(false)
        markInteraction()
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
    chromeVisible: State<Boolean>,
    opening: Boolean,
    returning: Boolean,
): ViewerControls = remember(onAction, screen, overviewVisible, chromeVisible, opening, returning) {
    ViewerControls(onAction, screen, overviewVisible, chromeVisible, opening, returning)
}

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
    val mediaItems = remember(post) { page.media.map(ViewerMediaState::ref) }
    val mediaIndex = uiState.currentPage?.selectedMediaIndex?.coerceIn(0, mediaItems.lastIndex) ?: 0
    val overviewItems = remember(post) { viewerMediaOverviewItems(page) }
    return remember(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems) {
        ViewerSelection(post, mediaItems, mediaIndex, canPlayUgoira, overviewItems)
    }
}

@Composable
internal fun rememberViewerFrame(
    uiState: ViewerUiState,
    navigation: ViewerPostNavigation,
    posts: List<Post>,
    selection: ViewerSelection,
    chromeVisible: State<Boolean>,
    chromeStage: State<Int>,
    opening: Boolean,
    returning: Boolean,
    inPictureInPicture: Boolean,
    touchExplorationEnabled: Boolean,
    isLandscape: Boolean,
): ViewerFrame {
    val requested = uiState.ignoringChromeVisibility()
    return remember(requested, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape) {
        ViewerFrame(requested, navigation, posts, selection, chromeVisible, chromeStage, opening, returning, inPictureInPicture, touchExplorationEnabled, isLandscape)
    }
}

private fun ViewerUiState.ignoringChromeVisibility(): ViewerUiState =
    if (controls.chromeVisible) this else copy(controls = controls.copy(chromeVisible = true))

internal class ViewerFrame(
    val uiState: ViewerUiState,
    val navigation: ViewerPostNavigation,
    val posts: List<Post>,
    val selection: ViewerSelection,
    private val chromeVisibleState: State<Boolean>,
    val chromeStage: State<Int>,
    val opening: Boolean,
    val returning: Boolean,
    val inPictureInPicture: Boolean,
    val touchExplorationEnabled: Boolean,
    val isLandscape: Boolean,
) {
    val chromeVisible: Boolean get() = chromeVisibleState.value

    val mediaOverviewVisible = uiState.overview.visible && !inPictureInPicture

    val watching = uiState.series is ViewerSeries.Watching

    val locked = uiState.controls.locked && watching && !inPictureInPicture

    val reading = uiState.series is ViewerSeries.Reading

    val mediaEdgeToEdge = isLandscape || inPictureInPicture || reading

    val systemControlsReady = !opening && uiState.session != null
}
