package com.theoriacodex.app.creator

import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.adapter.CreatorPostsSourceAdapter
import com.theoriacodex.domain.adapter.SourceAdapterRegistry
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CreatorProfileCoordinator(
    private val registry: SourceAdapterRegistry,
) {
    private val requestLock = Any()
    private var nextPageToken: String? = null
    private var availableSourcesSnapshot = registry.availableSources()
    private var nextGeneration = 0L
    private var activeRequest: CreatorRequest? = null

    var activeCreator: CreatorProfile? = null
        private set

    var results: List<Post> = emptyList()
        private set

    var loading = false
        private set

    var loadingMore = false
        private set

    var canLoadMore = false
        private set

    var errorMessage: String? = null
        private set

    val activeQueryHash: String?
        get() = activeCreator?.let(::creatorQueryHash)

    internal fun onAvailableSourcesChanged(): CreatorSourceAvailabilityChange {
        val currentSources = registry.availableSources()
        val changed = currentSources != availableSourcesSnapshot
        availableSourcesSnapshot = currentSources
        if (!changed) return CreatorSourceAvailabilityChange.UNCHANGED
        val creator = activeCreator ?: return CreatorSourceAvailabilityChange.RECONCILED
        if (creator.source in currentSources) {
            return if (errorMessage?.startsWith("Creator browsing is not available") == true) {
                CreatorSourceAvailabilityChange.REFRESH_REQUIRED
            } else {
                CreatorSourceAvailabilityChange.RECONCILED
            }
        }
        invalidateActiveRequest()
        results = emptyList()
        loading = false
        loadingMore = false
        canLoadMore = false
        nextPageToken = null
        errorMessage = "Creator browsing is not available for ${creator.source.name.lowercase()}."
        return CreatorSourceAvailabilityChange.RECONCILED
    }

    suspend fun open(creator: CreatorProfile) {
        prepare(creator)
        refresh()
    }

    /** Records a navigation handoff without starting provider work outside the route owner. */
    fun prepare(creator: CreatorProfile) {
        invalidateActiveRequest()
        activeCreator = creator
        results = emptyList()
        loading = false
        loadingMore = false
        canLoadMore = false
        nextPageToken = null
        errorMessage = null
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
        val request = beginRequest(CreatorRequestKind.ROOT)
        try {
            val page = adapter.searchCreatorPosts(creator = creator, pageToken = null)
            ensureCurrent(request)
            results = page.items.distinctBy(Post::id)
            nextPageToken = page.nextPageToken
            canLoadMore = !page.nextPageToken.isNullOrBlank()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            publishIfCurrent(request) {
                results = emptyList()
                nextPageToken = null
                canLoadMore = false
                errorMessage = error.message ?: "Could not load creator uploads"
            }
        } finally {
            finishRequest(request)
        }
    }

    suspend fun loadNextPage() {
        val creator = activeCreator ?: return
        val token = nextPageToken ?: return
        val adapter = adapterFor(creator)
        if (adapter == null || loading || loadingMore) return
        val request = beginRequest(CreatorRequestKind.PAGE)
        try {
            val page = adapter.searchCreatorPosts(creator = creator, pageToken = token)
            ensureCurrent(request)
            results = mergeResults(results, page.items)
            nextPageToken = page.nextPageToken
            canLoadMore = !page.nextPageToken.isNullOrBlank()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            publishIfCurrent(request) {
                canLoadMore = false
                errorMessage = error.message ?: "Could not load more creator uploads"
            }
        } finally {
            finishRequest(request)
        }
    }

    private suspend fun beginRequest(kind: CreatorRequestKind): CreatorRequest {
        val ownerJob = currentCoroutineContext()[Job]
        val (request, previous) = synchronized(requestLock) {
            val request = CreatorRequest(
                generation = ++nextGeneration,
                kind = kind,
                ownerJob = ownerJob,
            )
            val previous = activeRequest
            activeRequest = request
            when (kind) {
                CreatorRequestKind.ROOT -> {
                    loading = true
                    loadingMore = false
                    canLoadMore = false
                    nextPageToken = null
                }
                CreatorRequestKind.PAGE -> loadingMore = true
            }
            errorMessage = null
            request to previous
        }
        previous?.ownerJob?.takeIf { it !== request.ownerJob }?.cancel(
            CancellationException("Creator request superseded by generation ${request.generation}")
        )
        return request
    }

    private fun invalidateActiveRequest() {
        val request = synchronized(requestLock) {
            nextGeneration += 1L
            activeRequest.also {
                activeRequest = null
                loading = false
                loadingMore = false
            }
        }
        request?.ownerJob?.cancel(CancellationException("Creator capabilities changed"))
    }

    private suspend fun ensureCurrent(request: CreatorRequest) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(request)) {
            throw CancellationException("Stale Creator generation ${request.generation}")
        }
    }

    private fun isCurrent(request: CreatorRequest): Boolean {
        return synchronized(requestLock) { activeRequest == request }
    }

    private inline fun publishIfCurrent(request: CreatorRequest, update: () -> Unit) {
        if (isCurrent(request)) update()
    }

    private fun finishRequest(request: CreatorRequest) {
        synchronized(requestLock) {
            if (activeRequest != request) return
            activeRequest = null
            when (request.kind) {
                CreatorRequestKind.ROOT -> loading = false
                CreatorRequestKind.PAGE -> loadingMore = false
            }
        }
    }

    private enum class CreatorRequestKind { ROOT, PAGE }

    private data class CreatorRequest(
        val generation: Long,
        val kind: CreatorRequestKind,
        val ownerJob: Job?,
    )

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

    suspend fun resolvePostForCreator(postId: PostId): Post? {
        val adapter = registry.adapterFor(postId.source) ?: return null
        val resolved = adapter.resolvePost(postId) ?: return null
        rememberResolvedPost(resolved)
        return resolved
    }

    fun rememberResolvedPost(post: Post) {
        val index = results.indexOfFirst { current -> current.id == post.id }
        if (index < 0) return
        results = results.toMutableList().apply {
            this[index] = post
        }
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

internal enum class CreatorSourceAvailabilityChange {
    UNCHANGED,
    RECONCILED,
    REFRESH_REQUIRED,
}

private fun creatorQueryHash(creator: CreatorProfile): String {
    val key = creator.uploadsQuery
        ?: creator.profileId
        ?: creator.profileUrl
        ?: creator.displayName
    return "creator:${creator.source.name}:$key"
}
