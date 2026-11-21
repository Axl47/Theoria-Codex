package com.theoriacodex.app.lift

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.components.rememberCollectTargetsState
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Draws [session]'s lift above the navigation host and acts on its drops. Mount it once, above everything the
 * cards live in. Saves go through [LocalQuickSave], Like through the card's own toggle, and Similar opens a trail
 * through [LocalTrailOpener], with its target showing progress until the opener returns; New hands the post to
 * [onNewCodex]. [onFailure] gets a message when a drop could not act. [onSaved] confirms a save that can be
 * undone, suspending until the confirmation goes away, and returns true when the user chose Undo. Nothing is
 * composed while no lift is in progress.
 */
@Composable
fun LiftOverlay(
    session: LiftSession,
    onNewCodex: (Post) -> Unit,
    onFailure: (String) -> Unit,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
) {
    LiftDropEffect(session, onNewCodex, onFailure, onSaved)
    val (lift, settling) = when (val phase = session.phase) {
        LiftPhase.Idle -> return
        is LiftPhase.Lifted -> phase.lift to null
        is LiftPhase.Settling -> phase.lift to phase
    }
    key(lift) { LiftStage(session, lift, settling) }
}

@Composable
private fun LiftDropEffect(
    session: LiftSession,
    onNewCodex: (Post) -> Unit,
    onFailure: (String) -> Unit,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
) {
    val quickSave = LocalQuickSave.current
    val trails = LocalTrailOpener.current
    val newCodex by rememberUpdatedState(onNewCodex)
    val failure by rememberUpdatedState(onFailure)
    val saved by rememberUpdatedState(onSaved)
    // Drops outlive the overlay's phases: a save or trail keeps running after the copy has landed.
    val scope = rememberCoroutineScope()
    LaunchedEffect(session, quickSave, trails) {
        session.drops.collect { drop ->
            scope.launch { failureOf(drop, session, quickSave, trails, newCodex, saved)?.let(failure) }
        }
    }
}

/** Acts on [drop] and returns why it failed, or null. */
private suspend fun failureOf(
    drop: LiftDrop,
    session: LiftSession,
    quickSave: QuickSave,
    trails: TrailOpener,
    onNewCodex: (Post) -> Unit,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
): String? {
    val post = drop.lift.post
    when (val action = drop.action) {
        is LiftAction.Save -> {
            val record = quickSave.quickSave(post, action.codexId, drop.lift.query)
                ?: return "Could not save to ${action.codexName}"
            // Undo reverses only this save: a later save has replaced it as the last one.
            // TODO(merge): undo(record)
            if (record.undoable && onSaved(record) && quickSave.lastQuickSave.value == record) quickSave.undoLastQuickSave()
        }
        LiftAction.NewCodex -> onNewCodex(post)
        LiftAction.ToggleLike -> drop.lift.like?.toggle?.invoke()
        LiftAction.Similar -> if (!session.whilePending { trails.openTrail(TrailRequest.Similar(post)) }) return "No similar posts found"
    }
    return null
}

/** The scrim, both target edges and the lifted copy, for one lift from pick-up until it has landed. */
@Composable
private fun LiftStage(session: LiftSession, lift: LiftedPost, settling: LiftPhase.Settling?) {
    val quickSave = LocalQuickSave.current
    // Null until the first ranking arrives: the end edge's targets shift then, so it takes no hover before.
    val suggestions by remember(lift.post.id, lift.query, quickSave) {
        quickSave.suggestions(lift.post, lift.query, LIFT_SAVE_SUGGESTIONS)
    }.collectAsStateWithLifecycle<SaveSuggestions?>(null)
    val similarAvailable = LocalRelatedPostsSupport.current(lift.post.id.source)
    val targets = remember(suggestions, lift.like, similarAvailable) {
        liftTargets(suggestions ?: SaveSuggestions.Empty, lift.like, similarAvailable)
    }
    val quickState = rememberCollectTargetsState<LiftAction>()
    val saveState = rememberCollectTargetsState<LiftAction>()
    // What the user sees highlighted is what a release acts on.
    SideEffect { session.targetAt = { saveState.hovered?.payload ?: quickState.hovered?.payload } }
    val appear = remember { Animatable(0f) }
    val land = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, TheoriaMotion.settleFraction) }
    LaunchedEffect(settling) {
        if (settling == null) return@LaunchedEffect
        land.animateTo(1f, tween(TheoriaMotion.MEDIUM_MS))
        if (settling.action is LiftAction.Save) delay(CONFIRM_HOLD_MS)
        if (settling.action is LiftAction.Similar) snapshotFlow { session.pending }.first { it == null }
        appear.animateTo(0f, tween(TheoriaMotion.SHORT_MS))
        session.settled(settling)
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(Color.Black.copy(alpha = SCRIM_ALPHA)))
        LiftTargetEdges(session, targets, suggestions != null, settling, quickState, saveState, appear)
        LiftedCopy(session, lift, settling, land)
    }
}

/** Both edges of targets; the only part besides the copy that follows the finger. */
@Composable
private fun LiftTargetEdges(
    session: LiftSession,
    targets: LiftTargets,
    suggestionsReady: Boolean,
    settling: LiftPhase.Settling?,
    quickState: CollectTargetsState<LiftAction>,
    saveState: CollectTargetsState<LiftAction>,
    appear: Animatable<Float, *>,
) {
    // Once released, only a target that was dropped on stays highlighted, showing a check if it saved.
    val pointer = if (settling == null) session.hoverPointer else settling.at.takeIf { settling.action != null }
    val confirmed = settling?.action as? LiftAction.Save
    val save = remember(targets.save, confirmed) {
        targets.save.map { if (it.payload == confirmed) it.copy(visual = CollectTargetVisual.Icon(Icons.Default.Check)) else it }
    }
    val similarPending = settling?.action == LiftAction.Similar && session.pending != null
    val quick = remember(targets.quick, similarPending) {
        targets.quick.map { if (similarPending && it.payload == LiftAction.Similar) it.copy(visual = CollectTargetVisual.Busy) else it }
    }
    val slide = with(LocalDensity.current) { TARGET_SLIDE.toPx() }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).graphicsLayer { alpha = appear.value }) {
        CollectTargets(
            quick, pointer, CollectTargetsLayout.EdgeColumn(atEnd = false),
            Modifier.graphicsLayer { translationX = -slide * (1f - appear.value) }, quickState,
        )
        CollectTargets(
            save, pointer.takeIf { suggestionsReady || settling != null }, CollectTargetsLayout.EdgeColumn(atEnd = true),
            Modifier.graphicsLayer { translationX = slide * (1f - appear.value) }, saveState,
        )
    }
}

private val TARGET_SLIDE = 96.dp
private const val SCRIM_ALPHA = 0.45f
private const val CONFIRM_HOLD_MS = 450L
