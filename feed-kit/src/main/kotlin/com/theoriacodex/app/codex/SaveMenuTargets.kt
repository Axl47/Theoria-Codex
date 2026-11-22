package com.theoriacodex.app.codex

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MoreHoriz
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual

/** Where a drag-to-save menu can land: a suggested Codex, New (name a Codex and save there), or More (the full picker). */
sealed interface SaveTarget {
    data class Codex(val suggestion: SaveSuggestion) : SaveTarget
    data object New : SaveTarget
    data object More : SaveTarget
}

/** How many suggested Codices a drag-to-save menu shows before New and More. */
const val SAVE_MENU_CODICES = 3

/**
 * The bubbles of every drag-to-save menu (the Viewer's hold fan, a lifted card's save edge, the triage deck's upward
 * pull): up to [SAVE_MENU_CODICES] of [suggestions], best first, then New and More, so each menu ends the same way.
 * [payload] turns each target into what its surface acts on.
 *
 * Letting go on no target is each surface's own call, because each gesture already means something: the Viewer's hold
 * started from Save, so it opens the picker; a lift is a pick-up, so it cancels; the deck's upward swipe is itself a
 * save, so it saves to the top suggestion.
 */
fun <T> saveMenuTargets(suggestions: List<SaveSuggestion>, payload: (SaveTarget) -> T): List<CollectTarget<T>> =
    suggestions.take(SAVE_MENU_CODICES).map { suggestion ->
        CollectTarget(
            "codex:${suggestion.codexId}", suggestion.name,
            CollectTargetVisual.Cover(suggestion.cover, Icons.Default.Collections), payload(SaveTarget.Codex(suggestion)),
        )
    } + CollectTarget("new", "New", CollectTargetVisual.Icon(Icons.Default.Add), payload(SaveTarget.New)) +
        CollectTarget("more", "More", CollectTargetVisual.Icon(Icons.Default.MoreHoriz), payload(SaveTarget.More))
