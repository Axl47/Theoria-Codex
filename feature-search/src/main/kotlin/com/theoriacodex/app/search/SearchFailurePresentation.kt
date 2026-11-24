package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.orchestration.SourceRunStatus

internal val SearchUiState.pageFailure: SearchErrorUiState?
    get() = content.error?.takeIf { it.requestKind == SearchRequestKind.PAGE && content.results.isNotEmpty() }

@Composable
fun SearchFailureSurface(
    error: SearchErrorUiState,
    statuses: List<SourceRunStatus>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Box(modifier) {
        if (statuses.siteNeedingKey() != null) {
            SourceAccountNotice(siteKeyFailureMessage(error.message, statuses), statuses, onDismiss)
        } else {
            SourceFailureBlock(error.message, statuses, onDismiss, onRetry)
        }
    }
}
