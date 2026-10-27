package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.theoriacodex.app.ui.components.SecondaryScreenAppBar
import kotlin.math.abs

internal enum class ViewerPlaybackRate(
    val speed: Float,
    val menuLabel: String,
    val contentDescription: String,
) {
    VerySlow(0.2f, "0.2x Very slow", "Playback rate 0.2x very slow"),
    Slow(0.5f, "0.5x Slow", "Playback rate 0.5x slow"),
    Normal(1f, "1x Normal", "Playback rate 1x normal"),
    Fast(1.5f, "1.5x Fast", "Playback rate 1.5x fast"),
    VeryFast(2f, "2x Very fast", "Playback rate 2x very fast"),
}

internal fun closestViewerPlaybackRate(rate: Float): ViewerPlaybackRate {
    return ViewerPlaybackRate.entries.minBy { option -> abs(option.speed - rate) }
}

@Composable
internal fun ViewerChrome(
    source: String,
    indexLabel: String?,
    onBack: () -> Unit,
    liked: Boolean,
    actionsMenuExpanded: Boolean,
    onActionsMenuExpandedChange: (Boolean) -> Unit,
    playbackSettingsExpanded: Boolean,
    onPlaybackSettingsExpandedChange: (Boolean) -> Unit,
    playbackSettingsEnabled: Boolean,
    playbackRateEnabled: Boolean = true,
    playbackRate: ViewerPlaybackRate,
    onPlaybackRateSelected: (ViewerPlaybackRate) -> Unit,
    mediaOverviewAvailable: Boolean,
    mediaOverviewVisible: Boolean,
    onToggleMediaOverview: () -> Unit,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleLike: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
    quality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.AUTO,
    playbackMode: com.theoriacodex.app.viewer.state.ViewerPlaybackMode = com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP,
    onPlaybackModeSelected: (com.theoriacodex.app.viewer.state.ViewerPlaybackMode) -> Unit = {},
    qualityHeight: Int? = null,
    videoVariants: List<com.theoriacodex.domain.model.VideoVariant> = emptyList(),
    onQualitySelected: (com.theoriacodex.domain.model.VideoQuality, Int?) -> Unit = { _, _ -> },
) {
    SecondaryScreenAppBar(
        modifier = modifier,
        title = if (indexLabel != null) "$source • $indexLabel" else source,
        onBack = onBack,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        ViewerLikeAction(
            liked = liked,
            onToggleLike = onToggleLike,
        )
        ViewerOverviewAction(
            mediaOverviewAvailable = mediaOverviewAvailable,
            mediaOverviewVisible = mediaOverviewVisible,
            onToggleMediaOverview = onToggleMediaOverview,
        )
        if (playbackSettingsEnabled) {
            PlaybackSettingsMenu(
                expanded = playbackSettingsExpanded,
                onExpandedChange = onPlaybackSettingsExpandedChange,
                playbackRate = playbackRate,
                playbackRateEnabled = playbackRateEnabled,
                onPlaybackRateSelected = onPlaybackRateSelected,
                playbackMode = playbackMode, onPlaybackModeSelected = onPlaybackModeSelected,
                quality = quality, qualityHeight = qualityHeight, videoVariants = videoVariants,
                onQualitySelected = onQualitySelected,
            )
        }
        ViewerActionsMenu(
            expanded = actionsMenuExpanded,
            onExpandedChange = onActionsMenuExpandedChange,
            downloadEnabled = downloadEnabled,
            onDownload = onDownload,
            onInfo = onInfo,
            onPictureInPicture = onPictureInPicture,
        )
    }
}

