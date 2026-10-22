package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val noSearchDraft = flowOf<Query?>(null)
internal val LocalSearchDraftSelection = staticCompositionLocalOf<Flow<Query?>> { noSearchDraft }
internal val LocalSearchTermCounts = staticCompositionLocalOf<
    suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?>
> { { _, _ -> emptyMap() } }

/** Cross-route tag sheets share read-only draft selection and typed count lookup while shown. */
@Composable
internal fun PostTagContextProvider(
    state: StateFlow<SearchUiState>?,
    fetchSearchTermCounts: suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?> = { _, _ -> emptyMap() },
    content: @Composable () -> Unit,
) {
    val draft = remember(state) { state?.map { it.query.draft }?.distinctUntilChanged() ?: noSearchDraft }
    CompositionLocalProvider(
        LocalSearchDraftSelection provides draft,
        LocalSearchTermCounts provides fetchSearchTermCounts,
        content = content,
    )
}
