package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.orchestration.SourceRunStatus

/** A failed continuation belongs beside its usable grid, with the exact page retained for Retry. */
internal val SearchUiState.pageFailure: SearchErrorUiState?
    get() = content.error?.takeIf { it.requestKind == SearchRequestKind.PAGE && content.results.isNotEmpty() }

@Composable
internal fun SearchFailureSurface(
    error: SearchErrorUiState,
    statuses: List<SourceRunStatus>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (statuses.siteNeedingKey() != null) {
            SourceAccountNotice(siteKeyFailureMessage(error.message, statuses), statuses)
        } else {
            SourceFailureBlock(error.message, statuses, onRetry)
        }
    }
}
