package com.theoriacodex.app.tags

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.model.Post
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

/** Edits and runs that tag sheets send to Search from any route. */
internal class PostTagSearchActions(
    val addAlternative: (Post, Int, SearchTerm) -> Boolean = { _, _, _ -> false },
    val applyDraft: () -> Unit = {},
)

internal val LocalPostTagSearchActions = staticCompositionLocalOf { PostTagSearchActions() }

/** Cross-route tag sheets share the Search draft, typed count lookup, and draft actions while shown. */
@Composable
internal fun PostTagContextProvider(
    state: StateFlow<SearchUiState>?,
    fetchSearchTermCounts: suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?> = { _, _ -> emptyMap() },
    searchActions: PostTagSearchActions = PostTagSearchActions(),
    content: @Composable () -> Unit,
) {
    val draft = remember(state) { state?.map { it.query.draft }?.distinctUntilChanged() ?: noSearchDraft }
    CompositionLocalProvider(
        LocalSearchDraftSelection provides draft,
        LocalSearchTermCounts provides fetchSearchTermCounts,
        LocalPostTagSearchActions provides searchActions,
        content = content,
    )
}
