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
import com.theoriacodex.app.media.isGifMediaRef
import com.theoriacodex.app.media.isPixivUgoiraMedia
import com.theoriacodex.app.media.isVideoMediaRef
import com.theoriacodex.app.media.postMediaItems
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPageState
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
    val mediaPagers = mutableMapOf<PostId, PagerState>()
    val pendingMediaJumpByPost = mutableStateMapOf<PostId, Int>()
    var interactionSerial by mutableIntStateOf(0)
    var timelineInteractionActive by mutableStateOf(false)
    var relatedStripExpanded by mutableStateOf(false)
    var pendingDismiss by mutableStateOf(false)

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
    fun setInfoSheetVisible(visible: Boolean) {
        onAction(if (visible) ViewerAction.ShowMetadata else ViewerAction.HideMetadata)
    }

    /** Opens the info sheet scrolled to the post's comments. */
    fun openInfoSheetAtComments() {
        screen.infoSheetTarget = InfoSheetTarget.Comments
        setInfoSheetVisible(true)
    }

    /** What the open info sheet was asked to show; consumed once as the sheet opens. */
    val infoSheetTarget: InfoSheetTarget get() = screen.infoSheetTarget

    fun resetInfoSheetTarget() {
        screen.infoSheetTarget = InfoSheetTarget.Top
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        onAction(if (enabled) ViewerAction.Play else ViewerAction.Pause)
    }

    fun setActionsMenuExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowActionsMenu else ViewerAction.HideActionsMenu)
    }

    fun setPlaybackSettingsExpanded(expanded: Boolean) {
        onAction(if (expanded) ViewerAction.ShowPlaybackSettings else ViewerAction.HidePlaybackSettings)
    }

    fun setOverviewVisible(visible: Boolean) {
        if (visible != overviewVisible) onAction(ViewerAction.ToggleOverview)
    }

    fun markInteraction() {
        screen.interactionSerial += 1
        if (!chromeVisible && !opening && !returning) onAction(ViewerAction.ToggleChrome)
    }

    /** For seeking, zooming, and navigation: keeps open controls alive but never opens them; only a tap does. */
    fun markPassiveInteraction() {
        screen.interactionSerial += 1
    }

    fun toggleChrome() {
        onAction(ViewerAction.ToggleChrome)
        screen.interactionSerial += 1
    }

    /** Moves the open post to one of its pages at once, for controls that pick a page directly. */
    fun jumpToMedia(postId: PostId, mediaIndex: Int) {
        onAction(ViewerAction.SelectOverviewMedia(mediaIndex))
        screen.pendingMediaJumpByPost[postId] = mediaIndex
        markInteraction()
    }

    fun onTimelineInteractionChanged(isActive: Boolean) {
        screen.timelineInteractionActive = isActive
        if (isActive) markInteraction()
    }

    fun requestDismiss() {
        if (screen.pendingDismiss) return
        setPlaybackEnabled(false)
        setInfoSheetVisible(false)
        setActionsMenuExpanded(false)
        setPlaybackSettingsExpanded(false)
        setOverviewVisible(false)
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
    val isSeekable = media?.let { media ->
        isVideoMediaRef(media) || isGifMediaRef(media) || (isPixivUgoiraMedia(post, media) && canPlayUgoira)
    } == true
    val canDownload = media?.let { media ->
        (isPixivUgoiraMedia(post, media) && canPlayUgoira) || !media.url.isNullOrBlank()
    } == true
    val isAnimated = media?.let { viewerMediaIsAnimated(post, it, canPlayUgoira) } == true
    val overviewAvailable = viewerMediaOverviewAvailable(overviewItems)
}

@Composable
internal fun rememberViewerSelection(uiState: ViewerUiState, post: Post, canPlayUgoira: Boolean): ViewerSelection {
    val mediaItems = remember(post) { postMediaItems(post) }
    val mediaIndex = uiState.currentPage?.selectedMediaIndex?.coerceIn(0, mediaItems.lastIndex) ?: 0
    val overviewItems = remember(post) { viewerMediaOverviewItems(post) }
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
}

internal fun targetMediaIndex(page: ViewerPageState?) = page?.let { it.pendingMediaIndex ?: it.selectedMediaIndex } ?: 0

