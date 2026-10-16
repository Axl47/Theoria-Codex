package com.theoriacodex.app.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.data.repository.CacheRepository
import com.theoriacodex.data.repository.FollowedCreator
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal typealias FollowedFeedState = LiveCodexFeedState

/** Navigation-owned live collection. Branch identity includes the durable follow membership. */
internal class FollowedCodexViewModel(
    private val registry: SourceAdapterRegistry,
    private val cacheRepository: CacheRepository,
) : ViewModel(), LiveCodexFeedOwner {
    private val feed = LiveCodexFeedController<CreatorBranch>(viewModelScope,
        fetch = { branch, _ -> fetch(branch) },
        cacheCover = { post -> cacheRepository.cacheNamedThumbnail(FOLLOWED_CODEX_ID, post) },
    )
    override val state = feed.state
    private var follows: List<FollowedCreator> = emptyList()
    private var availableSources: Set<SourceKey>? = null

    fun synchronize(selected: List<FollowedCreator>, available: Set<SourceKey>) {
        // Check/visit metadata must not restart an otherwise identical feed.
        val identity = selected.map { it.membershipId to it.creator }
        if (identity == follows.map { it.membershipId to it.creator } && available == availableSources) return
        follows = selected
        availableSources = available
        refresh()
    }

    override fun refresh() = feed.reset(follows.map(::CreatorBranch))
    override fun loadMore() = feed.loadMore()
    override fun retry() = feed.retry()

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

    private data class CreatorBranch(
        val follow: FollowedCreator,
        override val posts: List<Post> = emptyList(),
        val nextPageToken: String? = null,
        val exhausted: Boolean = false,
        override val error: String? = null,
    ) : LiveCodexBranch {
        override val id get() = follow.membershipId
        override val canLoadMore get() = !exhausted && error == null
    }

    companion object {
        fun factory(registry: SourceAdapterRegistry, cacheRepository: CacheRepository) = viewModelFactory {
            initializer { FollowedCodexViewModel(registry, cacheRepository) }
        }
    }
}
