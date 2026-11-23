package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.theoriacodex.domain.model.MediaStream

/**
 * The subtitle and audio tracks of the video on screen. Its player publishes them ([FollowViewerActiveTracks]) for the
 * episode controls, which live in the screen's chrome rather than in the player.
 */
@Stable
internal class ViewerActiveTracks {
    private var player: Player? = null
    var tracks by mutableStateOf(PlaybackTracks())
        private set

    fun selectSubtitle(id: String?) {
        player?.selectSubtitle(id)
    }

    fun selectAudio(id: String) {
        player?.selectAudio(id)
    }

    /** Follows [player]'s tracks until the returned handle closes; a later call takes over from it. */
    fun follow(player: Player): AutoCloseable {
        this.player = player
        tracks = player.playbackTracks()
        val listener = object : Player.Listener {
            override fun onTracksChanged(changed: Tracks) {
                if (this@ViewerActiveTracks.player === player) tracks = player.playbackTracks()
            }
        }
        player.addListener(listener)
        return AutoCloseable {
            player.removeListener(listener)
            if (this.player === player) {
                this.player = null
                tracks = PlaybackTracks()
            }
        }
    }
}

/** The screen's [ViewerActiveTracks]; null outside a Viewer screen. */
internal val LocalViewerActiveTracks = staticCompositionLocalOf<ViewerActiveTracks?> { null }

/** Publishes [player]'s tracks to the screen while it plays the selected media. */
@Composable
internal fun FollowViewerActiveTracks(player: Player?, active: Boolean) {
    val activeTracks = LocalViewerActiveTracks.current
    DisposableEffect(activeTracks, player, active) {
        val following = player?.takeIf { active }?.let { activeTracks?.follow(it) }
        onDispose { following?.close() }
    }
}

/** [ViewerEpisodeControls] for the selected episode, in the post strip's place while a series is watched. */
@Composable
internal fun ViewerEpisodeBar(
    frame: ViewerFrame,
    activeTracks: ViewerActiveTracks,
    streamLabel: MutableState<String?>,
    onNext: (() -> Unit)?,
    onInteraction: () -> Unit,
) {
    val media = frame.selection.media
    ViewerEpisodeControls(
        streams = media?.streams.orEmpty(),
        currentStream = media?.viewerStream(streamLabel.value),
        onStreamSelected = { streamLabel.value = it.label },
        tracks = activeTracks.tracks,
        onSubtitleSelected = activeTracks::selectSubtitle,
        onAudioSelected = activeTracks::selectAudio,
        onNext = onNext,
        onInteraction = onInteraction,
    )
}

/**
 * An episode's controls: the stream playing ([currentStream]), which opens the list of its [streams]; subtitles, with
 * Off; audio, when there is more than one track to choose; and the next episode, absent on the last ([onNext] null).
 * Picking a stream keeps the position: its player picks up where the last one stopped.
 */
@Composable
internal fun ViewerEpisodeControls(
    streams: List<MediaStream>,
    currentStream: MediaStream?,
    onStreamSelected: (MediaStream) -> Unit,
    tracks: PlaybackTracks,
    onSubtitleSelected: (String?) -> Unit,
    onAudioSelected: (String) -> Unit,
    onNext: (() -> Unit)?,
    onInteraction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(VIEWER_EPISODE_CONTROLS_HEIGHT).padding(horizontal = 8.dp).testTag(VIEWER_EPISODE_CONTROLS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentStream != null) ViewerStreamPicker(streams, currentStream, onStreamSelected, onInteraction)
        Spacer(Modifier.weight(1f))
        if (tracks.subtitles.isNotEmpty()) {
            val selected = tracks.subtitles.firstOrNull { it.selected }
            ViewerTrackPicker(
                icon = if (selected == null) Icons.Default.ClosedCaptionDisabled else Icons.Default.ClosedCaption,
                description = "Subtitles", tag = "viewer-episode-subtitles",
                choices = listOf(null to "Off") + tracks.subtitles.mapIndexed { index, track -> track.id to track.choiceLabel(index) },
                selected = selected?.id, onSelected = onSubtitleSelected, onInteraction = onInteraction,
            )
        }
        if (tracks.audio.size > 1) {
            ViewerTrackPicker(
                icon = Icons.Default.Audiotrack, description = "Audio", tag = "viewer-episode-audio",
                choices = tracks.audio.mapIndexed { index, track -> track.id to track.choiceLabel(index) },
                selected = tracks.audio.firstOrNull { it.selected }?.id,
                onSelected = { id -> id?.let(onAudioSelected) }, onInteraction = onInteraction,
            )
        }
        if (onNext != null) {
            IconButton(onClick = { onInteraction(); onNext() }, modifier = Modifier.testTag("viewer-episode-next")) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next episode", tint = Color.White)
            }
        }
    }
}

/** The pill naming the stream playing; it opens the episode's streams. */
@Composable
private fun ViewerStreamPicker(
    streams: List<MediaStream>,
    current: MediaStream,
    onSelected: (MediaStream) -> Unit,
    onInteraction: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).background(ViewerVeil.Capsule)
                .clickable(onClickLabel = "Choose stream") { onInteraction(); expanded = true }
                .widthIn(max = 240.dp).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                .testTag("viewer-episode-stream"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current.label, color = Color.White, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            streams.forEach { stream ->
                DropdownMenuItem(
                    text = { Text(stream.label) },
                    leadingIcon = { if (stream.label == current.label) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onInteraction()
                        if (stream.label != current.label) onSelected(stream)
                    },
                )
            }
        }
    }
}

/** An icon button that opens a list of tracks; its state reads as the selected track's label, or Off. */
@Composable
private fun ViewerTrackPicker(
    icon: ImageVector,
    description: String,
    tag: String,
    choices: List<Pair<String?, String>>,
    selected: String?,
    onSelected: (String?) -> Unit,
    onInteraction: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == selected }?.second.orEmpty()
    Box {
        IconButton(
            onClick = { onInteraction(); expanded = true },
            modifier = Modifier.testTag(tag).semantics { stateDescription = selectedLabel },
        ) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { if (id == selected) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onInteraction()
                        onSelected(id)
                    },
                )
            }
        }
    }
}

/** A track's name, or its place in the list when the stream names it neither by label nor by language. */
private fun PlaybackTrack.choiceLabel(index: Int): String = label.ifBlank { "Track ${index + 1}" }

internal val VIEWER_EPISODE_CONTROLS_HEIGHT = 48.dp
internal const val VIEWER_EPISODE_CONTROLS_TAG = "viewer-episode-controls"
