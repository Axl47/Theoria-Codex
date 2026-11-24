package com.theoriacodex.app.codex

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.MoreHoriz
import com.theoriacodex.app.ui.components.CollectTarget
import com.theoriacodex.app.ui.components.CollectTargetVisual

sealed interface SaveTarget {
    data class Codex(val suggestion: SaveSuggestion) : SaveTarget
    data object New : SaveTarget
    data object More : SaveTarget
}

const val SAVE_MENU_CODICES = 3

fun <T> saveMenuTargets(suggestions: List<SaveSuggestion>, payload: (SaveTarget) -> T): List<CollectTarget<T>> =
    suggestions.take(SAVE_MENU_CODICES).map { suggestion ->
        CollectTarget(
            "codex:${suggestion.codexId}", suggestion.name,
            CollectTargetVisual.Cover(suggestion.cover, Icons.Default.Collections), payload(SaveTarget.Codex(suggestion)),
        )
    } + CollectTarget("new", "New", CollectTargetVisual.Icon(Icons.Default.Add), payload(SaveTarget.New)) +
        CollectTarget("more", "More", CollectTargetVisual.Icon(Icons.Default.MoreHoriz), payload(SaveTarget.More))
