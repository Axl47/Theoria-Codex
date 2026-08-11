package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.domain.orchestration.SourceRunStatus

@Composable
internal fun StatusRow(statuses: List<SourceRunStatus>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(statuses.size) { index ->
            val status = statuses[index]
            val text = sourceStatusChipText(status)
            AssistChip(onClick = {}, label = { Text(text) })
        }
    }
}

@Composable
internal fun EmptyBlock(
    hasPendingChanges: Boolean,
    messageOverride: String? = null,
) {
    FeedEmptyTile(
        message = messageOverride ?: if (hasPendingChanges) {
            "Draft updated. Press Apply to refresh results."
        } else {
            "No results yet. Add tags and press Apply to start searching."
        },
        contentPadding = 24.dp,
    )
}

@Composable
internal fun ErrorBlock(
    message: String,
    onRetry: (() -> Unit)? = null,
    title: String = "Could not load results",
    actionLabel: String = "Retry",
) {
    FeedErrorTile(
        message = message,
        title = title,
        actionLabel = actionLabel,
        onRetry = onRetry,
    )
}
