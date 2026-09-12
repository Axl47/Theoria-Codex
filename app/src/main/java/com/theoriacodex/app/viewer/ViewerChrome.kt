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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    indexLabel: String,
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
    invertScrollOptionVisible: Boolean,
    invertMultiImageScrollDirection: Boolean,
    onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleLike: (() -> Unit)? = null,
    quality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.AUTO,
    playbackMode: com.theoriacodex.app.viewer.state.ViewerPlaybackMode = com.theoriacodex.app.viewer.state.ViewerPlaybackMode.LOOP,
    onPlaybackModeSelected: (com.theoriacodex.app.viewer.state.ViewerPlaybackMode) -> Unit = {},
    qualityHeight: Int? = null,
    videoVariants: List<com.theoriacodex.domain.model.VideoVariant> = emptyList(),
    onQualitySelected: (com.theoriacodex.domain.model.VideoQuality, Int?) -> Unit = { _, _ -> },
) {
    SecondaryScreenAppBar(
        modifier = modifier,
        title = "$source • $indexLabel",
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
            invertScrollOptionVisible = invertScrollOptionVisible,
            invertMultiImageScrollDirection = invertMultiImageScrollDirection,
            onInvertMultiImageScrollDirectionChange = onInvertMultiImageScrollDirectionChange,
            downloadEnabled = downloadEnabled,
            onDownload = onDownload,
            onInfo = onInfo,
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
private fun PlaybackSettingsMenu(
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
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }) {
            Icon(Icons.Default.Settings, contentDescription = "Playback settings")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            com.theoriacodex.app.viewer.state.ViewerPlaybackMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { if (playbackMode == mode) Icon(Icons.Default.Check, null) },
                    onClick = { onPlaybackModeSelected(mode); onExpandedChange(false) },
                )
            }
            if (videoVariants.isNotEmpty()) {
                com.theoriacodex.domain.model.VideoQuality.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text("Quality: " + com.theoriacodex.app.media.videoQualityLabel(option)) },
                        leadingIcon = { if (qualityHeight == null && quality == option) Icon(Icons.Default.Check, null) },
                        onClick = { onQualitySelected(option, null); onExpandedChange(false) },
                    )
                }
                videoVariants.mapNotNull { it.height }.distinct().sortedDescending().forEach { height ->
                    DropdownMenuItem(
                        text = { Text("${height}p") },
                        leadingIcon = { if (qualityHeight == height) Icon(Icons.Default.Check, null) },
                        onClick = { onQualitySelected(quality, height); onExpandedChange(false) },
                    )
                }
            }
            if (playbackRateEnabled) ViewerPlaybackRate.entries.forEach { rate ->
                PlaybackRateMenuItem(
                    rate = rate,
                    selected = rate == playbackRate,
                    onClick = { onPlaybackRateSelected(rate) },
                )
            }
        }
    }
}

@Composable
private fun PlaybackRateMenuItem(
    rate: ViewerPlaybackRate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
    val selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    DropdownMenuItem(
        modifier = Modifier.background(if (selected) selectedContainerColor else Color.Transparent),
        text = { Text(rate.menuLabel) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(
            textColor = if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurface,
            leadingIconColor = if (selected) {
                selectedContentColor
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        leadingIcon = {
            if (selected) Icon(Icons.Default.Check, contentDescription = rate.contentDescription)
        },
    )
}

@Composable
private fun ViewerActionsMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    invertScrollOptionVisible: Boolean,
    invertMultiImageScrollDirection: Boolean,
    onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit,
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
            if (invertScrollOptionVisible) {
                DropdownMenuItem(
                    text = { Text("Invert scroll direction") },
                    onClick = {
                        onInvertMultiImageScrollDirectionChange(!invertMultiImageScrollDirection)
                    },
                    trailingIcon = {
                        Switch(checked = invertMultiImageScrollDirection, onCheckedChange = null)
                    },
                )
            }
        }
    }
}
