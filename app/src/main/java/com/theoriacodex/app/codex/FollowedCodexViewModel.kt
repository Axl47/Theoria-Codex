package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.coroutines.mapConcurrent
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class FollowedFeedState(
    val posts: List<Post> = emptyList(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val generation: Long = 0,
)

/** Navigation-owned live collection. Branch identity includes the durable follow membership. */
internal class FollowedCodexViewModel(
    private val registry: SourceAdapterRegistry,
    private val cacheRepository: CacheRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FollowedFeedState())
    val state = mutableState.asStateFlow()
    private var follows: List<FollowedCreator> = emptyList()
    private var availableSources: Set<SourceKey>? = null
    private var branches = linkedMapOf<String, CreatorBranch>()
    private var job: Job? = null
    private var generation = 0L

    fun synchronize(selected: List<FollowedCreator>, available: Set<SourceKey>) {
        // Check/visit metadata must not restart an otherwise identical feed.
        val identity = selected.map { it.membershipId to it.creator }
        if (identity == follows.map { it.membershipId to it.creator } && available == availableSources) return
        follows = selected
        availableSources = available
        refresh()
    }

    fun refresh() {
        job?.cancel()
        generation++
        branches = follows.associateTo(linkedMapOf()) { it.membershipId to CreatorBranch(it) }
        mutableState.value = FollowedFeedState(generation = generation)
        load(branches.keys.toList(), cacheCover = true)
    }

    fun loadMore() {
        if (mutableState.value.loading) return
        load(branches.filterValues { it.error == null && !it.exhausted }.keys.toList(), cacheCover = false)
    }

    fun retry() {
        if (mutableState.value.loading) return
        load(branches.filterValues { it.error != null }.keys.toList(), cacheCover = false)
    }

    private fun load(ids: List<String>, cacheCover: Boolean) {
        if (ids.isEmpty()) return
        val admittedGeneration = ++generation
        mutableState.value = mutableState.value.copy(loading = true, generation = generation)
        job = viewModelScope.launch {
            try {
                ids.mapConcurrent(concurrency = 2) { id ->
                    val branch = branches[id] ?: return@mapConcurrent
                    val result = fetch(branch)
                    if (generation == admittedGeneration) {
                        branches[id] = result
                        publish(loading = true)
                    }
                }
            } finally {
                if (generation == admittedGeneration) {
                    publish(loading = false)
                    if (cacheCover) {
                        mutableState.value.posts.firstOrNull()?.let { post ->
                            runCatchingPreservingCancellation { cacheRepository.cacheNamedThumbnail(FOLLOWED_CODEX_ID, post) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetch(branch: CreatorBranch): CreatorBranch {
        return try {
            val creator = branch.follow.creator
            check(creator.source in availableSources.orEmpty()) { "Source unavailable" }
            val adapter = registry.adapterFor(creator.source) as? CreatorPostsSourceAdapter
                ?: error("Creator browsing unavailable")
            val page = withTimeoutOrNull(15_000L) { adapter.searchCreatorPosts(creator, branch.nextPageToken) }
                ?: error("Source timed out")
            branch.copy(
                posts = (branch.posts + page.items.filter { it.id.source == creator.source }).distinctBy(Post::id),
                nextPageToken = page.nextPageToken,
                exhausted = page.nextPageToken.isNullOrBlank(),
                error = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            branch.copy(error = error.message ?: "Could not load posts")
        }
    }

    private fun publish(loading: Boolean) {
        mutableState.value = FollowedFeedState(
            posts = branches.values.flatMap { it.posts }.distinctBy(Post::id),
            loading = loading,
            canLoadMore = branches.values.any { !it.exhausted && it.error == null },
            errors = branches.values.mapNotNull { branch ->
                branch.error?.let { branch.follow.membershipId to it }
            }.toMap(),
            generation = generation,
        )
    }

    private data class CreatorBranch(
        val follow: FollowedCreator,
        val posts: List<Post> = emptyList(),
        val nextPageToken: String? = null,
        val exhausted: Boolean = false,
        val error: String? = null,
    )

    companion object {
        fun factory(registry: SourceAdapterRegistry, cacheRepository: CacheRepository) = viewModelFactory {
            initializer { FollowedCodexViewModel(registry, cacheRepository) }
        }
    }
}
