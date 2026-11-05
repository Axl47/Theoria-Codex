package com.theoriacodex.app.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.LocalNetworkOnline
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.referer
import com.theoriacodex.domain.model.SourceKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.domain.orchestration.SourceRunStatus

@Composable
internal fun StatusRow(
    statuses: List<SourceRunStatus>,
    retryEnabled: Boolean = true,
    onRetrySource: (SourceKey) -> Unit = {},
    onOpenAccounts: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf<SourceKey?>(null) }
    val uriHandler = LocalUriHandler.current
    val selectedStatus = statuses.firstOrNull { it.source == selected }
    selectedStatus?.let { status ->
        val requiresAccount = onOpenAccounts != null && status.requiresAccount()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(status.source.displayName()) },
            text = {
                Column {
                    Text(buildSourceAuthErrorMessage(listOf(status))
                        ?: buildSourceFailureMessage(listOf(status)) ?: "Source unavailable")
                    TextButton(onClick = { uriHandler.openUri(status.source.referer()) }) {
                        Text("Open provider website")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = retryEnabled || requiresAccount, onClick = {
                    selected = null
                    if (requiresAccount) onOpenAccounts() else onRetrySource(status.source)
                }) { Text(if (requiresAccount) "Source accounts" else "Retry this source") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    StatusChips(statuses) { selected = it }
}

/** Offline, one chip replaces every source a returning network could fix, since none of them is at fault. */
@Composable
private fun StatusChips(statuses: List<SourceRunStatus>, onSelect: (SourceKey) -> Unit) {
    val online by LocalNetworkOnline.current.collectAsStateWithLifecycle()
    val offlineFailures = !online && statuses.any(SourceRunStatus::retriesOnReconnect)
    val chips = if (online) statuses else statuses.filterNot(SourceRunStatus::retriesOnReconnect)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offlineFailures) item {
            AssistChip(onClick = {}, enabled = false, label = { Text(OFFLINE_STATUS_CHIP) })
        }
        items(chips.size) { index ->
            val status = chips[index]
            AssistChip(onClick = { onSelect(status.source) }, label = { Text(sourceStatusChipText(status)) })
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
    val online by LocalNetworkOnline.current.collectAsStateWithLifecycle()
    // Without a retry this is an account problem, which reconnecting won't fix.
    val offline = !online && onRetry != null
    FeedErrorTile(
        message = if (offline) OFFLINE_FEED_MESSAGE else message,
        title = if (offline) OFFLINE_FEED_TITLE else title,
        actionLabel = actionLabel,
        onRetry = onRetry,
    )
}

@Composable
internal fun SearchRefreshProgress() {
    androidx.compose.material3.LinearProgressIndicator(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SourceStatusActions(
    state: com.theoriacodex.app.search.state.SearchUiState,
    onAction: (com.theoriacodex.app.search.state.SearchAction) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    StatusRow(
        statuses = visibleSourceStatusChipStatuses(state.content.statuses),
        retryEnabled = state.execution.activeRequestId == null,
        onRetrySource = { onAction(com.theoriacodex.app.search.state.SearchAction.RetrySource(it)) },
        onOpenAccounts = onOpenAccounts,
    )
}
