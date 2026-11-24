package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import com.theoriacodex.app.ui.components.ConfirmDialog

@Composable
internal fun CredentialRecoveryDialog(onDismiss: () -> Unit, onReconnect: () -> Unit) {
    ConfirmDialog(
        title = "Reconnect source accounts",
        text = "Encrypted source credentials cannot be read on this device. " +
            "Reconnect clears the unreadable shared credential store, then opens " +
            "Settings so Pixiv, Gelbooru, and rule34.xxx can be connected again.",
        confirmLabel = "Reconnect",
        onConfirm = onReconnect,
        onDismiss = onDismiss,
        dismissLabel = "Not now",
    )
}
