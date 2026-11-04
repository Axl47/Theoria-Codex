package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.ListReorderHandle
import com.theoriacodex.app.ui.components.SingleChoiceMenu
import com.theoriacodex.data.repository.CodexLibrarySort
import com.theoriacodex.domain.model.Codex

@Composable
internal fun CodexReorderHandle(codex: Codex, index: Int, state: CodexListUiState) {
    ListReorderHandle(codex.codexId, codex.name, index, state.ordering)
}

/** Selects the shared Library sort; dragging is only offered while the sort is Manual. */
@Composable
internal fun CodexLibrarySortSelector(sort: CodexLibrarySort, onChange: (CodexLibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box {
            TextButton(onClick = { expanded = true }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, Modifier.size(18.dp))
                    Text("Sort: ${sort.label}")
                }
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
        if (sort != CodexLibrarySort.MANUAL) {
            Text(
                "Choose Manual to drag Codices into your own order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
