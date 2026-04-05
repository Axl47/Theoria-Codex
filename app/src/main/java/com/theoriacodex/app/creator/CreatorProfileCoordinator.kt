package com.theoriacodex.app.creator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.CancellationException

class CreatorProfileCoordinator(
    private val registry: SourceAdapterRegistry,
) {
    private var nextPageToken: String? = null

    var activeCreator by mutableStateOf<CreatorProfile?>(null)
        private set

    var results by mutableStateOf<List<Post>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var canLoadMore by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    val activeQueryHash: String?
        get() = activeCreator?.let(::creatorQueryHash)

    suspend fun open(creator: CreatorProfile) {
        activeCreator = creator
        refresh()
    }

    suspend fun refresh() {
        val creator = activeCreator ?: return
        val adapter = adapterFor(creator)
        if (adapter == null) {
            results = emptyList()
            loading = false
            loadingMore = false
            canLoadMore = false
            nextPageToken = null
            errorMessage = "Creator browsing is not available for ${creator.source.name.lowercase()}."
            return
        }
        loading = true
        loadingMore = false
        canLoadMore = false
        errorMessage = null
        nextPageToken = null
        try {
            val page = adapter.searchCreatorPosts(creator = creator, pageToken = null)
            results = page.items.distinctBy(Post::id)
            nextPageToken = page.nextPageToken
            canLoadMore = !page.nextPageToken.isNullOrBlank()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            results = emptyList()
            nextPageToken = null
            canLoadMore = false
            errorMessage = error.message ?: "Could not load creator uploads"
        } finally {
            loading = false
        }
    }

    suspend fun loadNextPage() {
        val creator = activeCreator ?: return
        val token = nextPageToken ?: return
        val adapter = adapterFor(creator)
        if (adapter == null || loading || loadingMore) return
        loadingMore = true
        errorMessage = null
        try {
            val page = adapter.searchCreatorPosts(creator = creator, pageToken = token)
            results = mergeResults(results, page.items)
            nextPageToken = page.nextPageToken
            canLoadMore = !page.nextPageToken.isNullOrBlank()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            canLoadMore = false
            errorMessage = error.message ?: "Could not load more creator uploads"
        } finally {
            loadingMore = false
        }
    }

    fun buildViewerLaunchContext(
        startIndex: Int,
        scrollOffsetHint: Int,
    ): ViewerLaunchContext {
        return ViewerLaunchContext(
            queryHash = activeQueryHash ?: "creator:unavailable",
            startIndex = startIndex,
            streamSource = ViewerStreamSource.CREATOR_PROFILE,
            scrollOffsetHint = scrollOffsetHint,
        )
    }

    private fun adapterFor(creator: CreatorProfile): CreatorPostsSourceAdapter? {
        return registry.adapterFor(creator.source) as? CreatorPostsSourceAdapter
    }

    private fun mergeResults(
        existing: List<Post>,
        incoming: List<Post>,
    ): List<Post> {
        if (incoming.isEmpty()) return existing
        val byId = LinkedHashMap<String, Post>()
        existing.forEach { post ->
            byId[post.idKey()] = post
        }
        incoming.forEach { post ->
            byId[post.idKey()] = post
        }
        return byId.values.toList()
    }

    private fun Post.idKey(): String {
        return "${id.source.name}:${id.sourcePostId}"
    }
}

private fun creatorQueryHash(creator: CreatorProfile): String {
    val key = creator.uploadsQuery
        ?: creator.profileId
        ?: creator.profileUrl
        ?: creator.displayName
    return "creator:${creator.source.name}:$key"
}
