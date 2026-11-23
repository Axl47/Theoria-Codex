package com.theoriacodex.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEpisodeChoice
import com.theoriacodex.app.viewer.state.ViewerPanel
import com.theoriacodex.app.viewer.state.ViewerSubtitleChoice
import com.theoriacodex.domain.model.MediaStream
import java.util.Locale

/**
 * The subtitle and audio tracks of the episode on screen. Its player publishes them ([FollowViewerActiveTracks]) for the
 * episode controls, which live in the screen's chrome rather than in the player, and every player followed gets the
 * user's [choice] as its tracks arrive: Media3 never shows an external subtitle by itself, so without it subtitles
 * would need turning on again for every stream, recovery and episode.
 */
@Stable
internal class ViewerActiveTracks {
    private var player: Player? = null
    var tracks by mutableStateOf(PlaybackTracks())
        private set

    var choice = ViewerEpisodeChoice()
        set(value) {
            if (field == value) return
            field = value
            player?.let(::apply)
        }

    /** Follows [player]'s tracks until the returned handle closes; a later call takes over from it. */
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

/** A selection to make on a player so it plays the user's [ViewerEpisodeChoice]. */
internal sealed interface ViewerTrackChange {
    /** Shows subtitle track [id]; null turns subtitles off. */
    data class Subtitle(val id: String?) : ViewerTrackChange
    data class Audio(val id: String) : ViewerTrackChange
}

/**
 * What a player offering [tracks] must change to play this choice: nothing it already plays, and nothing for a label
 * this episode does not offer, which keeps the episode's own choice. With no subtitle chosen and none showing, a
 * side-loaded one shows ([defaultSubtitle]); subtitles inside the stream stay Media3's choice.
 */
internal fun ViewerEpisodeChoice.trackChanges(tracks: PlaybackTracks, locale: Locale = Locale.getDefault()): List<ViewerTrackChange> = buildList {
    when (val wanted = subtitle) {
        null -> if (tracks.subtitles.none { it.selected }) tracks.subtitles.defaultSubtitle(locale)?.let { add(ViewerTrackChange.Subtitle(it.id)) }
        ViewerSubtitleChoice.Off -> if (tracks.subtitles.any { it.selected }) add(ViewerTrackChange.Subtitle(null))
        is ViewerSubtitleChoice.Track -> tracks.subtitles.labelled(wanted.label)?.takeUnless { it.selected }
            ?.let { add(ViewerTrackChange.Subtitle(it.id)) }
    }
    audioLabel?.let { label -> tracks.audio.labelled(label)?.takeUnless { it.selected }?.let { add(ViewerTrackChange.Audio(it.id)) } }
}

/** The screen's [ViewerActiveTracks] while a series is watched; null otherwise, so no player is followed. */
internal val LocalViewerActiveTracks = staticCompositionLocalOf<ViewerActiveTracks?> { null }

/** Publishes [player]'s tracks to the screen while it plays the selected episode. */
@Composable
internal fun FollowViewerActiveTracks(player: Player?, active: Boolean) {
    val activeTracks = LocalViewerActiveTracks.current ?: return
    DisposableEffect(activeTracks, player, active) {
        val following = player?.takeIf { active }?.let(activeTracks::follow)
        onDispose { following?.close() }
    }
}

/** [ViewerEpisodeControls] for the selected episode, in the post strip's place while a series is watched. */
@Composable
internal fun ViewerEpisodeBar(
    frame: ViewerFrame,
    activeTracks: ViewerActiveTracks,
    controls: ViewerControls,
    onNext: (() -> Unit)?,
    onAction: (ViewerAction) -> Unit,
) {
    val media = frame.selection.media
    ViewerEpisodeControls(
        streams = media?.streams.orEmpty(),
        // The stream actually playing: a fallback shows here though the session's choice stays.
        currentStream = media?.viewerStream(frame.uiState.controls.episode.streamLabelFor(frame.uiState.currentMedia?.key)),
        tracks = activeTracks.tracks,
        openPanel = frame.uiState.controls.panel,
        onPanelOpenChange = { panel, open ->
            controls.setPanelOpen(panel, open)
            if (open) controls.markInteraction()
        },
        onAction = onAction,
        onNext = onNext?.let { next -> { controls.markInteraction(); next() } },
    )
}

/**
 * An episode's controls, as one row: the stream playing ([currentStream]), which opens the list of its [streams];
 * subtitles, with Off; audio, when there is more than one track to choose; and, at the far end, the next episode,
 * absent on the last ([onNext] null). Each list is a chrome panel ([openPanel]), so it holds the chrome while open and
 * closes the Viewer's other menus. Choices go to the Viewer's state through [onAction].
 */
@Composable
internal fun ViewerEpisodeControls(
    streams: List<MediaStream>,
    currentStream: MediaStream?,
    tracks: PlaybackTracks,
    openPanel: ViewerPanel?,
    onPanelOpenChange: (ViewerPanel, Boolean) -> Unit,
    onAction: (ViewerAction) -> Unit,
    onNext: (() -> Unit)?,
) {
    val menus = ViewerEpisodeMenus(openPanel, onPanelOpenChange, onAction)
    Row(
        Modifier.fillMaxWidth().height(VIEWER_EPISODE_CONTROLS_HEIGHT).padding(horizontal = 8.dp).testTag(VIEWER_EPISODE_CONTROLS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentStream != null) ViewerStreamMenu(streams, currentStream, menus)
        if (tracks.subtitles.isNotEmpty()) ViewerSubtitlesMenu(tracks.subtitles, menus)
        if (tracks.audio.size > 1) ViewerAudioMenu(tracks.audio, menus)
        Spacer(Modifier.weight(1f))
        if (onNext != null) {
            IconButton(onClick = onNext, modifier = Modifier.testTag("viewer-episode-next")) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next episode", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ViewerStreamMenu(streams: List<MediaStream>, current: MediaStream, menus: ViewerEpisodeMenus) {
    val panel = ViewerPanel.EPISODE_STREAM
    ViewerEpisodeMenu(
        panel, menus, VIEWER_EPISODE_STREAM_TAG,
        streams.map { stream -> menus.item(stream.label, stream.label == current.label, panel) { ViewerAction.ChooseStream(stream.label) } },
    ) { open -> ViewerStreamPill(current.label, open) }
}

@Composable
private fun ViewerSubtitlesMenu(subtitles: List<PlaybackTrack>, menus: ViewerEpisodeMenus) {
    val panel = ViewerPanel.EPISODE_SUBTITLES
    val selected = subtitles.firstOrNull { it.selected }
    val off = menus.item("Off", selected == null, panel) { ViewerAction.ChooseSubtitle(ViewerSubtitleChoice.Off) }
    ViewerEpisodeMenu(
        panel, menus, VIEWER_EPISODE_SUBTITLES_TAG,
        listOf(off) + subtitles.map { track ->
            menus.item(track.label, track.selected, panel) { ViewerAction.ChooseSubtitle(ViewerSubtitleChoice.Track(track.label)) }
        },
    ) { open ->
        val icon = if (selected == null) Icons.Default.ClosedCaptionDisabled else Icons.Default.ClosedCaption
        ViewerTrackButton(icon, "Subtitles", selected?.label ?: "Off", VIEWER_EPISODE_SUBTITLES_TAG, open)
    }
}

@Composable
private fun ViewerAudioMenu(audio: List<PlaybackTrack>, menus: ViewerEpisodeMenus) {
    val panel = ViewerPanel.EPISODE_AUDIO
    ViewerEpisodeMenu(
        panel, menus, VIEWER_EPISODE_AUDIO_TAG,
        audio.map { track -> menus.item(track.label, track.selected, panel) { ViewerAction.ChooseAudio(track.label) } },
    ) { open -> ViewerTrackButton(Icons.Default.Audiotrack, "Audio", audio.firstOrNull { it.selected }?.label.orEmpty(), VIEWER_EPISODE_AUDIO_TAG, open) }
}

/** How the episode lists open, close and choose: as chrome panels, with choices sent through [onAction]. */
private class ViewerEpisodeMenus(
    val openPanel: ViewerPanel?,
    val onPanelOpenChange: (ViewerPanel, Boolean) -> Unit,
    private val onAction: (ViewerAction) -> Unit,
) {
    /** An entry of [panel]'s list. Picking what already plays only closes it: the same request would not replace a failed player. */
    fun item(label: String, selected: Boolean, panel: ViewerPanel, choose: () -> ViewerAction) =
        ViewerEpisodeMenuItem(label, selected) { if (selected) onPanelOpenChange(panel, false) else onAction(choose()) }
}

/** One entry of an episode list: [label], checked when [selected]. */
private class ViewerEpisodeMenuItem(val label: String, val selected: Boolean, val choose: () -> Unit)

/**
 * An episode list in the chrome panel [panel], under the control [anchor] draws, which opens it. Each entry is tagged
 * `[tag]:[label]`.
 */
@Composable
private fun ViewerEpisodeMenu(
    panel: ViewerPanel,
    menus: ViewerEpisodeMenus,
    tag: String,
    items: List<ViewerEpisodeMenuItem>,
    anchor: @Composable (open: () -> Unit) -> Unit,
) {
    Box {
        anchor { menus.onPanelOpenChange(panel, true) }
        DropdownMenu(expanded = menus.openPanel == panel, onDismissRequest = { menus.onPanelOpenChange(panel, false) }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { if (item.selected) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = item.choose,
                    modifier = Modifier.testTag("$tag:${item.label}"),
                )
            }
        }
    }
}

/**
 * The pill naming the stream playing; the whole 48 dp row height is its touch target. It is one accessibility node, a
 * button described `Stream` whose text is [label]; the label is its state too, which TalkBack reads after the
 * description where it skips the text.
 */
@Composable
private fun ViewerStreamPill(label: String, open: () -> Unit) {
    Box(
        // The tag comes first: clearing semantics drops every semantics modifier after it, the tag included.
        Modifier.heightIn(min = 48.dp)
            .testTag(VIEWER_EPISODE_STREAM_TAG)
            .clickable(role = Role.Button, onClickLabel = "Choose stream", onClick = open)
            .clearAndSetSemantics { contentDescription = "Stream"; text = AnnotatedString(label); stateDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(ViewerVeil.Capsule).widthIn(max = 240.dp).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White)
        }
    }
}

/** An icon button that opens a list of tracks; its state reads as the selected track's label. */
@Composable
private fun ViewerTrackButton(icon: ImageVector, description: String, selectedLabel: String, tag: String, open: () -> Unit) {
    IconButton(onClick = open, modifier = Modifier.testTag(tag).semantics { stateDescription = selectedLabel }) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

/**
 * The side-loaded subtitle shown before the user chooses: the first whose label names [locale]'s language, in English,
 * in itself or by its code, as a whole word and ignoring case (`English [CC]`, `español`, `en`); else the first.
 */
internal fun List<PlaybackTrack>.defaultSubtitle(locale: Locale): PlaybackTrack? {
    val sideLoaded = filter { it.sideLoaded }
    val names = listOf(locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale), locale.language).filter(String::isNotBlank)
    val language = Regex(names.joinToString("|", "(?<!\\p{L})(?:", ")(?!\\p{L})") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    return sideLoaded.firstOrNull { language.containsMatchIn(it.label) } ?: sideLoaded.firstOrNull()
}

/** The track a choice names, by its label. */
private fun List<PlaybackTrack>.labelled(label: String): PlaybackTrack? = firstOrNull { it.label == label }

internal val VIEWER_EPISODE_CONTROLS_HEIGHT = 48.dp
internal const val VIEWER_EPISODE_CONTROLS_TAG = "viewer-episode-controls"
internal const val VIEWER_EPISODE_STREAM_TAG = "viewer-episode-stream"
internal const val VIEWER_EPISODE_SUBTITLES_TAG = "viewer-episode-subtitles"
internal const val VIEWER_EPISODE_AUDIO_TAG = "viewer-episode-audio"
