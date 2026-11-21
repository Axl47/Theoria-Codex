package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.CodexCoverImage
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the Viewer's posts are saved from: the search they came from and whether that was For You. */
data class ViewerSaveContext(val query: Query? = null, val fromForYou: Boolean = false)

/** The hold fan's live gesture, shared by the Save button that drives it and [ViewerSaveFanOverlay] that draws it. */
@Stable
internal class ViewerSaveFanState {
    /** The finger in window coordinates while the fan is open, else null. */
    var pointer by mutableStateOf<Offset?>(null)
    var anchor by mutableStateOf(Offset.Zero)
    var targets by mutableStateOf<List<CollectTarget<ViewerSaveFanTarget>>>(emptyList())
    val collect = CollectTargetsState<ViewerSaveFanTarget>()
}

/**
 * The dock's Save button for [post]. Tap saves to the top suggested Codex and then offers Undo for a few seconds; hold
 * opens [fan], where sliding onto a Codex saves there and releasing anywhere else opens the full picker ([onOpenPicker]).
 */
@Composable
internal fun ViewerSaveButton(post: Post, context: ViewerSaveContext, fan: ViewerSaveFanState, onOpenPicker: () -> Unit) {
    val quickSave = LocalQuickSave.current
    val suggestions = rememberSettledSaveSuggestions(quickSave, post, context.query)
    val lastSave by quickSave.lastQuickSave.collectAsStateWithLifecycle()
    val state = viewerSaveButtonState(post.id, suggestions, lastSave, rememberSavedUndoClock(lastSave))
    val scope = rememberCoroutineScope()
    val run: (ViewerSaveCommand) -> Unit = { command ->
        when (command) {
            // A failed save falls back to the picker, which reports its own errors.
            is ViewerSaveCommand.QuickSave -> scope.launch {
                if (quickSave.quickSave(post, command.codexId, context.query, context.fromForYou) == null) onOpenPicker()
            }
            ViewerSaveCommand.Undo -> quickSave.undoLastQuickSave()
            ViewerSaveCommand.OpenPicker -> onOpenPicker()
        }
    }
    val haptics = LocalHapticFeedback.current
    val hold = remember(fan) { ViewerSaveHold(fan, haptics) }
    hold.onTap = { run(state.tapCommand()) }
    hold.onRelease = { run(viewerSaveFanRelease(it)) }
    hold.targets = { viewerSaveFanTargets(suggestions).map { it.collectTarget() } }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(ViewerVeil.Capsule)
            .onGloballyPositioned { hold.coordinates = it }
            .viewerSaveGestures(hold)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = state.description()
                onClick { run(state.tapCommand()); true }
                customActions = listOf(CustomAccessibilityAction("Choose Codex") { onOpenPicker(); true })
            }
            .testTag("viewer-dock-save")
            .height(40.dp)
            .animateContentSize()
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ViewerSaveLeading(state)
        Text(state.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
    }
}

/** Draws the Save button's fan over the screen while a hold is open. */
@Composable
internal fun ViewerSaveFanOverlay(fan: ViewerSaveFanState) {
    val pointer = fan.pointer ?: return
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        CollectTargets(fan.targets, pointer, CollectTargetsLayout.Rising(fan.anchor), Modifier.background(FAN_SCRIM), fan.collect)
    }
}

/** Suggestions for the settled post; the previous post's stay until the new ones arrive, since scoring walks the library. */
@Composable
private fun rememberSettledSaveSuggestions(quickSave: QuickSave, post: Post, query: Query?): SaveSuggestions? {
    var latest by remember(quickSave) { mutableStateOf<SaveSuggestions?>(null) }
    LaunchedEffect(quickSave, post.id, query) {
        delay(SUGGESTION_SETTLE_MS)
        quickSave.suggestions(post, query).collect { latest = it }
    }
    return latest
}

