package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Configured API-key accounts stay compact until their credentials are disconnected. */
@Composable
internal fun CredentialAccountControls(
    name: String,
    statusLabel: String,
    configured: Boolean,
    userIdInput: String,
    apiKeyInput: String,
    enabled: Boolean,
    onUserIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Text(name, style = MaterialTheme.typography.titleSmall)
    Text(statusLabel, style = MaterialTheme.typography.bodySmall)
    if (!configured) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = userIdInput,
            onValueChange = onUserIdChange,
            label = { Text("User ID") },
            singleLine = true,
            enabled = enabled,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = apiKeyInput,
            onValueChange = onApiKeyChange,
            label = { Text("Replacement API Key") },
            supportingText = {
                Text("Leave blank to keep the configured key. Paste a key or credential query to replace it.")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = enabled,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!configured) {
            Button(onClick = onSave, enabled = enabled) { Text("Save") }
        }
        TextButton(onClick = onDisconnect, enabled = enabled) {
            Text(if (configured) "Disconnect" else "Clear")
        }
    }
}
