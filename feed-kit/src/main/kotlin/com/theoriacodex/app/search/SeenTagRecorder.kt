package com.theoriacodex.app.search

import com.theoriacodex.app.recommend.recommendationTaxonomyFor
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.domain.adapter.FacetedTagSuggestion
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.tags.normalizeFavoriteTagForStorage
import com.theoriacodex.domain.tags.sourceTagKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SeenTagRecorder(
    private val statisticsRepository: StatisticsRepository,
    private val tagSuggestionStore: TagSuggestionStore,
    private val computationDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {
    private val order = Mutex()

    fun record(posts: List<Post>) {
        if (posts.isEmpty()) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            order.withLock { runCatchingPreservingCancellation { withContext(computationDispatcher) { remember(posts) } } }
        }
    }

    private suspend fun remember(posts: List<Post>) {
        posts.forEach { post -> runCatchingPreservingCancellation { statisticsRepository.recordPostAvailable(post) } }
        posts.groupBy { it.id.source }.forEach { (source, sourcePosts) ->
            val suggestions = sourcePosts.asSequence()
                .flatMap { post -> recommendationTaxonomyFor(post).asSequence() }
                .map { term ->
                    FacetedTagSuggestion(
                        text = normalizeFavoriteTagForStorage(source, term.value),
                        facet = SearchFacet.TAG,
                        sourceNamespace = term.sourceNamespace,
                        alternateText = term.translation,
                    )
                }
                .filter { it.text.isNotBlank() }
                .distinctBy { Triple(it.facet, it.sourceNamespace, sourceTagKey(source, it.text)) }
                .take(SEEN_TAGS_PER_SOURCE_INGEST_LIMIT)
                .toList()
            if (suggestions.isNotEmpty()) {
                tagSuggestionStore.putFaceted(source, suggestions, TagSuggestionOrigin.SEEN)
            }
        }
    }
}
