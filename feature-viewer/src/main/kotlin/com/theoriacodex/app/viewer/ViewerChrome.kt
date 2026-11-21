package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** The Viewer's top bar: Back, the source and the page index, with [below] (the trail row) under it. */
@Composable
internal fun ViewerChrome(
    source: String,
    indexLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    below: @Composable () -> Unit = {},
) {
    ViewerTopBar(source, indexLabel, onBack, modifier, below)
}

/** Floats over the media on a scrim, unlike the solid bars of secondary screens. */
@Composable
private fun ViewerTopBar(
    source: String,
    indexLabel: String?,
    onBack: () -> Unit,
    modifier: Modifier,
    below: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(VIEWER_TOP_SCRIM_HEIGHT).background(ViewerVeil.TopScrim))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = buildAnnotatedString {
                            append(source)
                            if (indexLabel != null) {
                                withStyle(SpanStyle(color = Color.White.copy(alpha = ViewerVeil.SECONDARY_TEXT_ALPHA), fontWeight = FontWeight.Normal, fontSize = 14.sp, fontFeatureSettings = "tnum")) {
                                    append("  $indexLabel")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                below()
            }
        }
    }
}

private val VIEWER_TOP_SCRIM_HEIGHT = 170.dp

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
