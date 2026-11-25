package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEpisodeChoice
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerSubtitleChoice
import com.theoriacodex.domain.model.MediaStream
import java.util.Locale

@Stable
internal class ViewerActiveTracks {
    private var player: Player? = null
    private var loadingOwner: Any? = null
    var tracks by mutableStateOf(PlaybackTracks())
        private set
    var loading by mutableStateOf(false)
        private set

    var choice = ViewerEpisodeChoice()
        set(value) {
            if (field == value) return
            field = value
            player?.let(::apply)
        }

    fun follow(player: Player): AutoCloseable {
        this.player = player
        apply(player)
        val listener = object : Player.Listener {
            override fun onTracksChanged(changed: Tracks) {
                if (this@ViewerActiveTracks.player === player) apply(player)
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

    fun publishLoading(owner: Any, isLoading: Boolean): AutoCloseable {
        loadingOwner = owner
        loading = isLoading
        return AutoCloseable {
            if (loadingOwner === owner) {
                loadingOwner = null
                loading = false
            }
        }
    }

    private fun apply(player: Player) {
        val offered = player.playbackTracks()
        tracks = offered
        choice.trackChanges(offered).forEach { change ->
            when (change) {
                is ViewerTrackChange.Subtitle -> player.selectSubtitle(change.id)
                is ViewerTrackChange.Audio -> player.selectAudio(change.id)
            }
        }
    }
}

internal sealed interface ViewerTrackChange {
    data class Subtitle(val id: String?) : ViewerTrackChange
    data class Audio(val id: String) : ViewerTrackChange
}

internal fun ViewerEpisodeChoice.trackChanges(tracks: PlaybackTracks, locale: Locale = Locale.getDefault()): List<ViewerTrackChange> = buildList {
    val subtitles = tracks.subtitles
    if (subtitle == ViewerSubtitleChoice.Off) {
        if (subtitles.any { it.selected }) add(ViewerTrackChange.Subtitle(null))
    } else {
        val chosen = (subtitle as? ViewerSubtitleChoice.Track)?.let { subtitles.labelled(it.label) }
        val shown = chosen ?: subtitles.firstOrNull { it.selected } ?: subtitles.defaultSubtitle(locale)
        shown?.takeUnless { it.selected }?.let { add(ViewerTrackChange.Subtitle(it.id)) }
    }
    audioLabel?.let { label -> tracks.audio.labelled(label)?.takeUnless { it.selected }?.let { add(ViewerTrackChange.Audio(it.id)) } }
}

internal val LocalViewerActiveTracks = staticCompositionLocalOf<ViewerActiveTracks?> { null }

@Composable
internal fun FollowViewerActiveTracks(player: Player?, active: Boolean, loading: Boolean = false) {
    val activeTracks = LocalViewerActiveTracks.current ?: return
    val loadingToken = remember { Any() }
    DisposableEffect(activeTracks, player, active) {
        val following = player?.takeIf { active }?.let(activeTracks::follow)
        onDispose { following?.close() }
    }
    DisposableEffect(activeTracks, active, loading) {
        val handle = if (active) activeTracks.publishLoading(loadingToken, loading) else null
        onDispose { handle?.close() }
    }
}

@Composable
internal fun RowScope.ViewerEpisodeTopActions(
    currentStream: MediaStream?,
    tracks: PlaybackTracks,
    moreExpanded: Boolean,
    onOpenPanel: (ViewerPanel) -> Unit,
    onMoreExpandedChange: (Boolean) -> Unit,
    onStartOver: () -> Unit,
) {
    if (tracks.subtitles.isNotEmpty()) {
        val selected = tracks.subtitles.firstOrNull { it.selected }
        val icon = if (selected == null) Icons.Default.ClosedCaptionDisabled else Icons.Default.ClosedCaption
        val tint = if (selected != null) MaterialTheme.colorScheme.primary else Color.White
        ViewerTrackButton(icon, "Subtitles", selected?.label ?: "Off", VIEWER_EPISODE_SUBTITLES_TAG, tint) {
            onOpenPanel(ViewerPanel.EPISODE_SUBTITLES)
        }
    }
    if (tracks.audio.size > 1) {
        val selectedLabel = tracks.audio.firstOrNull { it.selected }?.label.orEmpty()
        ViewerTrackButton(Icons.Default.Audiotrack, "Audio", selectedLabel, VIEWER_EPISODE_AUDIO_TAG, Color.White) {
            onOpenPanel(ViewerPanel.EPISODE_AUDIO)
        }
    }
    if (currentStream != null) {
        ViewerStreamButton(currentStream.label) { onOpenPanel(ViewerPanel.EPISODE_STREAM) }
    }
    Box {
        IconButton(onClick = { onMoreExpandedChange(!moreExpanded) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions", tint = Color.White)
        }
        DropdownMenu(expanded = moreExpanded, onDismissRequest = { onMoreExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("Start over") },
                leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                onClick = {
                    onMoreExpandedChange(false)
                    onStartOver()
                },
            )
        }
    }
}

@Composable
internal fun ViewerEpisodeCenterControls(
    isPlaying: Boolean,
    hideCenterPlayback: Boolean,
    onTogglePlayback: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(VIEWER_EPISODE_CENTER_TAG),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CenterTransportButton(
            icon = Icons.Default.SkipPrevious,
            description = "Previous episode",
            tag = "viewer-episode-previous",
            size = 56.dp,
            iconSize = 30.dp,
            enabled = onPrevious != null,
            onClick = { onPrevious?.invoke() },
        )
        if (hideCenterPlayback) {
            Spacer(Modifier.size(64.dp))
        } else {
            CenterTransportButton(
                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                tag = VIEWER_EPISODE_CENTER_PLAY_TAG,
                size = 64.dp,
                iconSize = 38.dp,
                enabled = true,
                onClick = onTogglePlayback,
            )
        }
        CenterTransportButton(
            icon = Icons.Default.SkipNext,
            description = "Next episode",
            tag = "viewer-episode-next",
            size = 56.dp,
            iconSize = 30.dp,
            enabled = onNext != null,
            onClick = { onNext?.invoke() },
        )
    }
}

@Composable
private fun CenterTransportButton(
    icon: ImageVector,
    description: String,
    tag: String,
    size: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
internal fun ViewerEpisodeBar(
    frame: ViewerFrame,
    controls: ViewerControls,
    rotationOffer: ViewerRotationOffer?,
    onPictureInPicture: (() -> Unit)?,
    onAction: (ViewerAction) -> Unit,
) {
    val rate = closestViewerPlaybackRate(frame.uiState.controls.playback.playbackRate)
    val fillScreen = frame.uiState.controls.fillScreen
    ViewerEpisodeControls(
        playbackRate = rate,
        speedEnabled = frame.uiState.controls.playback.available,
        rotationOffer = rotationOffer,
        showFillToggle = frame.isLandscape,
        fillScreen = fillScreen,
        onLock = { onAction(ViewerAction.SetLocked(true)) },
        onRotate = {
            controls.markInteraction()
            rotationOffer?.rotate?.invoke()
        },
        onOpenSpeed = {
            controls.setPanelOpen(ViewerPanel.PLAYBACK_SETTINGS, true)
            controls.markInteraction()
        },
        onPictureInPicture = onPictureInPicture?.let { pip -> { controls.markInteraction(); pip() } },
        onToggleFill = {
            controls.markInteraction()
            onAction(ViewerAction.ToggleFillScreen)
        },
        onToggleRotationLock = {
            controls.markInteraction()
            rotationOffer?.toggleLock?.invoke()
        },
    )
}

@Composable
internal fun ViewerEpisodeControls(
    playbackRate: ViewerPlaybackRate,
    speedEnabled: Boolean,
    rotationOffer: ViewerRotationOffer?,
    showFillToggle: Boolean,
    fillScreen: Boolean,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onOpenSpeed: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onToggleFill: () -> Unit,
    onToggleRotationLock: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(VIEWER_EPISODE_CONTROLS_HEIGHT).padding(horizontal = 8.dp).testTag(VIEWER_EPISODE_CONTROLS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLock, modifier = Modifier.testTag(VIEWER_LOCK_TAG)) {
            Icon(Icons.Outlined.LockOpen, contentDescription = "Lock controls", tint = Color.White)
        }
        rotationOffer?.let {
            if (it.lock == null) ViewerEpisodeRotateButton(it, onRotate)
            ViewerEpisodeRotationLockButton(it, onToggleRotationLock)
        }
        ViewerEpisodeSpeedPill(playbackRate, speedEnabled, onOpenSpeed)
        Spacer(Modifier.weight(1f))
        onPictureInPicture?.let { ViewerEpisodePipButton(it) }
        if (showFillToggle) ViewerEpisodeFillButton(fillScreen, onToggleFill)
    }
}

@Composable
private fun ViewerEpisodeRotateButton(offer: ViewerRotationOffer, onRotate: () -> Unit) {
    IconButton(onClick = onRotate, modifier = Modifier.testTag(VIEWER_ROTATE_TAG)) {
        ViewerAccentIcon(Icons.Default.ScreenRotation, offer.rotateDescription, accented = offer.fits)
    }
}

@Composable
private fun ViewerEpisodeRotationLockButton(offer: ViewerRotationOffer, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.testTag(VIEWER_ROTATION_LOCK_TAG)) {
        ViewerAccentIcon(offer.lockIcon, offer.lockDescription, accented = offer.lock != null)
    }
}

@Composable
private fun ViewerEpisodePipButton(onPictureInPicture: () -> Unit) {
    IconButton(onClick = onPictureInPicture, modifier = Modifier.testTag(VIEWER_EPISODE_PIP_TAG)) {
        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-picture", tint = Color.White)
    }
}

@Composable
private fun ViewerEpisodeFillButton(fillScreen: Boolean, onToggleFill: () -> Unit) {
    IconButton(onClick = onToggleFill, modifier = Modifier.testTag(VIEWER_EPISODE_FILL_TAG)) {
        Icon(
            imageVector = Icons.Default.FitScreen,
            contentDescription = if (fillScreen) "Fit video" else "Fill screen",
            tint = if (fillScreen) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

@Composable
private fun ViewerEpisodeSpeedPill(rate: ViewerPlaybackRate, enabled: Boolean, onClick: () -> Unit) {
    val label = formatEpisodeSpeed(rate.speed)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .testTag(VIEWER_EPISODE_SPEED_TAG)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Playback speed"
                stateDescription = rate.contentDescription
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = ViewerVeil.Time,
            maxLines = 1,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun formatEpisodeSpeed(speed: Float): String {
    val formatted = if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0" else speed.toString()
    return "$formatted×"
}

@Composable
private fun ViewerStreamButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag(VIEWER_EPISODE_STREAM_TAG)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Choose stream", onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "Stream"
                text = AnnotatedString(label)
                stateDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.HighQuality, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun ViewerTrackButton(icon: ImageVector, description: String, selectedLabel: String, tag: String, tint: Color, open: () -> Unit) {
    IconButton(onClick = open, modifier = Modifier.testTag(tag).semantics { stateDescription = selectedLabel }) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

internal fun List<PlaybackTrack>.defaultSubtitle(locale: Locale): PlaybackTrack? {
    val names = listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale), locale.language).filter(String::isNotBlank)
    val language = Regex(names.joinToString("|", "(?<!\\p{L})(?:", ")(?!\\p{L})") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    return firstOrNull { language.containsMatchIn(it.label) } ?: firstOrNull()
}

private fun List<PlaybackTrack>.labelled(label: String): PlaybackTrack? = firstOrNull { it.label == label }

internal val VIEWER_EPISODE_CONTROLS_HEIGHT = 48.dp
internal const val VIEWER_EPISODE_CONTROLS_TAG = "viewer-episode-controls"
internal const val VIEWER_EPISODE_STREAM_TAG = "viewer-episode-stream"
internal const val VIEWER_EPISODE_SUBTITLES_TAG = "viewer-episode-subtitles"
internal const val VIEWER_EPISODE_AUDIO_TAG = "viewer-episode-audio"
internal const val VIEWER_EPISODE_SPEED_TAG = "viewer-episode-speed"
internal const val VIEWER_EPISODE_PIP_TAG = "viewer-episode-pip"
internal const val VIEWER_EPISODE_FILL_TAG = "viewer-episode-fill"
internal const val VIEWER_EPISODE_CENTER_TAG = "viewer-episode-center"
internal const val VIEWER_EPISODE_CENTER_PLAY_TAG = "viewer-center-playback"
internal const val VIEWER_LOCK_TAG = "viewer-lock"
internal const val VIEWER_UNLOCK_TAG = "viewer-unlock"
