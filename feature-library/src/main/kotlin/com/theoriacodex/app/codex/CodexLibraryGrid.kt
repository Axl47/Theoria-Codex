package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.AdaptiveMediaGrid
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.undo.DeferredRemovalRow
import com.theoriacodex.app.ui.undo.PendingRemoval
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex

/**
 * The Library's Codex tiles. A Codex waiting to be deleted keeps its cell, drawn as its Undo, until the Library is
 * left. Under Manual sort a long-press drag reorders the tiles in place ([CodexGridReorderState]); releasing a tile
 * where it was lifted opens its menu.
 */
@Composable
internal fun CodexGrid(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val deleting by rememberUpdatedState(presentation.pendingDeletes)
    val gridState = rememberLazyGridState()
    val reorder = remember(gridState) { CodexGridReorderState(gridState) { it in deleting } }
    LaunchedEffect(reorder, presentation.reorderFailures) { presentation.reorderFailures.collect(reorder::commitFailed) }
    if (presentation.awaitingSortInputs) return
    val sorted = sortCodicesForLibrary(
        presentation.libraryCodices, presentation.librarySort, presentation.itemCounts, presentation.likesCodexId,
        presentation.libraryUsage.orEmpty(),
    )
    val presented = sorted.map(Codex::codexId)
    if (presented.isEmpty()) return EmptyState("No Codices", Modifier.fillMaxSize())
    val manual = presentation.librarySort == CodexLibrarySort.MANUAL
    SideEffect { reorder.bind(presented, enabled = manual) }
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { gridState.animateScrollToItem(0) }
    val openActions = { key: String -> state.actionTarget = sorted.firstOrNull { it.codexId == key } }
    AdaptiveMediaGrid(
        modifier = Modifier.fillMaxSize()
            .then(if (manual) rememberCodexReorderGestures(reorder, actions.commitReorder, openActions) else Modifier),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val byId = sorted.associateBy(Codex::codexId)
        items(if (manual) reorder.displayed(presented) else presented, key = { it }) { key ->
            val placement = Modifier.codexTilePlacement(this, reorder, key, animated = manual)
            val codex = byId.getValue(key)
            val deletion = deleting[key]
            if (deletion != null) return@items CodexVacatedTile(deletion, placement) { actions.actOnDelete(key) }
            val moves = placement.codexTileMoves(reorder, presented.takeIf { manual }, key, actions.commitReorder)
            CodexLibraryTile(codex, presentation, state, actions, moves, manual)
        }
    }
}

@Composable
private fun CodexVacatedTile(deletion: PendingRemoval, modifier: Modifier, onAct: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(CODEX_TILE_HEIGHT).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        DeferredRemovalRow(deletion, onAct)
    }
}

@Composable
private fun CodexLibraryTile(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
    modifier: Modifier,
    manual: Boolean,
) {
    CodexCoverTile(
        codexId = codex.codexId,
        name = codex.name,
        itemCount = presentation.itemCounts[codex.codexId] ?: 0,
        covers = presentation.coverCandidates[codex.codexId].orEmpty(),
        onOpen = { actions.openCodex(codex.codexId) },
        onOpenActions = { state.actionTarget = codex },
        modifier = modifier.fillMaxWidth().height(CODEX_TILE_HEIGHT),
        onTopUpOffline = actions.makeAvailableOffline?.let { topUp -> { topUp(codex.codexId) } },
        longPressOpensActions = !manual,
    )
}

private val CODEX_TILE_HEIGHT = 168.dp
