package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import com.theoriacodex.app.comments.CommentCount
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.domain.model.VideoQuality
import kotlinx.coroutines.flow.drop

@Composable
internal fun viewerDockInset(): Dp = VIEWER_DOCK_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
internal fun rememberViewerVideoQuality(default: VideoQuality, onDefaultChanged: (VideoQuality) -> Unit): MutableState<VideoQuality> {
    val quality = remember(default) { mutableStateOf(default) }
    val currentOnDefaultChanged by rememberUpdatedState(onDefaultChanged)
    LaunchedEffect(quality) { snapshotFlow { quality.value }.drop(1).collect { currentOnDefaultChanged(it) } }
    return quality
}

@Composable
internal fun viewerDockActions(
    frame: ViewerFrame,
    controls: ViewerControls,
    activity: ViewerHostActivity?,
    quality: MutableState<VideoQuality>,
    qualityHeight: MutableState<Int?>,
    liked: Boolean,
    commentCount: CommentCount?,
    onAction: (ViewerAction) -> Unit,
    moreExtras: List<ViewerMenuItem> = emptyList(),
    series: ViewerSeries? = null,
): ViewerDockActions {
    val collectable = series == null
    val selection = frame.selection
    val pictureInPicture = viewerPictureInPictureAction(activity, selection.post, selection.media, frame.uiState.mediaError != null)
    return ViewerDockActions(
        liked = liked,
        onToggleLike = if (collectable) {
            {
                onAction(ViewerAction.ToggleLike)
                controls.markInteraction()
            }
        } else null,
        commentCount = commentCount,
        onInfo = {
            controls.setInfoSheetVisible(true)
            controls.markInteraction()
        },
        moreExpanded = frame.uiState.controls.panel == ViewerPanel.ACTIONS_MENU,
        onMoreExpandedChange = { expanded ->
            controls.setActionsMenuExpanded(expanded)
            if (expanded) controls.markInteraction()
        },
        moreItems = viewerMoreItems(onChooseCodex = if (collectable) { { onAction(ViewerAction.Save) } } else null, pictureInPicture, moreExtras),
        overview = if (selection.overviewAvailable) {
            ViewerOverviewToggle(frame.mediaOverviewVisible) {
                controls.setOverviewVisible(!frame.mediaOverviewVisible)
                controls.markInteraction()
            }
        } else null,
        playbackSettings = if (frame.uiState.controls.playback.available) {
            { ViewerPlaybackSettings(frame, controls, quality, qualityHeight, series is ViewerSeries.Watching, onAction) }
        } else null,
    )
}

@Composable
private fun ViewerPlaybackSettings(
    frame: ViewerFrame,
    controls: ViewerControls,
    quality: MutableState<VideoQuality>,
    qualityHeight: MutableState<Int?>,
    watching: Boolean,
    onAction: (ViewerAction) -> Unit,
) {
    val uiState = frame.uiState
    PlaybackSettingsMenu(
        expanded = uiState.controls.panel == ViewerPanel.PLAYBACK_SETTINGS,
        onExpandedChange = { expanded ->
            controls.setPlaybackSettingsExpanded(expanded)
            if (expanded) controls.markInteraction()
        },
        playbackRate = closestViewerPlaybackRate(uiState.controls.playback.playbackRate),
        playbackRateEnabled = frame.selection.isSeekable,
        onPlaybackRateSelected = { selectedRate ->
            onAction(ViewerAction.SetPlaybackRate(selectedRate.speed))
            controls.markInteraction()
        },
        playbackMode = uiState.controls.playbackMode.takeUnless { watching },
        onPlaybackModeSelected = { onAction(ViewerAction.SetPlaybackMode(it)) },
        quality = quality.value,
        qualityHeight = qualityHeight.value,
        videoVariants = uiState.currentMedia?.ref?.videoVariants.orEmpty(),
        onQualitySelected = { selected, height -> quality.value = selected; qualityHeight.value = height },
    )
}
