package com.theoriacodex.app.search

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.search.state.SearchErrorUiState
import com.theoriacodex.app.search.state.SearchRequestKind
import com.theoriacodex.app.search.state.SearchUiState

/** A failed continuation belongs beside its usable grid, with the exact page retained for Retry. */
internal val SearchUiState.pageFailure: SearchErrorUiState?
    get() = content.error?.takeIf { it.requestKind == SearchRequestKind.PAGE && content.results.isNotEmpty() }

@Composable
internal fun SearchFailureSurface(error: SearchErrorUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { ErrorBlock(message = error.message, onRetry = onRetry) }
}
