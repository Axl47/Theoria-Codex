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
import com.theoriacodex.app.codex.ChosenCodex
import com.theoriacodex.app.codex.CodexChosen
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.QuickSave
import com.theoriacodex.app.codex.QuickSaveRecord
import com.theoriacodex.app.codex.SAVE_MENU_CODICES
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.app.codex.save
import com.theoriacodex.app.related.LocalRelatedPostsSupport
import com.theoriacodex.app.ui.components.CollectTargetVisual
import com.theoriacodex.app.ui.components.CollectTargets
import com.theoriacodex.app.ui.components.CollectTargetsLayout
import com.theoriacodex.app.ui.components.CollectTargetsState
import com.theoriacodex.app.ui.components.rememberCollectTargetsState
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.ui.navigation.LocalTrailOpener
import com.theoriacodex.app.ui.navigation.TrailOpener
import com.theoriacodex.app.ui.navigation.TrailOutcome
import com.theoriacodex.app.ui.navigation.TrailRequest
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun LiftOverlay(
    session: LiftSession,
    onNewCodex: (onChosen: suspend (ChosenCodex.New) -> Unit) -> Unit,
    onOpenPicker: (Post, Query?, CodexChosen) -> Unit,
    onFailure: (String) -> Unit,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
) {
    LiftDropEffect(session, LiftSaveRoutes(onNewCodex, onOpenPicker), onFailure, onSaved)
    val (lift, settling) = when (val phase = session.phase) {
        LiftPhase.Idle -> return
        is LiftPhase.Lifted -> phase.lift to null
        is LiftPhase.Settling -> phase.lift to phase
    }
    key(lift) { LiftStage(session, lift, settling) }
}

private class LiftSaveRoutes(
    val newCodex: (suspend (ChosenCodex.New) -> Unit) -> Unit,
    val openPicker: (Post, Query?, CodexChosen) -> Unit,
)

@Composable
private fun LiftDropEffect(
    session: LiftSession,
    routes: LiftSaveRoutes,
    onFailure: (String) -> Unit,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
) {
    val quickSave = LocalQuickSave.current
    val trails = LocalTrailOpener.current
    val saveRoutes by rememberUpdatedState(routes)
    val failure by rememberUpdatedState(onFailure)
    val saved by rememberUpdatedState(onSaved)
    val scope = rememberCoroutineScope()
    LaunchedEffect(session, quickSave, trails) {
        session.drops.collect { drop ->
            val saveTo: suspend (ChosenCodex) -> Unit = { codex -> saveFailure(drop, codex, quickSave, saved)?.let(failure) }
            when (drop.action) {
                LiftAction.NewCodex -> saveRoutes.newCodex(saveTo)
                LiftAction.OpenPicker -> saveRoutes.openPicker(drop.lift.post, drop.lift.query) { codex -> scope.launch { saveTo(codex) } }
                else -> scope.launch { failureOf(drop, session, quickSave, trails, saved)?.let(failure) }
            }
        }
    }
}

private suspend fun failureOf(
    drop: LiftDrop,
    session: LiftSession,
    quickSave: QuickSave,
    trails: TrailOpener,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
): String? {
    val post = drop.lift.post
    when (val action = drop.action) {
        is LiftAction.Save -> return saveFailure(drop, ChosenCodex.Existing(action.codexId, action.codexName), quickSave, onSaved)
        LiftAction.NewCodex, LiftAction.OpenPicker -> Unit
        LiftAction.ToggleLike -> drop.lift.like?.toggle?.invoke()
        LiftAction.Similar -> when (session.whilePending { trails.openTrail(TrailRequest.Similar(post)) }) {
            TrailOutcome.Opened -> Unit
            TrailOutcome.Empty -> return "No similar posts found"
            TrailOutcome.Failed -> return "Couldn't load similar posts"
        }
    }
    return null
}

private suspend fun saveFailure(
    drop: LiftDrop,
    codex: ChosenCodex,
    quickSave: QuickSave,
    onSaved: suspend (QuickSaveRecord) -> Boolean,
): String? {
    val record = quickSave.save(drop.lift.post, codex, drop.lift.query) ?: return "Could not save to ${codex.name}"
    if (record.undoable && onSaved(record)) quickSave.undoQuickSave(record)
    return null
}

@Composable
private fun LiftStage(session: LiftSession, lift: LiftedPost, settling: LiftPhase.Settling?) {
    val quickSave = LocalQuickSave.current
    val suggestions by remember(lift.post.id, lift.query, quickSave) {
        quickSave.suggestions(lift.post, lift.query, SAVE_MENU_CODICES)
    }.collectAsStateWithLifecycle<SaveSuggestions?>(null)
    val similarAvailable = LocalRelatedPostsSupport.current(lift.post.id.source)
    val targets = remember(suggestions, lift.like, similarAvailable) {
        liftTargets(suggestions ?: SaveSuggestions.Empty, lift.like, similarAvailable)
    }
    val quickState = rememberCollectTargetsState<LiftAction>()
    val saveState = rememberCollectTargetsState<LiftAction>()
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
