package com.theoriacodex.app.recents

import com.theoriacodex.app.ui.components.focusOnOpen
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.theoriacodex.data.repository.MAX_SAVED_SEARCH_NAME_LENGTH

@Composable
fun SavedSearchNameDialog(
    initialName: String,
    renaming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onEdit: ((String) -> Unit)? = null,
) {
    var name by rememberSaveable(initialName, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (renaming) "Rename search" else "Save search") },
        text = {
            OutlinedTextField(
                modifier = Modifier.focusOnOpen(),
                value = name,
                onValueChange = { if (it.text.length <= MAX_SAVED_SEARCH_NAME_LENGTH) name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim()) }, enabled = name.text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                onEdit?.let { edit -> TextButton(onClick = { edit(name.text.trim()) }) { Text("Edit search") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
