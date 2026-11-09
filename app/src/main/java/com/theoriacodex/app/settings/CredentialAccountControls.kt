package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.SourceKey

/** The account line for a built-in source's row in Sites; null for sources without accounts. */
internal fun builtInAccountSummary(source: SourceKey, accounts: SettingsAccountUiState): String? = when (source) {
    SourceKey.PIXIV -> accounts.pixivStatusLabel
    SourceKey.GELBOORU -> accounts.gelbooruStatusLabel
    SourceKey.RULE34XXX -> accounts.rule34XxxStatusLabel
    else -> null
}

/** The account controls on a built-in source's page in Sites; nothing for sources without accounts. */
@Composable
internal fun BuiltInAccountControls(source: SourceKey, accounts: SettingsAccountUiState, onAction: (SettingsAction) -> Unit) {
    when (source) {
        SourceKey.PIXIV -> PixivAccountControls(accounts, onAction)
        SourceKey.GELBOORU -> ApiKeySection(accounts.gelbooruConfigured) {
            CredentialAccountControls(
                statusLabel = accounts.gelbooruStatusLabel,
                configured = accounts.gelbooruConfigured,
                userIdInput = accounts.gelbooruUserIdInput,
                apiKeyInput = accounts.gelbooruApiKeyInput,
                enabled = accounts.mutationsEnabled,
                onUserIdChange = { onAction(SettingsAction.SetGelbooruUserId(it)) },
                onApiKeyChange = { onAction(SettingsAction.SetGelbooruApiKey(it)) },
                onSave = { onAction(SettingsAction.SaveGelbooruCredentials) },
                onDisconnect = { onAction(SettingsAction.ClearGelbooruCredentials) },
            )
        }
        SourceKey.RULE34XXX -> ApiKeySection(accounts.rule34XxxConfigured) {
            CredentialAccountControls(
                statusLabel = accounts.rule34XxxStatusLabel,
                configured = accounts.rule34XxxConfigured,
                userIdInput = accounts.rule34XxxUserIdInput,
                apiKeyInput = accounts.rule34XxxApiKeyInput,
                enabled = accounts.mutationsEnabled,
                onUserIdChange = { onAction(SettingsAction.SetRule34XxxUserId(it)) },
                onApiKeyChange = { onAction(SettingsAction.SetRule34XxxApiKey(it)) },
                onSave = { onAction(SettingsAction.SaveRule34XxxCredentials) },
                onDisconnect = { onAction(SettingsAction.ClearRule34XxxCredentials) },
            )
        }
        else -> Unit
    }
}

@Composable
private fun PixivAccountControls(accounts: SettingsAccountUiState, onAction: (SettingsAction) -> Unit) {
    SettingsGroup("Account") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(accounts.pixivStatusLabel, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction(SettingsAction.ConnectPixiv) },
                    enabled = accounts.pixivConnectEnabled,
                ) { Text("Connect") }
                TextButton(onClick = { onAction(SettingsAction.DisconnectPixiv) }) { Text("Disconnect") }
            }
        }
    }
}

/** The collapsed "API key" item; it opens by itself only while no key is saved. */
@Composable
private fun ApiKeySection(configured: Boolean, content: @Composable () -> Unit) {
    SettingsGroup(null) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpandableSection("API key", initiallyExpanded = !configured, content = content)
        }
    }
}

/** Configured API-key accounts stay compact until their credentials are disconnected. */
@Composable
internal fun CredentialAccountControls(
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
