package com.theoriacodex.app.codex

import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
internal fun rememberCodexReorderGestures(
    state: CodexGridReorderState,
    onCommit: (List<String>) -> Unit,
    onOpenActions: (String) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val commit by rememberUpdatedState(onCommit)
    val openActions by rememberUpdatedState(onOpenActions)
    val edgeZone = with(LocalDensity.current) { AUTO_SCROLL_EDGE.toPx() }
    val maxStep = with(LocalDensity.current) { AUTO_SCROLL_MAX_STEP.toPx() }
    LaunchedEffect(state, state.dragging) {
        if (state.dragging) state.autoScroll(edgeZone, maxStep, haptics)
    }
    return Modifier.codexReorderGestures(state, haptics, scope, { commit(it) }, { openActions(it) })
}

internal fun Modifier.codexTilePlacement(
    scope: LazyGridItemScope,
    state: CodexGridReorderState,
    key: String,
    animated: Boolean,
): Modifier {
    val lifted = state.liftedKey == key
    val placement = with(scope) {
        animateItem(fadeInSpec = null, placementSpec = TILE_GLIDE.takeIf { animated && !lifted }, fadeOutSpec = null)
    }
    return placement.zIndex(if (lifted) 1f else 0f).graphicsLayer {
        val translation = state.translationOf(key)
        translationX = translation.x
        translationY = translation.y
        if (lifted) {
            scaleX = LIFTED_SCALE
            scaleY = LIFTED_SCALE
            shadowElevation = LIFTED_ELEVATION.toPx()
            shape = LIFTED_SHAPE
        }
    }
}

internal fun Modifier.codexTileMoves(
    state: CodexGridReorderState,
    presented: List<String>?,
    key: String,
    commit: (List<String>) -> Unit,
): Modifier {
    val moves = presented?.let { keys ->
        listOf("Move earlier" to -1, "Move later" to 1)
            .filter { (_, step) -> state.canStep(keys, key, step) }
            .map { (label, step) -> CustomAccessibilityAction(label) { state.stepAndCommit(keys, key, step, commit) } }
    }
    return if (moves.isNullOrEmpty()) this else semantics { customActions = moves }
}

private val TILE_GLIDE = spring<IntOffset>(dampingRatio = 1f, stiffness = 550f)
private const val LIFTED_SCALE = 1.04f
private val LIFTED_ELEVATION = 12.dp
private val LIFTED_SHAPE = RoundedCornerShape(16.dp)
private val AUTO_SCROLL_EDGE = 72.dp
private val AUTO_SCROLL_MAX_STEP = 14.dp
