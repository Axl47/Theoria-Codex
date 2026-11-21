package com.theoriacodex.app.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.ScrollToTopOnTabReselect
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.components.AdaptiveMediaGrid
import com.theoriacodex.app.ui.components.EmptyState
import com.theoriacodex.app.ui.undo.LocalUndoSlot
import com.theoriacodex.app.ui.undo.UndoRow
import com.theoriacodex.domain.model.Codex

/**
 * The Library's Codex tiles. While a deleted Codex's Undo is live, its offer fills the cell the Codex vacated, so
 * the user finds it where they last saw the Codex.
 */
@Composable
internal fun CodexGrid(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    val codices = presentation.libraryCodices
    // Survives opening a Codex, so deleting it from its own screen still finds the cell it left.
    var tileOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val vacatedId = rememberVacatedCodexId()
    // A first frame in manual order would move the restored scroll anchor to the wrong Codex.
    val awaitingCounts = presentation.librarySort.usesItemCounts && codices.any { it.codexId !in presentation.itemCounts }
    val awaitingUsage = presentation.librarySort.usesUsage && presentation.libraryUsage == null
    if (awaitingCounts || awaitingUsage) return
    val sorted = sortCodicesForLibrary(
        codices, presentation.librarySort, presentation.itemCounts, presentation.likesCodexId,
        presentation.libraryUsage.orEmpty(),
    )
    val sortedIds = sorted.map(Codex::codexId)
    SideEffect { tileOrder = rememberedTileOrder(tileOrder, sortedIds) }
    val vacatedAt = vacatedId?.takeIf { it !in sortedIds }?.let { vacatedTileIndex(tileOrder, sortedIds, it) }
    if (sorted.isEmpty() && vacatedAt == null) return EmptyState("No Codices", Modifier.fillMaxSize())
    val gridState = rememberLazyGridState()
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { gridState.animateScrollToItem(0) }
    AdaptiveMediaGrid(
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        sorted.forEachIndexed { index, codex ->
            if (index == vacatedAt) item(key = "vacated:$vacatedId") { CodexVacatedTile(checkNotNull(vacatedId)) }
            item(key = codex.codexId) { CodexLibraryTile(codex, presentation, state, actions) }
        }
        if (vacatedAt == sorted.size) item(key = "vacated:$vacatedId") { CodexVacatedTile(checkNotNull(vacatedId)) }
    }
}

/** The Codex whose deletion the live offer can undo, if any. */
@Composable
private fun rememberVacatedCodexId(): String? {
    val offer by LocalUndoSlot.current.offer.collectAsStateWithLifecycle()
    return offer?.anchor?.takeIf { it.scope == CodexUndoAnchors.LIBRARY_SCOPE }?.key
}

@Composable
private fun CodexVacatedTile(codexId: String) {
    Box(
        Modifier.fillMaxWidth().height(CODEX_TILE_HEIGHT).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        UndoRow(CodexUndoAnchors.tile(codexId))
    }
}

@Composable
private fun CodexLibraryTile(
    codex: Codex,
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    CodexCoverTile(
        codexId = codex.codexId,
        name = codex.name,
        itemCount = presentation.itemCounts[codex.codexId] ?: 0,
        covers = presentation.coverCandidates[codex.codexId].orEmpty(),
        onOpen = { actions.openCodex(codex.codexId) },
        onOpenActions = { state.actionTarget = codex },
        modifier = Modifier.fillMaxWidth().height(CODEX_TILE_HEIGHT),
        onTopUpOffline = actions.makeAvailableOffline?.let { topUp -> { topUp(codex.codexId) } },
    )
}

/**
 * The tile order to remember: [shown] while [current] only dropped Codices from it, so a deleted Codex's place
 * survives; otherwise [current].
 */
internal fun rememberedTileOrder(shown: List<String>, current: List<String>): List<String> {
    val present = current.toSet()
    return if (shown.filter { it in present } == current) shown else current
}

/**
 * Where the cell of Codex [vacatedId] goes among [current]: after the Codices that preceded it in [shown] and are
 * still there. First when [shown] never had it.
 */
internal fun vacatedTileIndex(shown: List<String>, current: List<String>, vacatedId: String): Int {
    val before = shown.indexOf(vacatedId)
    if (before < 0) return 0
    val present = current.toSet()
    return shown.take(before).count { it in present }
}

private val CODEX_TILE_HEIGHT = 168.dp
