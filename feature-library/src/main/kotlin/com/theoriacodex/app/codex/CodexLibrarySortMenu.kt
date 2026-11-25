package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.ui.components.SortLabel
import com.theoriacodex.app.ui.components.SortMenu
import com.theoriacodex.app.ui.components.labelOf
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.next

@Composable
internal fun CodexLibrarySortMenu(sort: CodexLibrarySort, onChange: (CodexLibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val label = CodexLibrarySortOptions.labelOf(sort.choice.key)
        SortLabel(
            label,
            sort.choice.direction,
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "$SORT_CODICES: $label" },
            actionLabel = SORT_CODICES,
        )
        SortMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = CodexLibrarySortOptions,
            choice = sort.choice,
            onPick = { key -> onChange(CodexLibrarySort.entries.next(sort, key)) },
        )
    }
}

internal const val SORT_CODICES = "Sort Codices"
