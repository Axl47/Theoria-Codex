package com.theoriacodex.app.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.app.ui.LocalNetworkOnline
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
import com.theoriacodex.app.settings.sites.ADD_API_KEY_LABEL
import com.theoriacodex.app.source.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.app.source.LocalSourceHealth
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.referer
import com.theoriacodex.domain.model.SourceKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.orchestration.SourceRunStatus

@Composable
fun StatusRow(
    statuses: List<SourceRunStatus>,
    retryEnabled: Boolean = true,
    onRetrySource: (SourceKey) -> Unit = {},
    onOpenAccounts: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf<SourceKey?>(null) }
    val health = LocalSourceHealth.current
    val uriHandler = LocalUriHandler.current
    val selectedStatus = statuses.firstOrNull { it.source == selected }
    selectedStatus?.let { status ->
        val requiresAccount = onOpenAccounts != null && status.requiresAccount()
        val keyableSite = status.source.takeIf { requiresAccount && it.isCustom }
        val challenged = listOf(status).challengedSource(health.issues)
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
                TextButton(enabled = retryEnabled || requiresAccount || challenged != null, onClick = {
                    selected = null
                    when {
                        keyableSite != null -> health.addKey(keyableSite)
                        requiresAccount -> onOpenAccounts()
                        challenged != null -> health.verify(challenged)
                        else -> onRetrySource(status.source)
                    }
                }) {
                    Text(when {
                        keyableSite != null -> ADD_API_KEY_LABEL
                        requiresAccount -> "Source accounts"
                        challenged != null -> VERIFY_IN_BROWSER_LABEL
                        else -> "Retry this source"
                    })
                }
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