@Composable
private fun ViewerLikeAction(
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
) {
    if (onToggleLike == null) return
    IconButton(onClick = onToggleLike) {
        Icon(
            imageVector = if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) "Unlike post" else "Like post",
            tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ViewerOverviewAction(
    mediaOverviewAvailable: Boolean,
    mediaOverviewVisible: Boolean,
    onToggleMediaOverview: () -> Unit,
) {
    if (!mediaOverviewAvailable) return
    IconButton(onClick = onToggleMediaOverview) {
        Icon(
            imageVector = Icons.Default.Collections,
            contentDescription = if (mediaOverviewVisible) {
                "Close media overview"
            } else {
                "Open media overview"
            },
            tint = if (mediaOverviewVisible) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
internal fun PlaybackSettingsMenu(
    playbackRateEnabled: Boolean,
    playbackMode: com.theoriacodex.app.viewer.state.ViewerPlaybackMode,
    onPlaybackModeSelected: (com.theoriacodex.app.viewer.state.ViewerPlaybackMode) -> Unit,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    videoVariants: List<com.theoriacodex.domain.model.VideoVariant>,
    onQualitySelected: (com.theoriacodex.domain.model.VideoQuality, Int?) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    playbackRate: ViewerPlaybackRate,
    onPlaybackRateSelected: (ViewerPlaybackRate) -> Unit,
) {
    var interactionSerial by remember { mutableIntStateOf(0) }
    var pointerDown by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) pointerDown = false }
    LaunchedEffect(expanded, interactionSerial, pointerDown) {
        if (expanded && !pointerDown) {
            delay(5_000L)
            onExpandedChange(false)
        }
    }
    fun interact() { interactionSerial++ }
    Box {
        IconButton(onClick = { interact(); onExpandedChange(!expanded) }) {
            Icon(Icons.Default.Settings, contentDescription = "Playback settings")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pointerDown = event.changes.any { it.pressed }
                        interact()
                    }
                }
            },
        ) {
            PlaybackModeChoices(playbackMode, { interact(); onPlaybackModeSelected(it) }, ::interact)
            VideoQualityChoices(videoVariants, quality, qualityHeight, { selected, height ->
                interact(); onQualitySelected(selected, height)
            })
            if (playbackRateEnabled) PlaybackRateSlider(playbackRate) { interact(); onPlaybackRateSelected(it) }
        }
    }
}

@Composable
private fun ViewerActionsMenu(
    onPictureInPicture: (() -> Unit)?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
) {
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("Info") },
                onClick = {
                    onExpandedChange(false)
                    onInfo()
                },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Download") },
                onClick = {
                    onExpandedChange(false)
                    onDownload()
                },
                enabled = downloadEnabled,
                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
            )
            if (onPictureInPicture != null) {
                DropdownMenuItem(
                    text = { Text("Picture-in-picture") },
                    onClick = {
                        onExpandedChange(false)
                        onPictureInPicture()
                    },
                    leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) },
                )
            }

        }
    }
}

@Composable
internal fun PlaybackModeChoices(
    playbackMode: com.theoriacodex.app.viewer.state.ViewerPlaybackMode,
    onPlaybackModeSelected: (com.theoriacodex.app.viewer.state.ViewerPlaybackMode) -> Unit,
    onInteraction: () -> Unit,
) {
    com.theoriacodex.app.viewer.state.ViewerPlaybackMode.entries.forEach { mode ->
        val selected = playbackMode == mode
        val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurface
        DropdownMenuItem(
            modifier = Modifier
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics { this.selected = selected },
            text = { Text(mode.label) },
            leadingIcon = {
                Icon(
                    imageVector = when (mode) {
                        com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP -> Icons.Default.Repeat
                        com.theoriacodex.app.viewer.state.ViewerPlaybackMode.ONCE -> Icons.Default.PlayArrow
                        com.theoriacodex.app.viewer.state.ViewerPlaybackMode.NEXT -> Icons.AutoMirrored.Filled.PlaylistPlay
                    },
                    contentDescription = null,
                )
            },
            colors = MenuDefaults.itemColors(textColor = contentColor, leadingIconColor = contentColor),
            onClick = { onPlaybackModeSelected(mode); onInteraction() },
        )
    }
}

@Composable
private fun VideoQualityChoices(
    videoVariants: List<com.theoriacodex.domain.model.VideoVariant>,
    quality: com.theoriacodex.domain.model.VideoQuality,
    qualityHeight: Int?,
    onQualitySelected: (com.theoriacodex.domain.model.VideoQuality, Int?) -> Unit,
) {
            if (videoVariants.isNotEmpty()) {
                com.theoriacodex.domain.model.VideoQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("Quality: " + com.theoriacodex.app.media.videoQualityLabel(option)) },
                        leadingIcon = { if (qualityHeight == null && quality == option) Icon(Icons.Default.Check, null) },
                        onClick = { onQualitySelected(option, null) },
                    )
                }
                videoVariants.mapNotNull { it.height }.distinct().sortedDescending().forEach { height ->
                    DropdownMenuItem(
                        text = { Text("${height}p") },
                        leadingIcon = { if (qualityHeight == height) Icon(Icons.Default.Check, null) },
                        onClick = { onQualitySelected(quality, height) },
                    )
                }
            }
}
