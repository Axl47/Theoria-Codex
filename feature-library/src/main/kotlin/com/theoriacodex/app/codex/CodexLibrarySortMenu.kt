package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.data.repository.CodexLibrarySort

@Composable
internal fun CodexLibrarySortMenu(sort: CodexLibrarySort, onChange: (CodexLibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort Codices")
        }
        SingleChoiceMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            options = CodexLibrarySort.entries,
            selected = sort,
            label = { it.label },
            onSelect = onChange,
        )
    }
}
