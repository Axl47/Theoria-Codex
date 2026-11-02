package com.theoriacodex.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun CredentialRecoveryDialog(onDismiss: () -> Unit, onReconnect: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onReconnect,
            ) {
                Text("Reconnect")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Not now")
            }
        },
        title = { Text("Reconnect source accounts") },
        text = {
            Text(
                "Encrypted source credentials cannot be read on this device. " +
                    "Reconnect clears the unreadable shared credential store, then opens " +
                    "Settings so Pixiv, Gelbooru, and rule34.xxx can be connected again."
            )
        },
    )
}
