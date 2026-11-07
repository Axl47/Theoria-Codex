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

/**
 * The active profile's saved single tags, so every tag sheet marks them, and how holding a saved tag
 * removes it. Saving still goes through each sheet's own long-press callback.
 */
internal class PostTagSavedTags(
    val saved: Flow<Set<Pair<SourceKey, String>>> = flowOf(emptySet()),
    val remove: (SourceKey, String) -> Unit = { _, _ -> },
)

internal val LocalPostTagSavedTags = staticCompositionLocalOf { PostTagSavedTags() }

/** Cross-route tag sheets share the Search draft, typed count lookup, and draft actions while shown. */
@Composable
internal fun PostTagContextProvider(
    state: StateFlow<SearchUiState>?,
    fetchSearchTermCounts: suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?> = { _, _ -> emptyMap() },
    searchActions: PostTagSearchActions = PostTagSearchActions(),
    savedTags: PostTagSavedTags = PostTagSavedTags(),
    content: @Composable () -> Unit,
) {
    val draft = remember(state) { state?.map { it.query.draft }?.distinctUntilChanged() ?: noSearchDraft }
    CompositionLocalProvider(
        LocalSearchDraftSelection provides draft,
        LocalSearchTermCounts provides fetchSearchTermCounts,
        LocalPostTagSearchActions provides searchActions,
        LocalPostTagSavedTags provides savedTags,
        content = content,
    )
}