/** The time, re-read when [lastSave] lands and again when its Undo window closes. */
@Composable
private fun rememberSavedUndoClock(lastSave: QuickSaveRecord?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSave) {
        now = System.currentTimeMillis()
        val remaining = lastSave?.let { it.savedAtEpochMs + VIEWER_SAVED_UNDO_MS - now } ?: return@LaunchedEffect
        if (remaining > 0) {
            delay(remaining)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Links the button's long-lived pointer input to the latest callbacks and to the fan. */
private class ViewerSaveHold(private val fan: ViewerSaveFanState, private val haptics: HapticFeedback) {
    var coordinates: LayoutCoordinates? = null
    var targets: () -> List<CollectTarget<ViewerSaveFanTarget>> = { emptyList() }
    var onTap: () -> Unit = {}
    var onRelease: (ViewerSaveFanTarget?) -> Unit = {}

    fun start(local: Offset) {
        val layout = coordinates ?: return
        haptics.performHapticFeedback(TheoriaHaptics.Hold)
        fan.targets = targets()
        fan.anchor = layout.localToWindow(Offset(layout.size.width / 2f, 0f))
        fan.pointer = layout.localToWindow(local)
    }

    fun move(local: Offset) {
        val layout = coordinates ?: return
        if (fan.pointer != null) fan.pointer = layout.localToWindow(local)
    }

    fun end(released: Boolean) {
        if (fan.pointer == null) return
        val target = fan.collect.hovered?.payload
        fan.pointer = null
        if (released) onRelease(target)
    }
}

/** Tap, or hold then slide: the button's own pointer input, so the media's gestures are untouched. */
private fun Modifier.viewerSaveGestures(hold: ViewerSaveHold): Modifier = pointerInput(hold) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() != null }
        if (tapped != null) {
            if (tapped) hold.onTap()
            return@awaitEachGesture
        }
        hold.start(down.position)
        var released = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) {
                released = true
                break
            }
            hold.move(change.position)
        }
        hold.end(released)
    }
}

@Composable
private fun ViewerSaveLeading(state: ViewerSaveButtonState) {
    when (state) {
        is ViewerSaveButtonState.Suggest -> Box {
            CodexCoverImage(state.target.cover, null, Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))) {
                Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.padding(4.dp))
            }
            if (state.inAnyCodex) {
                Icon(
                    Icons.Default.CheckCircle, contentDescription = null, tint = LocalViewerAccent.current,
                    modifier = Modifier.align(Alignment.BottomEnd).size(12.dp).background(Color.Black, CircleShape),
                )
            }
        }
        is ViewerSaveButtonState.Saved -> Icon(Icons.Default.Check, contentDescription = null, tint = LocalViewerAccent.current, modifier = Modifier.size(28.dp).padding(2.dp))
        is ViewerSaveButtonState.NoTarget -> Icon(
            if (state.inAnyCodex) Icons.Default.Bookmark else Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(28.dp).padding(2.dp),
        )
        ViewerSaveButtonState.Loading -> Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(28.dp).padding(2.dp))
    }
}

private fun ViewerSaveButtonState.label(): String = when (this) {
    is ViewerSaveButtonState.Suggest -> target.name
    is ViewerSaveButtonState.Saved -> if (record.undoable) "Saved · Undo" else "Saved"
    is ViewerSaveButtonState.NoTarget -> if (inAnyCodex) "Saved" else "Save"
    ViewerSaveButtonState.Loading -> "Save"
}

private fun ViewerSaveButtonState.description(): String = when (this) {
    is ViewerSaveButtonState.Suggest -> "Save to ${target.name}" + if (inAnyCodex) ", already in a Codex" else ""
    is ViewerSaveButtonState.Saved -> "Saved to ${record.codexName}" + if (record.undoable) ", undo" else ""
    is ViewerSaveButtonState.NoTarget -> if (inAnyCodex) "In every Codex, save to Codex" else "Save to Codex"
    ViewerSaveButtonState.Loading -> "Save to Codex"
}

private fun ViewerSaveFanTarget.collectTarget(): CollectTarget<ViewerSaveFanTarget> = when (this) {
    is ViewerSaveFanTarget.Codex -> CollectTarget(
        "codex:${suggestion.codexId}", suggestion.name, CollectTargetVisual.Cover(suggestion.cover, Icons.Outlined.Collections), this,
    )
    ViewerSaveFanTarget.New -> CollectTarget("new", "New", CollectTargetVisual.Icon(Icons.Default.Add), this)
}

private const val SUGGESTION_SETTLE_MS = 250L
private val FAN_SCRIM = Color.Black.copy(alpha = 0.35f)
