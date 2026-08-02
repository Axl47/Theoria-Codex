package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StartupUpdatePromptActions(
    actionEnabled: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onRemindLater: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onYes,
            enabled = actionEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("Yes")
        }
        TextButton(
            onClick = onNo,
            enabled = actionEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("No")
        }
    }
    TextButton(
        onClick = onRemindLater,
        enabled = actionEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Remind Later")
    }
}
