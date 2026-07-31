package com.theoriacodex.app.search

import com.theoriacodex.app.media.AnimatedDurationEnricher
import com.theoriacodex.app.media.AnimatedDurationEnrichment
import com.theoriacodex.app.media.AnimatedDurationEnrichmentLane
import com.theoriacodex.app.media.animatedDurationMs
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CoroutineScope

/** Search-scoped application collaborator for duration enrichment state and immutable updates. */
internal class SearchAnimatedDurationEnrichmentOwner(
    scope: CoroutineScope,
    enricher: AnimatedDurationEnricher,
    private val currentState: () -> SearchUiState,
    private val applyResolvedPosts: (String, List<Post>) -> Unit,
) {
    private val lane = AnimatedDurationEnrichmentLane(
        scope = scope,
        enricher = enricher,
        currentIdentity = { currentState().query.appliedQueryHash },
        currentPosts = { currentState().content.results },
        applyEnrichments = ::applyEnrichments,
    )

    fun request(queryHash: String) {
        lane.request(queryHash)
    }

    private fun applyEnrichments(
        queryHash: String,
        enrichments: List<AnimatedDurationEnrichment>,
    ) {
        if (currentState().query.appliedQueryHash != queryHash) return
        val resolved = enrichments.mapNotNull { result ->
            val latestPost = currentState().content.results
                .firstOrNull { post -> post.id == result.postId }
                ?: return@mapNotNull null
            if (animatedDurationMs(latestPost) != null) return@mapNotNull null
            latestPost.copy(durationMs = result.durationMs)
        }
        if (resolved.isNotEmpty()) applyResolvedPosts(queryHash, resolved)
    }
}
