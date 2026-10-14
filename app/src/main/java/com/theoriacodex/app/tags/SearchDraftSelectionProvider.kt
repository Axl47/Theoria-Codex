package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val noSearchDraft = flowOf<Query?>(null)
internal val LocalSearchDraftSelection = staticCompositionLocalOf<Flow<Query?>> { noSearchDraft }

/** Cross-route tag sheets subscribe only while shown; this exposes no mutable state or route owner. */
@Composable
internal fun SearchDraftSelectionProvider(state: StateFlow<SearchUiState>?, content: @Composable () -> Unit) {
    val draft = remember(state) { state?.map { it.query.draft }?.distinctUntilChanged() ?: noSearchDraft }
    CompositionLocalProvider(LocalSearchDraftSelection provides draft, content = content)
}
