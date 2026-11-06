package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.Query
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

internal val LocalCodexSaveRelevanceLoader = staticCompositionLocalOf<CodexSaveRelevanceLoader?> { null }

/**
 * Shares one lookup across the hold menu and save picker, retaining it through their transition.
 *
 * The saved library is observed once and shared across posts: opening another post rescores the retained
 * snapshot instead of decoding the library again. It stops 5 s after the last action surface closes.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CodexSaveRelevanceLoader(
    repository: CodexRepository,
    private val scope: CoroutineScope,
    private val scoringDispatcher: CoroutineDispatcher = Dispatchers.Default,
    observeLibrary: (Set<String>) -> Flow<Map<String, List<Post>>>,
) {
    private val library: SharedFlow<Map<String, List<Post>>> = repository.observeCodices()
        .map { codices -> codices.mapTo(linkedSetOf(), Codex::codexId) }
        .distinctUntilChanged()
        .flatMapLatest(observeLibrary)
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)
    private var request: Pair<List<Post>, Query?>? = null
    private var lookupScope: CoroutineScope? = null
    private var scores: StateFlow<Map<String, Double>>? = null

    fun observe(posts: List<Post>, query: Query?): StateFlow<Map<String, Double>> {
        val next = posts.distinctBy(Post::id) to query
        if (request == next) return requireNotNull(scores)
        lookupScope?.cancel()
        val childScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        lookupScope = childScope
        request = next
        val relevance = CodexSaveRelevance(next.first, next.second)
        val scored = if (relevance.hasSignals) {
            library.map(relevance::scoreEach).flowOn(scoringDispatcher)
        } else {
            flowOf(emptyMap())
        }
        return scored
            .stateIn(childScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), emptyMap())
            .also { scores = it }
    }
}

/** Keep the lookup subscribed for as long as a post's action surface is visible. */
@Composable
internal fun PreloadCodexSaveRelevance(post: Post, query: Query? = null) {
    val loader = LocalCodexSaveRelevanceLoader.current ?: return
    remember(loader, post, query) { loader.observe(listOf(post), query) }
        .collectAsStateWithLifecycle()
}
