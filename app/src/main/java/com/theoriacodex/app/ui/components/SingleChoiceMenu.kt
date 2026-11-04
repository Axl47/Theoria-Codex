package com.theoriacodex.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Dropdown of mutually exclusive options; the current one carries a check. Selecting also dismisses. */
@Composable
internal fun <T> SingleChoiceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(label(option)) },
                trailingIcon = {
                    if (option == selected) Icon(Icons.Default.Check, contentDescription = "Selected")
                },
                onClick = {
                    onDismiss()
                    onSelect(option)
                },
            )
        }
    }
}
