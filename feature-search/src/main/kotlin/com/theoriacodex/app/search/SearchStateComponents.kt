package com.theoriacodex.app.search

import androidx.compose.foundation.layout.fillMaxWidth
import com.theoriacodex.app.settings.sites.ADD_API_KEY_LABEL
import com.theoriacodex.app.settings.sites.VERIFY_IN_BROWSER_LABEL
import com.theoriacodex.app.source.LocalSourceHealth
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.FeedEmptyTile
import com.theoriacodex.app.ui.components.FeedErrorTile
import com.theoriacodex.app.ui.components.FeedFailureTile
import com.theoriacodex.domain.orchestration.SourceRunStatus

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

/** A failed search, whose action verifies the source in the browser when a challenge blocked it, else retries. */
@Composable
fun SourceFailureBlock(
    message: String,
    statuses: List<SourceRunStatus>,
    onDismiss: (() -> Unit)? = null,
    onRetry: () -> Unit,
) {
    val health = LocalSourceHealth.current
    val challenged = statuses.challengedSource(health.issues)
    if (challenged == null) {
        return FeedFailureTile(message = message, onRetry = onRetry, title = SEARCH_FAILURE_TITLE, onDismiss = onDismiss)
    }
    FeedFailureTile(
        message = message,
        title = SEARCH_FAILURE_TITLE,
        onRetry = { health.verify(challenged) },
        actionLabel = VERIFY_IN_BROWSER_LABEL,
        accountProblem = true,
        onDismiss = onDismiss,
    )
}

/** Says a source needs an account, with a way straight to the key field when it is an added site. */
@Composable
internal fun SourceAccountNotice(message: String, statuses: List<SourceRunStatus>, onDismiss: (() -> Unit)? = null) {
    val health = LocalSourceHealth.current
    FeedFailureTile(
        title = "Source account required",
        message = message,
        onRetry = statuses.siteNeedingKey()?.let { site -> { health.addKey(site) } },
        actionLabel = ADD_API_KEY_LABEL,
        accountProblem = true,
        onDismiss = onDismiss,
    )
}

/**
 * Says which sources a multi-source search is missing and why. Their chips live in the composer, which is
 * collapsed once results are showing. Each notice stays dismissed for as long as its outage lasts.
 */
@Composable
fun SourceOutageNotices(notices: List<SourceOutageNotice>) {
    val health = LocalSourceHealth.current
    notices.filterNot { it.sources.all(health.dismissedNotices::contains) }.forEach { notice ->
        FeedErrorTile(
            title = "Results may be incomplete",
            message = notice.message,
            actionLabel = VERIFY_IN_BROWSER_LABEL,
            onRetry = notice.verifiableSource?.let { source -> { health.verify(source) } },
            onDismiss = { health.dismissNotices(notice.sources) },
        )
    }
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

private const val SEARCH_FAILURE_TITLE = "Could not load results"
