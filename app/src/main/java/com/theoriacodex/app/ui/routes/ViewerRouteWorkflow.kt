package com.theoriacodex.app.ui.routes

import android.content.Context
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.media.appClipboardConfirmationMessage
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.statistics.statisticsTagsForPost
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.prepareViewerPostsForLaunch
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ReadingPosition
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges Viewer route work to the application-scoped engines and repositories.
 *
 * The Viewer owns its screen state and jobs. This bridge owns only cross-route persistence and
 * restoration policy, keeping those decisions out of both the renderer and the navigation shell.
 */
internal class ViewerRouteWorkflow(
    private val data: DataDependencies,
    private val cacheScope: CoroutineScope,
    private val sources: SourceDependencies,
    private val searchOwner: () -> SearchRouteOwnerHandle?,
    private val forYouOwner: () -> ForYouRouteOwnerHandle?,
    private val creatorOwner: () -> CreatorRouteOwnerHandle?,
    private val offlineMedia: com.theoriacodex.app.media.OfflineMediaCoordinator? = null,
) {
    suspend fun persistResolvedPost(post: Post, streamSource: ViewerStreamSource) {
        when (streamSource) {
            ViewerStreamSource.SEARCH -> if (
                searchOwner()?.dispatch(SearchAction.RememberResolvedPost(post)) != true
            ) {
                return
            }
            ViewerStreamSource.FOR_YOU -> if (forYouOwner()?.rememberResolvedPost(post) != true) {
                return
            }
            ViewerStreamSource.CREATOR_PROFILE -> if (
                creatorOwner()?.rememberResolvedPost(post) != true
            ) {
                return
            }
            ViewerStreamSource.RELATED -> Unit
            ViewerStreamSource.CODEX,
            ViewerStreamSource.RECENTS,
            -> {
                cacheScope.launch {
                    runCatchingPreservingCancellation {
                        data.codexRepository.updatePost(post)
                        data.cacheRepository.cacheThumbnail(post)
                    }
                }
            }
        }
    }

    suspend fun resolvePost(postId: PostId, streamSource: ViewerStreamSource): Post? {
        offlineMedia?.find(postId)?.let { return it }
        val adapter = checkNotNull(sources.registry.adapterFor(postId.source)) {
            "${postId.source.name} is unavailable"
        }
        val resolved = adapter.resolvePost(postId) ?: return null
        persistResolvedPost(resolved, streamSource)
        return resolved
    }

    suspend fun recoverMedia(
        post: Post,
        failedMedia: ImageRef,
        streamSource: ViewerStreamSource,
    ): Post? {
        val recovered = try {
            recoverRemoteMedia(sources.registry, post, failedMedia)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        persistResolvedPost(recovered, streamSource)
        return recovered
    }

    fun loadMore(streamSource: ViewerStreamSource) {
        when (streamSource) {
            ViewerStreamSource.SEARCH -> searchOwner()?.dispatch(SearchAction.LoadNextPage)
            ViewerStreamSource.FOR_YOU -> forYouOwner()?.dispatch(ForYouAction.LoadNextPage)
            ViewerStreamSource.CREATOR_PROFILE -> creatorOwner()
                ?.dispatch(com.theoriacodex.app.creator.state.CreatorAction.LoadNextPage)
            ViewerStreamSource.RELATED,
            ViewerStreamSource.CODEX,
            ViewerStreamSource.RECENTS,
            -> Unit
        }
    }

    suspend fun preparePostsForLaunch(
        posts: List<Post>,
        context: ViewerLaunchContext,
    ): List<Post> {
        val offlineIds = offlineMedia?.store?.snapshot?.value?.availablePostIds.orEmpty()
        val availablePosts = posts.map { post ->
            val portable = offlineMedia?.withoutOfflineLocations(post) ?: post
            if (post.id in offlineIds) offlineMedia?.find(post.id) ?: portable else portable
        }
        return prepareViewerPostsForLaunch(availablePosts, context) { selectedPost ->
            runCatchingPreservingCancellation {
                resolvePost(selectedPost.id, context.streamSource)
            }.getOrNull()
        }
    }

    suspend fun restoreSession(request: ViewerRestorationRequest): ViewerSession? {
        val identity = request.session
        val streamSource = identity.streamKey
            ?.let { name -> ViewerStreamSource.entries.firstOrNull { source -> source.name == name } }
            ?: return null
        if (streamSource == ViewerStreamSource.RELATED) return null
        val selectedId = request.selectedPostId ?: return null
        val restoredContext = data.uiRestoreRepository.observeViewerLaunchContext()
            .first()
            ?.takeIf { context ->
                context.streamSource == streamSource &&
                    (identity.queryHash == null || context.queryHash == identity.queryHash)
            }
            ?: ViewerLaunchContext(
                queryHash = identity.queryHash.orEmpty(),
                startIndex = 0,
                streamSource = streamSource,
                scrollOffsetHint = 0,
            )
        val recentPosts = data.recentsRepository.observeWatchedPosts().first().associate { it.post.id to it.post }
        val orderedIds = request.orderedPostIds.ifEmpty { listOf(selectedId) }.distinct()
        val restored = linkedMapOf<PostId, Post>()
        for (postId in orderedIds) {
            val post = offlineMedia?.find(postId) ?: data.codexRepository.getPost(postId) ?: recentPosts[postId]
            if (post != null) restored[postId] = post
        }
        val selected = restored[selectedId]
        if (selected == null || request.selectedMediaIndex >= selected.media.size.coerceAtLeast(1)) {
            val resolved = resolveRestoredPost(selectedId)
            if (resolved != null) restored[selectedId] = resolved
        }
        if (selectedId !in restored) return null
        val posts = orderedIds.mapNotNull(restored::get)
        val selectedIndex = posts.indexOfFirst { it.id == selectedId }
        if (selectedIndex < 0) return null
        return ViewerSession(
            posts = posts,
            context = restoredContext.copy(
                startIndex = selectedIndex,
                recentsSection = request.recentsSection ?: restoredContext.recentsSection,
            ),
            // A cold reconstruction is the saved ordered window, never a fresh/unrelated feed.
            liveSearchBinding = false,
            sessionId = identity.value,
            initialMediaIndex = request.selectedMediaIndex,
        )
    }

    suspend fun prepareRecentSession(
        posts: List<Post>,
        context: ViewerLaunchContext,
        startOver: Boolean = false,
    ): ViewerSession {
        val index = context.startIndex.coerceIn(0, posts.lastIndex)
        val selected = posts[index]
        val section = recentPostSectionForViewer(context)
        val mediaNumber = if (startOver) 1 else runCatchingPreservingCancellation {
            data.readingPositions.get(selected.id, section)?.mediaNumber
        }.getOrNull() ?: 1
        val prepared = preparePostsForLaunch(posts, context).toMutableList()
        if (mediaNumber > prepared[index].media.size.coerceAtLeast(1)) {
            resolveRestoredPost(selected.id)?.let { prepared[index] = it }
        }
        if (startOver) {
            runCatchingPreservingCancellation {
                data.readingPositions.record(ReadingPosition(selected.id, section, 1, System.currentTimeMillis()))
            }
        }
        return ViewerSession(posts = prepared, context = context, initialMediaIndex = mediaNumber - 1)
    }

    private suspend fun resolveRestoredPost(postId: PostId): Post? = withTimeoutOrNull(15_000L) {
        offlineMedia?.find(postId)
            ?: runCatchingPreservingCancellation { sources.registry.adapterFor(postId.source)?.resolvePost(postId) }.getOrNull()
    }

    suspend fun recordVisiblePost(
        post: Post,
        viewedMediaNumber: Int,
        session: ViewerSession?,
    ) {
        val origin = session?.context?.streamSource ?: ViewerStreamSource.SEARCH
        recordReadingPosition(post, viewedMediaNumber, session)
        runCatchingPreservingCancellation {
            data.statisticsRepository.recordWatchedPost(
                source = post.id.source,
                tags = statisticsTagsForPost(post),
            )
        }
        data.recentsRepository.recordWatchedPost(
            post = offlineMedia?.withoutOfflineLocations(post) ?: post,
            origin = origin,
            originQueryHash = session?.context?.queryHash,
            section = recentPostSectionForViewer(session?.context),
            viewedMediaNumber = viewedMediaNumber,
        )
    }

    suspend fun recordVisibleMediaProgress(
        post: Post,
        viewedMediaNumber: Int,
        session: ViewerSession?,
    ) {
        val origin = session?.context?.streamSource ?: ViewerStreamSource.SEARCH
        recordReadingPosition(post, viewedMediaNumber, session)
        runCatchingPreservingCancellation {
            data.recentsRepository.recordWatchedMediaProgress(
                post = post,
                origin = origin,
                originQueryHash = session?.context?.queryHash,
                section = recentPostSectionForViewer(session?.context),
                viewedMediaNumber = viewedMediaNumber,
            )
        }
    }

    private suspend fun recordReadingPosition(post: Post, mediaNumber: Int, session: ViewerSession?) {
        runCatchingPreservingCancellation {
            data.readingPositions.record(
                ReadingPosition(post.id, recentPostSectionForViewer(session?.context), mediaNumber, System.currentTimeMillis()),
            )
        }
    }
}

internal fun recentPostSectionForViewer(context: ViewerLaunchContext?): RecentPostSection {
    val origin = context?.streamSource ?: ViewerStreamSource.SEARCH
    return context?.recentsSection ?: RecentPostSection.fromOrigin(origin)
}

internal fun shareViewerPostMessage(
    context: Context,
    post: Post?,
    onPostUrlCopied: () -> Unit = {},
): String? {
    val copied = post?.let { copyPostUrlToClipboard(context, it) } == true
    if (copied) onPostUrlCopied()
    return if (copied) appClipboardConfirmationMessage("Post URL copied") else "No post URL available"
}
