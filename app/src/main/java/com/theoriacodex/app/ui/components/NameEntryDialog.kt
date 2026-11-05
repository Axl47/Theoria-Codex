package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Asks for one trimmed name; shared by everything the user can name. A name is required unless
 * [optional], where saving a blank field passes an empty name and [hint] says what that means.
 */
@Composable
internal fun NameEntryDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    optional: Boolean = false,
    hint: String? = null,
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    fun saveIfValid() {
        val trimmed = value.trim()
        if (optional || trimmed.isNotBlank()) onSave(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = { value = it.replace("\n", " ") },
                label = { Text(if (optional) "Name (optional)" else "Name") },
                supportingText = hint?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveIfValid() }),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { saveIfValid() }) { Text("Save") } },
    )
}
