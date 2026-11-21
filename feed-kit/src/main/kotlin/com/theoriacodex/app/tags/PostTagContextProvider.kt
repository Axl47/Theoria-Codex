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

/**
 * How tag sheets count posts per tag: [videoCount] answers from what is already known, [fetchVideoCounts] looks up
 * general tags it could not, and [fetchTermCounts] looks up typed tags by their full identity.
 */
internal class PostTagCounts(
    val videoCount: (SourceKey, String) -> Int? = { _, _ -> null },
    val fetchVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    val fetchTermCounts: suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?> = { _, _ -> emptyMap() },
)

internal val LocalPostTagCounts = staticCompositionLocalOf { PostTagCounts() }

/** Edits and runs that tag sheets send to Search from any route; each edit takes the post its tag came from. */
class PostTagSearchActions(
    val addInclude: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    val addExclude: (Post, SearchTerm) -> Boolean = { _, _ -> false },
    val removeInclude: (Post, SearchTerm) -> Unit = { _, _ -> },
    val removeExclude: (Post, SearchTerm) -> Unit = { _, _ -> },
    val addAlternative: (Post, Int, SearchTerm) -> Boolean = { _, _, _ -> false },
    val applyDraft: () -> Unit = {},
)

val LocalPostTagSearchActions = staticCompositionLocalOf { PostTagSearchActions() }

/**
 * The active profile's saved single tags, so every tag sheet marks them, and how holding a tag saves ([add]) or
 * removes it. [add] is null where tags cannot be saved.
 */
class PostTagSavedTags(
    val saved: Flow<Set<Pair<SourceKey, String>>> = flowOf(emptySet()),
    val add: ((SourceKey, String) -> Unit)? = null,
    val remove: (SourceKey, String) -> Unit = { _, _ -> },
)

internal val LocalPostTagSavedTags = staticCompositionLocalOf { PostTagSavedTags() }

/**
 * Every route's tag sheets share the Search draft, tag counts, saved tags and Search edits through this, so screens
 * pass none of them.
 */
@Composable
fun PostTagContextProvider(
    state: StateFlow<SearchUiState>?,
    fetchSearchTermCounts: suspend (SourceKey, List<SearchTerm>) -> Map<SearchTerm, Int?> = { _, _ -> emptyMap() },
    searchActions: PostTagSearchActions = PostTagSearchActions(),
    savedTags: PostTagSavedTags = PostTagSavedTags(),
    tagVideoCount: (SourceKey, String) -> Int? = { _, _ -> null },
    fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ -> emptyMap() },
    content: @Composable () -> Unit,
) {
    val draft = remember(state) { state?.map { it.query.draft }?.distinctUntilChanged() ?: noSearchDraft }
    val counts = remember(tagVideoCount, fetchTagVideoCounts, fetchSearchTermCounts) {
        PostTagCounts(tagVideoCount, fetchTagVideoCounts, fetchSearchTermCounts)
    }
    CompositionLocalProvider(
        LocalSearchDraftSelection provides draft,
        LocalPostTagCounts provides counts,
        LocalPostTagSearchActions provides searchActions,
        LocalPostTagSavedTags provides savedTags,
        content = content,
    )
}
