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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.CodexCoverImage
import com.theoriacodex.app.codex.LocalNewCodexOpener
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.SAVE_MENU_CODICES
import com.theoriacodex.app.codex.SaveSuggestion
import com.theoriacodex.app.codex.SaveTarget
import com.theoriacodex.app.codex.save
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ViewerSaveContext(val query: Query? = null, val fromForYou: Boolean = false)

@Stable
internal class ViewerSaveFanState {
    var pointer by mutableStateOf<Offset?>(null)
    var anchor by mutableStateOf(Offset.Zero)
    var targets by mutableStateOf<List<CollectTarget<SaveTarget>>>(emptyList())
    val collect = CollectTargetsState<SaveTarget>()

    var suggestions by mutableStateOf<ViewerPostSuggestions?>(null)

    var showingSaved by mutableStateOf(false)

    val holdsChrome: Boolean get() = pointer != null || showingSaved
}

@Composable
internal fun ViewerSaveButton(
    post: Post,
    context: ViewerSaveContext,
    fan: ViewerSaveFanState,
    onInteraction: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    val quickSave = LocalQuickSave.current
    val newCodex = LocalNewCodexOpener.current
    val suggestions = rememberSettledSaveSuggestions(quickSave, post, context.query, fan)
    val lastSave by quickSave.lastQuickSave.collectAsStateWithLifecycle()
    val state = viewerSaveButtonState(post.id, suggestions, lastSave, rememberSavedUndoClock(lastSave))
    val scope = rememberCoroutineScope()
    val run: (ViewerSaveCommand) -> Unit = { command ->
        onInteraction()
        when (command) {
            is ViewerSaveCommand.QuickSave -> scope.launch {
                if (quickSave.quickSave(post, command.codexId, context.query, context.fromForYou) == null) onOpenPicker()
            }
            is ViewerSaveCommand.Undo -> scope.launch(start = CoroutineStart.UNDISPATCHED) { quickSave.undoQuickSave(command.record) }
            ViewerSaveCommand.OpenPicker -> onOpenPicker()
            ViewerSaveCommand.NewCodex -> newCodex.openNewCodex { codex ->
                if (quickSave.save(post, codex, context.query, context.fromForYou) == null) onOpenPicker()
            }
        }
    }
    val haptics = LocalHapticFeedback.current
    val hold = remember(fan) { ViewerSaveHold(fan, haptics) }
    SideEffect { fan.showingSaved = state is ViewerSaveButtonState.Saved }
    DisposableEffect(fan) { onDispose { fan.pointer = null; fan.showingSaved = false } }
    hold.onStart = onInteraction
    hold.onTap = { run(state.tapCommand()) }
    hold.onRelease = { run(viewerSaveFanRelease(it)) }
    hold.targets = { viewerSaveFanTargets(post.id, suggestions) }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(ViewerVeil.Capsule)
            .onGloballyPositioned { hold.coordinates = it }
            .viewerSaveGestures(hold, post.id)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = state.description()
                onClick { run(state.tapCommand()); true }
                customActions = listOf(CustomAccessibilityAction("Choose Codex") { onOpenPicker(); true })
                testTag = "viewer-dock-save"
            }
            .height(40.dp)
            .animateContentSize()
            .padding(start = 6.dp, end = if (state.next() != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val savedIn = state.savedIn()
        if (savedIn != null) ViewerSavedInLabel(savedIn, state.next()) else {
            ViewerSaveLeading(state)
            ViewerSaveLabel(state.label())
        }
    }
}

@Composable
private fun RowScope.ViewerSaveLabel(text: String, color: Color = Color.Unspecified) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = 128.dp),
    )
}

@Composable
private fun RowScope.ViewerSavedInLabel(savedIn: SaveSuggestion, next: SaveSuggestion?) {
    val accent = LocalViewerAccent.current
    Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp).padding(2.dp))
    ViewerSaveLabel(savedIn.name, accent)
    if (next == null) return
    Box {
        CodexCoverImage(next.cover, null, Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))) {
            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.padding(4.dp))
        }
        Icon(
            Icons.Default.Add, contentDescription = null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).size(12.dp).background(Color.Black, CircleShape),
        )
    }
}

