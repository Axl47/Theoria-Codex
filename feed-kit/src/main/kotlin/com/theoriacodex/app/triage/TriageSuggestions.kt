package com.theoriacodex.app.triage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.codex.LocalQuickSave
import com.theoriacodex.app.codex.SaveSuggestions
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Save targets for the deck's cards, by post. A post with no entry is still loading, which is not the same as
 * having no Codex to suggest.
 */
@Stable
internal class TriageSuggestions {
    private val loaded = mutableStateMapOf<PostId, SaveSuggestions>()

    operator fun get(post: PostId): SaveSuggestions? = loaded[post]

    /** [post]'s targets once they load; null when they have not arrived in a few seconds. */
    suspend fun await(post: PostId): SaveSuggestions? =
        withTimeoutOrNull(SUGGESTIONS_WAIT_MS) { snapshotFlow { loaded[post] }.filterNotNull().first() }

    fun put(post: PostId, suggestions: SaveSuggestions) {
        loaded[post] = suggestions
    }

    fun forget(post: PostId) {
        loaded.remove(post)
    }
}

/**
 * Loads save targets for [posts] (the current card and the next), so the next card's are usually ready when it
 * comes up; a post keeps its targets while it stays in [posts].
 */
@Composable
internal fun rememberTriageSuggestions(posts: List<Post>, query: Query?): TriageSuggestions {
    val quickSave = LocalQuickSave.current
    val suggestions = remember(quickSave, query) { TriageSuggestions() }
    posts.forEach { post ->
        key(post.id) {
            DisposableEffect(suggestions) { onDispose { suggestions.forget(post.id) } }
            LaunchedEffect(suggestions) {
                quickSave.suggestions(post, query, TRIAGE_SUGGESTIONS).collect { suggestions.put(post.id, it) }
            }
        }
    }
    return suggestions
}

private const val TRIAGE_SUGGESTIONS = 3
private const val SUGGESTIONS_WAIT_MS = 3_000L
