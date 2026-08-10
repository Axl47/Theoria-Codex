package com.theoriacodex.app.ui.routes

import android.content.Context
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.media.PostDownloadService
import com.theoriacodex.app.media.appClipboardConfirmationMessage
import com.theoriacodex.app.media.copyPostUrlToClipboard
import com.theoriacodex.app.media.isPixivUgoiraPost
import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.statistics.statisticsTagsForPost
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.prepareViewerPostsForLaunch
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Bridges Viewer route work to the application-scoped engines and repositories.
 *
 * The Viewer owns its screen state and jobs. This bridge owns only cross-route persistence and
 * restoration policy, keeping those decisions out of both the renderer and the navigation shell.
 */
internal class ViewerRouteWorkflow(
    private val data: DataDependencies,
    private val sources: SourceDependencies,
    private val searchOwner: () -> SearchRouteOwnerHandle?,
    private val forYouOwner: () -> ForYouRouteOwnerHandle?,
    private val creatorOwner: () -> CreatorRouteOwnerHandle?,
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
            ViewerStreamSource.CODEX,
            ViewerStreamSource.RECENTS,
            -> {
                data.codexRepository.updatePost(post)
                runCatchingPreservingCancellation {
                    data.cacheRepository.cacheThumbnail(post)
                }
            }
        }
    }

    suspend fun resolvePost(postId: PostId, streamSource: ViewerStreamSource): Post? {
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
            ViewerStreamSource.CODEX,
            ViewerStreamSource.RECENTS,
            -> Unit
        }
    }

    suspend fun preparePostsForLaunch(
        posts: List<Post>,
        context: ViewerLaunchContext,
    ): List<Post> {
        return prepareViewerPostsForLaunch(posts, context) { selectedPost ->
            runCatchingPreservingCancellation {
                resolvePost(selectedPost.id, context.streamSource)
            }.getOrNull()
        }
    }

    suspend fun restoreSession(identity: ViewerSessionIdentity): ViewerSession? {
        val streamSource = identity.streamKey
            ?.let { name -> ViewerStreamSource.entries.firstOrNull { source -> source.name == name } }
            ?: return null
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
        val posts = when (streamSource) {
            ViewerStreamSource.SEARCH -> searchOwner()?.currentState()?.content?.results.orEmpty()
            ViewerStreamSource.FOR_YOU -> forYouOwner()?.currentState()?.results.orEmpty()
            ViewerStreamSource.CREATOR_PROFILE -> creatorOwner()?.currentState()?.results.orEmpty()
            ViewerStreamSource.RECENTS -> data.recentsRepository
                .observeWatchedPosts()
                .first()
                .filter { entry ->
                    restoredContext.recentsSection == null ||
                        entry.section == restoredContext.recentsSection
                }
                .map { entry -> entry.post }
            ViewerStreamSource.CODEX -> identity.queryHash
                ?.removePrefix("codex:")
                ?.takeIf(String::isNotBlank)
                ?.let { codexId ->
                    data.codexRepository.observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED).first()
                }
                .orEmpty()
        }
        if (posts.isEmpty()) return null
        return ViewerSession(
            posts = posts,
            context = restoredContext,
            liveSearchBinding = streamSource in setOf(
                ViewerStreamSource.SEARCH,
                ViewerStreamSource.FOR_YOU,
                ViewerStreamSource.CREATOR_PROFILE,
            ),
            sessionId = identity.value,
        )
    }

    suspend fun recordVisiblePost(post: Post, session: ViewerSession?) {
        val origin = session?.context?.streamSource ?: ViewerStreamSource.SEARCH
        runCatchingPreservingCancellation {
            data.statisticsRepository.recordWatchedPost(
                source = post.id.source,
                tags = statisticsTagsForPost(post),
            )
        }
        data.recentsRepository.recordWatchedPost(
            post = post,
            origin = origin,
            originQueryHash = session?.context?.queryHash,
            section = recentPostSectionForViewer(session?.context),
        )
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

internal suspend fun downloadViewerMediaMessage(
    context: Context,
    sources: SourceDependencies,
    request: ViewerDownloadRequest?,
): String = when {
    request == null -> "Media unavailable"
    isPixivUgoiraPost(request.post) -> sources.pixivUgoiraClient
        .exportToMp4(
            context = context,
            postId = request.post.id.sourcePostId,
            title = request.post.title,
        )
        .fold(
            onSuccess = { "Saved MP4 to device" },
            onFailure = { error ->
                "Could not export MP4: ${error.message ?: "unknown error"}"
            },
        )
    PostDownloadService.enqueueViewerDownload(
        context = context,
        post = request.post,
        media = request.media,
        pageIndex = request.pageIndex,
        totalPages = request.totalPages,
    ) -> "Download started"
    else -> "Media unavailable"
}
