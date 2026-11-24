package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ExpandedBottomSheet
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerSubtitleChoice
import com.theoriacodex.domain.model.MediaStream

@Composable
internal fun ViewerEpisodeSheets(
    streams: List<MediaStream>,
    currentStream: MediaStream?,
    tracks: PlaybackTracks,
    playbackRate: ViewerPlaybackRate,
    openPanel: ViewerPanel?,
    onDismiss: (ViewerPanel) -> Unit,
    onAction: (ViewerAction) -> Unit,
) {
    when (openPanel) {
        ViewerPanel.EPISODE_STREAM -> ViewerStreamSheet(streams, currentStream, { onDismiss(ViewerPanel.EPISODE_STREAM) }, onAction)
        ViewerPanel.EPISODE_SUBTITLES -> ViewerSubtitleSheet(tracks.subtitles, { onDismiss(ViewerPanel.EPISODE_SUBTITLES) }, onAction)
        ViewerPanel.EPISODE_AUDIO -> ViewerAudioSheet(tracks.audio, { onDismiss(ViewerPanel.EPISODE_AUDIO) }, onAction)
        ViewerPanel.PLAYBACK_SETTINGS -> ViewerSpeedSheet(playbackRate, { onDismiss(ViewerPanel.PLAYBACK_SETTINGS) }) {
            onAction(ViewerAction.SetPlaybackRate(it.speed))
        }
        else -> Unit
    }
}

@Composable
private fun ViewerStreamSheet(
    streams: List<MediaStream>,
    current: MediaStream?,
    onDismiss: () -> Unit,
    onAction: (ViewerAction) -> Unit,
) {
    if (streams.isEmpty() || current == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    EpisodeSheetFrame(title = "Servers", onDismiss = onDismiss) {
        streams.forEach { stream ->
            val active = stream.label == current.label
            EpisodeCheckRow(
                label = stream.label,
                selected = active,
                tag = "$VIEWER_EPISODE_STREAM_TAG:${stream.label}",
                onClick = { if (active) onDismiss() else onAction(ViewerAction.ChooseStream(stream.label)) },
            )
        }
    }
}

@Composable
private fun ViewerSubtitleSheet(
    subtitles: List<PlaybackTrack>,
    onDismiss: () -> Unit,
    onAction: (ViewerAction) -> Unit,
) {
    if (subtitles.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val active = subtitles.firstOrNull { it.selected }
    EpisodeSheetFrame(title = "Subtitles", onDismiss = onDismiss) {
        EpisodeRadioRow(
            label = "Off",
            selected = active == null,
            tag = "$VIEWER_EPISODE_SUBTITLES_TAG:Off",
            onClick = { if (active == null) onDismiss() else onAction(ViewerAction.ChooseSubtitle(ViewerSubtitleChoice.Off)) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        subtitles.forEach { track ->
            EpisodeRadioRow(
                label = track.label,
                selected = track.selected,
                tag = "$VIEWER_EPISODE_SUBTITLES_TAG:${track.label}",
                onClick = { if (track.selected) onDismiss() else onAction(ViewerAction.ChooseSubtitle(ViewerSubtitleChoice.Track(track.label))) },
            )
        }
    }
}

@Composable
private fun ViewerAudioSheet(
    audio: List<PlaybackTrack>,
    onDismiss: () -> Unit,
    onAction: (ViewerAction) -> Unit,
) {
    if (audio.size <= 1) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    EpisodeSheetFrame(title = "Audio", onDismiss = onDismiss) {
        audio.forEach { track ->
            EpisodeRadioRow(
                label = track.label,
                selected = track.selected,
                tag = "$VIEWER_EPISODE_AUDIO_TAG:${track.label}",
                onClick = { if (track.selected) onDismiss() else onAction(ViewerAction.ChooseAudio(track.label)) },
            )
        }
    }
}

@Composable
private fun ViewerSpeedSheet(
    rate: ViewerPlaybackRate,
    onDismiss: () -> Unit,
    onRateSelected: (ViewerPlaybackRate) -> Unit,
) {
    EpisodeSheetFrame(title = "Playback speed", onDismiss = onDismiss) {
        PlaybackRateSlider(
            rate = rate,
            modifier = Modifier.fillMaxWidth(),
            onRateSelected = onRateSelected,
        )
    }
}

@Composable
private fun EpisodeSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ExpandedBottomSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun EpisodeCheckRow(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = accent)
    }
}

@Composable
private fun EpisodeRadioRow(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .testTag(tag)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