@Composable
internal fun ViewerSaveFanOverlay(fan: ViewerSaveFanState) {
    val pointer = fan.pointer ?: return
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        CollectTargets(fan.targets, pointer, CollectTargetsLayout.Rising(fan.anchor), Modifier.background(FAN_SCRIM), fan.collect)
    }
}

@Composable
private fun rememberSettledSaveSuggestions(quickSave: QuickSave, post: Post, query: Query?, fan: ViewerSaveFanState): ViewerPostSuggestions? {
    LaunchedEffect(quickSave, post.id, query, fan) {
        delay(SUGGESTION_SETTLE_MS)
        quickSave.suggestions(post, query, SAVE_MENU_CODICES + 1).collect { fan.suggestions = ViewerPostSuggestions(post.id, it) }
    }
    return fan.suggestions
}

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

private class ViewerSaveHold(private val fan: ViewerSaveFanState, private val haptics: HapticFeedback) {
    var coordinates: LayoutCoordinates? = null
    var targets: () -> List<CollectTarget<SaveTarget>> = { emptyList() }
    var onStart: () -> Unit = {}
    var onTap: () -> Unit = {}
    var onRelease: (SaveTarget?) -> Unit = {}

    fun start(local: Offset) {
        val layout = coordinates ?: return
        haptics.performHapticFeedback(TheoriaHaptics.Hold)
        onStart()
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

private fun Modifier.viewerSaveGestures(hold: ViewerSaveHold, postId: PostId): Modifier = pointerInput(hold, postId) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() != null }
        if (tapped != null) {
            if (tapped) hold.onTap()
            return@awaitEachGesture
        }
        hold.start(down.position)
        var released = false
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    released = true
                    break
                }
                hold.move(change.position)
            }
        } finally {
            hold.end(released)
        }
    }
}

@Composable
private fun ViewerSaveLeading(state: ViewerSaveButtonState) {
    when (state) {
        is ViewerSaveButtonState.Suggest -> CodexCoverImage(state.target.cover, null, Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))) {
            Icon(Icons.Outlined.Collections, contentDescription = null, modifier = Modifier.padding(4.dp))
        }
        is ViewerSaveButtonState.Saved -> Icon(Icons.Default.Check, contentDescription = null, tint = LocalViewerAccent.current, modifier = Modifier.size(28.dp).padding(2.dp))
        is ViewerSaveButtonState.NoTarget -> Icon(Icons.Outlined.BookmarkAdd, contentDescription = null, modifier = Modifier.size(28.dp).padding(2.dp))
        ViewerSaveButtonState.Loading -> Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(28.dp).padding(2.dp))
    }
}

private fun ViewerSaveButtonState.label(): String = when (this) {
    is ViewerSaveButtonState.Suggest -> target.name
    is ViewerSaveButtonState.Saved -> if (record.undoable) "Saved · Undo" else "Saved"
    is ViewerSaveButtonState.NoTarget -> "Save"
    ViewerSaveButtonState.Loading -> "Save"
}

private fun ViewerSaveButtonState.description(): String = when (this) {
    is ViewerSaveButtonState.Suggest -> savedIn?.let { "Saved in ${it.name}. " }.orEmpty() + "Save to ${target.name}"
    is ViewerSaveButtonState.Saved -> "Saved to ${record.codexName}" + if (record.undoable) ", undo" else ""
    is ViewerSaveButtonState.NoTarget -> savedIn?.let { "Saved in ${it.name}. Choose Codex" } ?: "Save to Codex"
    ViewerSaveButtonState.Loading -> "Save to Codex"
}

private const val SUGGESTION_SETTLE_MS = 250L
private val FAN_SCRIM = Color.Black.copy(alpha = 0.35f)
