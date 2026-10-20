package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Balanced sheet title with a close action and an optional trailing action. */
@Composable
internal fun SheetHeader(
    title: String,
    closeDescription: String,
    onDismiss: () -> Unit,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = closeDescription) }
        Text(title, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (trailingAction != null) trailingAction() else Spacer(Modifier.size(48.dp))
    }
}
