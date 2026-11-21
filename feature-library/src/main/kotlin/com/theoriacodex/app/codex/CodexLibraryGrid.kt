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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex

/**
 * The Library's Codex tiles. While a deleted Codex's Undo is live, its offer fills the cell the Codex vacated, so
 * the user finds it where they last saw the Codex. Under Manual sort a long-press drag reorders the tiles in place
 * ([CodexGridReorderState]); releasing a tile where it was lifted opens its menu.
 */
@Composable
internal fun CodexGrid(
    presentation: CodexListPresentation,
    state: CodexListUiState,
    actions: CodexListActions,
) {
    // Survives opening a Codex, so deleting it from its own screen still finds the cell it left.
    var tileOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val vacatedId = rememberVacatedCodexId()
    val gridState = rememberLazyGridState()
    val reorder = remember(gridState) { CodexGridReorderState(gridState, ::isVacatedKey) }
    if (presentation.awaitingSortInputs) return
    val sorted = sortCodicesForLibrary(
        presentation.libraryCodices, presentation.librarySort, presentation.itemCounts, presentation.likesCodexId,
        presentation.libraryUsage.orEmpty(),
    )
    val sortedIds = sorted.map(Codex::codexId)
    // A pending reorder already wrote the order it will arrive in.
    SideEffect { if (reorder.order == null) tileOrder = rememberedTileOrder(tileOrder, sortedIds) }
    val presented = presentedTileKeys(tileOrder, sortedIds, vacatedId)
    if (presented.isEmpty()) return EmptyState("No Codices", Modifier.fillMaxSize())
    val manual = presentation.librarySort == CodexLibrarySort.MANUAL
    SideEffect { reorder.bind(presented, enabled = manual) }
    val commit: (List<String>) -> Unit = { keys ->
        tileOrder = keys.map { it.removePrefix(VACATED_KEY_PREFIX) }
        actions.commitReorder(keys.filterNot(::isVacatedKey))
    }
    ScrollToTopOnTabReselect(TopLevelDestination.Library) { gridState.animateScrollToItem(0) }
    val openActions = { key: String -> state.actionTarget = sorted.firstOrNull { it.codexId == key } }
    AdaptiveMediaGrid(
        modifier = Modifier.fillMaxSize().then(if (manual) rememberCodexReorderGestures(reorder, commit, openActions) else Modifier),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val byId = sorted.associateBy(Codex::codexId)
        items(if (manual) reorder.displayed(presented) else presented, key = { it }) { key ->
            val placement = Modifier.codexTilePlacement(this, reorder, key, animated = manual)
            val codex = byId[key] ?: return@items CodexVacatedTile(key.removePrefix(VACATED_KEY_PREFIX), placement)
            val moves = placement.codexTileMoves(reorder, presented.takeIf { manual }, key, commit)
            CodexLibraryTile(codex, presentation, state, actions, moves, manual)
        }
    }
}

/**
 * The grid's keys: [current] with the cell of deleted Codex [vacatedId] where [shown] last had it, while that Codex
 * is gone.
 */
internal fun presentedTileKeys(shown: List<String>, current: List<String>, vacatedId: String?): List<String> {
    val gone = vacatedId?.takeIf { it !in current } ?: return current
    return current.toMutableList().apply { add(vacatedTileIndex(shown, current, gone), VACATED_KEY_PREFIX + gone) }
}

/** The Codex whose deletion the live offer can undo, if any. */
@Composable
private fun rememberVacatedCodexId(): String? {
    val offer by LocalUndoSlot.current.offer.collectAsStateWithLifecycle()
    return offer?.anchor?.takeIf { it.scope == CodexUndoAnchors.LIBRARY_SCOPE }?.key
}

@Composable
private fun CodexVacatedTile(codexId: String, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().height(CODEX_TILE_HEIGHT).clip(RoundedCornerShape(16.dp))
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

private const val VACATED_KEY_PREFIX = "vacated:"

private fun isVacatedKey(key: String): Boolean = key.startsWith(VACATED_KEY_PREFIX)

private val CODEX_TILE_HEIGHT = 168.dp
