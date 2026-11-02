package com.theoriacodex.app.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Drag, keyboard activation, and assistive actions share the same reorder draft. */
@Composable
internal fun <T> ListReorderHandle(id: String, title: String, index: Int, state: ReorderListState<T>) {
    var menuExpanded by remember(id) { mutableStateOf(false) }
    val moves = buildList {
        if (index > 0) add("Move up" to -1)
        if (index < state.reorderDraft.lastIndex) add("Move down" to 1)
    }
    Box {
        IconButton(
            modifier = Modifier.pointerInput(id, state.itemHeightPx) {
                detectDragGestures(
                    onDragStart = { menuExpanded = false; state.startDrag(id, index) },
                    onDragCancel = state::resetDrag,
                    onDragEnd = state::resetDrag,
                    onDrag = { change, dragAmount ->
                        if (state.draggingId != id) return@detectDragGestures
                        change.consume()
                        state.drag(id, index, dragAmount.y)
                    },
                )
            }.semantics {
                stateDescription = "Position ${index + 1} of ${state.reorderDraft.size}"
                customActions = moves.map { (label, offset) ->
                    CustomAccessibilityAction(label) { state.move(id, offset) }
                }
            },
            enabled = moves.isNotEmpty(),
            onClick = { menuExpanded = true },
        ) {
            Icon(Icons.Default.DragHandle, contentDescription = "Reorder ${title}")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            moves.forEach { (label, offset) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { state.move(id, offset); menuExpanded = false },
                )
            }
        }
    }
}
