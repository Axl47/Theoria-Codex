package com.theoriacodex.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * The state kit: what a screen, sheet or section shows instead of content while loading, when empty, or after a
 * failure. Each centres itself in the width it gets; pass `Modifier.fillMaxSize()` to centre on a whole screen.
 * Feed grids keep their card tiles (FeedEmptyTile, FeedErrorTile).
 */

/** Nothing to show yet. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Nothing here: a bare [label], plus one action (such as Add) when there is an obvious next step. */
@Composable
fun EmptyState(label: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    StateMessage(label, modifier, actionLabel.takeIf { onAction != null }, onAction)
}

/** Something failed: what went wrong, and Retry. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, retryLabel: String = "Retry") {
    StateMessage(message, modifier, retryLabel, onRetry)
}

@Composable
private fun StateMessage(text: String, modifier: Modifier, actionLabel: String?, onAction: (() -> Unit)?) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
