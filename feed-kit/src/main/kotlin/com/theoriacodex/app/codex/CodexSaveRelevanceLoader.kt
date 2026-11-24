package com.theoriacodex.app.codex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.vision.PredictedTag
import com.theoriacodex.domain.vision.PredictedTagIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

val LocalCodexSaveRelevanceLoader = staticCompositionLocalOf<CodexSaveRelevanceLoader?> { null }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodexSaveRelevanceLoader(
    repository: CodexRepository,
    private val scope: CoroutineScope,
    private val scoringDispatcher: CoroutineDispatcher = Dispatchers.Default,
    predicted: Flow<PredictedTagIndex> = flowOf(PredictedTagIndex.Empty),
    private val incoming: (PostId) -> List<PredictedTag>? = { null },
    observeLibrary: (Set<String>) -> Flow<Map<String, List<Post>>>,
) {
    private val library: SharedFlow<SavedLibrary> = repository.observeCodices()
        .map { codices -> codices.mapTo(linkedSetOf(), Codex::codexId) }
        .distinctUntilChanged()
        .flatMapLatest(observeLibrary)
        .combine(predicted, ::SavedLibrary)
        .flowOn(scoringDispatcher)
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = RETAIN_MS), replay = 1)
    private val requests = HashMap<Request, Lookup>()

    fun observe(posts: List<Post>, query: Query?): Flow<Map<String, Double>> {
        val request = Request(posts.distinctBy(Post::id), query)
        return flow {
            val lookup = acquire(request)
            try {
                emitAll(lookup.scores.filterNotNull())
            } finally {
                release(request, lookup)
            }
        }
    }

    fun latest(posts: List<Post>, query: Query?): Map<String, Double> = synchronized(requests) {
        requests[Request(posts.distinctBy(Post::id), query)]?.scores?.value.orEmpty()
    }

    private fun acquire(request: Request): Lookup = synchronized(requests) {
        requests.getOrPut(request) { start(request) }.apply {
            collectors++
            expiry?.cancel()
            expiry = null
        }
    }

    private fun release(request: Request, lookup: Lookup) = synchronized(requests) {
        if (--lookup.collectors > 0) return@synchronized
        lookup.expiry = scope.launch {
            delay(RETAIN_MS)
            synchronized(requests) {
                if (lookup.collectors == 0 && requests[request] === lookup) {
                    requests.remove(request)
                    lookup.scope.cancel()
                }
            }
        }
    }

    private fun start(request: Request): Lookup {
        val lookupScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val own = request.posts.mapNotNull { post -> incoming(post.id)?.let { post.id to it } }.toMap()
        val scored = library.map { saved ->
            val predicted = saved.predicted.copy(byPost = saved.predicted.byPost + own)
            CodexSaveRelevance(request.posts, request.query, predicted).takeIf { it.hasSignals }?.scoreEach(saved).orEmpty()
        }.flowOn(scoringDispatcher)
        return Lookup(lookupScope, scored.stateIn(lookupScope, SharingStarted.Eagerly, null))
    }

    private data class Request(val posts: List<Post>, val query: Query?)

    private class Lookup(val scope: CoroutineScope, val scores: StateFlow<Map<String, Double>?>) {
        var collectors = 0
        var expiry: Job? = null
    }
}

@Composable
fun PreloadCodexSaveRelevance(post: Post, query: Query? = null) {
    val loader = LocalCodexSaveRelevanceLoader.current ?: return
    remember(loader, post, query) { loader.observe(listOf(post), query) }
        .collectAsStateWithLifecycle(emptyMap())
}

private const val RETAIN_MS = 5_000L
