package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.statistics.statisticsCodexId
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.di.DataDependencies
import com.theoriacodex.app.di.SourceDependencies
import com.theoriacodex.app.media.OfflineMediaCoordinator
import com.theoriacodex.app.media.recoverRemoteMedia
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.statistics.statisticsTagsForPost
import com.theoriacodex.app.codex.SAVED_CODEX_ID
import com.theoriacodex.app.codex.isVirtualCodex
import com.theoriacodex.app.series.SeriesReader
import com.theoriacodex.app.series.seriesIdOf
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerPlaybackProgress
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.prepareViewerPostsForLaunch
import com.theoriacodex.data.repository.CodexPostOpen
import com.theoriacodex.data.repository.RecentPostSection
import com.theoriacodex.data.repository.ReadingPosition
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

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
    /** The live feed a Viewer stream pages through, or null for a stream no feed owns. */
    private val feedOwner: (ViewerStreamSource) -> ViewerFeedOwner?,
    private val offlineMedia: OfflineMediaCoordinator,
    private val providerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Reads series chapters; a [ViewerStreamSource.SERIES] session goes to it and leaves no trace anywhere else. */
    private val series: SeriesReader? = null,
) : ViewerPostResolver {
    override fun requiresResolution(post: Post): Boolean =
        post.id in offlineMedia.store.snapshot.value.availablePostIds && !offlineMedia.store.hasOfflineLocations(post)

    override suspend fun resolve(session: ViewerSessionIdentity, postId: PostId): Post? {
        val source = ViewerStreamSource.entries.firstOrNull { it.name == session.streamKey } ?: ViewerStreamSource.SEARCH
        if (source == ViewerStreamSource.SERIES) return checkNotNull(series).resolve(session.queryHash, postId)
        return resolvePost(postId, source)
    }

    suspend fun persistResolvedPost(resolvedPost: Post, streamSource: ViewerStreamSource) {
        val post = offlineMedia.withoutOfflineLocations(resolvedPost)
        val feed = feedOwner(streamSource)
        when {
            feed != null -> feed.rememberResolvedPost(post)
            streamSource == ViewerStreamSource.CODEX || streamSource == ViewerStreamSource.RECENTS -> {
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
        offlineMedia.find(postId)?.let { return it }
        val adapter = checkNotNull(sources.registry.adapterFor(postId.source)) {
            "${postId.source.displayName()} is unavailable"
        }
        val resolved = withContext(providerDispatcher) {
            sources.postResolutions.resolve(postId) { adapter.resolvePost(postId) }
        } ?: return null
        persistResolvedPost(resolved, streamSource)
        return resolved
    }

    /**
     * Recovers working media for [post]; a result that no longer [stillApplies] is dropped before anything records it.
     * A series chapter's page addresses expire within minutes, so its pages are asked for again from the series
     * [queryHash] names.
     */
    suspend fun recoverMedia(
        post: Post,
        failedMedia: ImageRef,
        streamSource: ViewerStreamSource,
        queryHash: String? = null,
        stillApplies: () -> Boolean = { true },
    ): Post? {
        if (streamSource == ViewerStreamSource.SERIES) {
            return runCatchingPreservingCancellation { series?.resolve(queryHash, post.id) }.getOrNull()?.takeIf { stillApplies() }
        }
        val recovered = try {
            withContext(providerDispatcher) { recoverRemoteMedia(sources.registry, post, failedMedia) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        if (!stillApplies()) return null
        persistResolvedPost(recovered, streamSource)
        return recovered
    }

    val relatedShelves = ViewerRelatedShelves(feedOwner)

    fun loadMore(streamSource: ViewerStreamSource) {
        feedOwner(streamSource)?.loadNextPage()
    }

    suspend fun preparePostsForLaunch(
        posts: List<Post>,
        context: ViewerLaunchContext,
    ): List<Post> {
        val offlineIds = offlineMedia.store.snapshot.value.availablePostIds
        val selected = context.startIndex.coerceIn(0, posts.lastIndex.coerceAtLeast(0))
        // Normalizing every gallery can traverse thousands of media refs before navigation.
        val availablePosts = withContext(providerDispatcher) {
            posts.mapIndexed { index, post ->
                currentCoroutineContext().ensureActive()
                val portable = offlineMedia.withoutOfflineLocations(post)
                if (index in selected..selected + 1 && post.id in offlineIds) offlineMedia.find(post.id) ?: portable else portable
            }
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
        if (streamSource == ViewerStreamSource.SERIES) {
            val id = seriesIdOf(identity.queryHash) ?: return null
            return series?.session(id, selectedId.sourcePostId, request.selectedMediaIndex, identity.value)
        }
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
            val post = data.codexRepository.getPost(postId) ?: recentPosts[postId] ?: offlineMedia.find(postId)
            if (post != null) restored[postId] = post
        }
        val selected = restored[selectedId]
        if (selected == null || request.selectedMediaIndex >= selected.media.size.coerceAtLeast(1)) {
            val resolved = resolvePostWithoutSaving(selectedId)
            if (resolved != null) restored[selectedId] = resolved
        }
        if (selectedId !in restored) return null
        val posts = orderedIds.mapNotNull(restored::get)
        val selectedIndex = posts.indexOfFirst { it.id == selectedId }
        if (selectedIndex < 0) return null
        return ViewerSession(
            posts = preparePostsForLaunch(posts, restoredContext.copy(startIndex = selectedIndex)),
            context = restoredContext.copy(
                startIndex = selectedIndex,
                recentsSection = request.recentsSection ?: restoredContext.recentsSection,
            ),
            // A cold reconstruction is the saved ordered window, never a fresh/unrelated feed.
            liveSearchBinding = false,
            sessionId = identity.value,
            initialMediaIndices = savedMediaIndices(request.recentsSection ?: recentPostSectionForViewer(restoredContext)) +
                (selectedId to request.selectedMediaIndex),
        )
    }

    /** Resume feed and Recents launches through the same saved-position policy. */
    suspend fun prepareResumableSession(
        session: ViewerSession,
        startOver: Boolean = false,
    ): ViewerSession {
        val posts = session.posts
        val context = session.context
        val index = context.startIndex.coerceIn(0, posts.lastIndex)
        val selected = posts[index]
        val section = recentPostSectionForViewer(context)
        val mediaIndices = savedMediaIndices(section)
        val mediaNumber = if (startOver) 1 else (mediaIndices[selected.id] ?: 0) + 1
        val prepared = preparePostsForLaunch(posts, context).toMutableList()
        if (mediaNumber > prepared[index].media.size.coerceAtLeast(1)) {
            resolvePostWithoutSaving(selected.id)?.let { prepared[index] = it }
        }
        if (startOver) {
            runCatchingPreservingCancellation {
                data.readingPositions.record(ReadingPosition(selected.id, section, 1, System.currentTimeMillis()))
            }
        }
        return session.copy(
            posts = prepared,
            initialMediaIndices = mediaIndices + (selected.id to mediaNumber - 1),
        )
    }

    private suspend fun savedMediaIndices(section: RecentPostSection): Map<PostId, Int> =
        runCatchingPreservingCancellation {
            data.readingPositions.snapshot().filter { it.section == section }
                .associate { it.postId to it.mediaNumber - 1 }
        }.getOrDefault(emptyMap())

    /** Finds a post offline or on its site, giving up after 15 seconds; unlike [resolvePost] it records nothing. */
    suspend fun resolvePostWithoutSaving(postId: PostId): Post? = withTimeoutOrNull(15_000L) {
        offlineMedia.find(postId)
            ?: runCatchingPreservingCancellation {
                withContext(providerDispatcher) {
                    sources.registry.adapterFor(postId.source)?.let { adapter ->
                        sources.postResolutions.resolve(postId) { adapter.resolvePost(postId) }
                    }
                }
            }.getOrNull()
    }

    suspend fun recordVisiblePost(
        post: Post,
        viewedMediaNumber: Int,
        session: ViewerSession?,
    ) {
        if (recordSeriesProgress(session) { recordPageProgress(it, post, viewedMediaNumber) }) return
        val origin = historyOrigin(session?.context)
        recordReadingPosition(post, viewedMediaNumber, session)
        runCatchingPreservingCancellation { data.statisticsRepository.recordPostAvailable(post) }
        val codexOpen = statisticsCodexId(session?.context)?.let { codexId ->
            CodexPostOpen(
                codexId, statisticsPostKey(post.id),
                post.title?.takeIf(String::isNotBlank) ?: "${post.id.source.displayName()} ${post.id.sourcePostId}",
            )
        }
        runCatchingPreservingCancellation {
            data.statisticsRepository.recordWatchedPost(
                source = post.id.source,
                tags = statisticsTagsForPost(post),
                codexOpen = codexOpen,
            )
        }
        data.recentsRepository.recordWatchedPost(
            post = offlineMedia.withoutOfflineLocations(post),
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
        if (recordSeriesProgress(session) { recordPageProgress(it, post, viewedMediaNumber) }) return
        val origin = historyOrigin(session?.context)
        recordReadingPosition(post, viewedMediaNumber, session)
        runCatchingPreservingCancellation {
            data.recentsRepository.recordWatchedMediaProgress(
                // This callback can create the membership before the visible-post write completes.
                post = offlineMedia.withoutOfflineLocations(post),
                origin = origin,
                originQueryHash = session?.context?.queryHash,
                section = recentPostSectionForViewer(session?.context),
                viewedMediaNumber = viewedMediaNumber,
            )
        }
    }

    /** An episode's playback position, which only a series session reports and only its series records. */
    suspend fun recordPlaybackProgress(post: Post, progress: ViewerPlaybackProgress, session: ViewerSession) {
        recordSeriesProgress(session) { recordPlaybackProgress(it, post, progress) }
    }

    /** Records progress through a series entry on its series and reports true, so History and statistics never see it. */
    private suspend fun recordSeriesProgress(session: ViewerSession?, record: suspend SeriesReader.(ViewerSession) -> Unit): Boolean {
        if (session?.context?.streamSource != ViewerStreamSource.SERIES) return false
        runCatchingPreservingCancellation { series?.record(session) }
        return true
    }

    private suspend fun recordReadingPosition(post: Post, mediaNumber: Int, session: ViewerSession?) {
        runCatchingPreservingCancellation {
            data.readingPositions.record(
                ReadingPosition(post.id, recentPostSectionForViewer(session?.context), mediaNumber, System.currentTimeMillis()),
            )
        }
    }
}

/**
 * The origin History stores for a post watched from [context]. A trail is stored as a search: History and profile backups
 * keep the origin by name, and a build from before trails cannot read a name it does not know.
 */
internal fun historyOrigin(context: ViewerLaunchContext?): ViewerStreamSource =
    context?.streamSource?.takeUnless { it == ViewerStreamSource.TRAIL } ?: ViewerStreamSource.SEARCH

internal fun recentPostSectionForViewer(context: ViewerLaunchContext?): RecentPostSection {
    val origin = context?.streamSource ?: ViewerStreamSource.SEARCH
    return context?.recentsSection ?: RecentPostSection.fromOrigin(origin)
}

/**
 * The related shelf of the feed a Viewer session came from, which the Viewer shows as its strip. Only the feeds in
 * [SOURCES] keep one; for any other source each call does nothing.
 */
internal class ViewerRelatedShelves(private val feedOwner: (ViewerStreamSource) -> ViewerFeedOwner?) {
    /** "More like this" from the Viewer. */
    fun show(source: ViewerStreamSource, post: Post) {
        feedOwner(source)?.related?.show(post)
    }

    fun dismiss(source: ViewerStreamSource) {
        feedOwner(source)?.related?.clear()
    }

    fun retry(source: ViewerStreamSource) {
        feedOwner(source)?.related?.retry()
    }

    companion object {
        val SOURCES = setOf(ViewerStreamSource.SEARCH, ViewerStreamSource.FOR_YOU, ViewerStreamSource.CREATOR_PROFILE)
    }
}

/** The Codex a Viewer session was opened from, when its posts can be removed from it: not a feed, nor a virtual Codex. */
internal fun ViewerLaunchContext.removableCodexId(): String? = queryHash.removePrefix("codex:").takeIf { id ->
    streamSource == ViewerStreamSource.CODEX && id != queryHash && id != SAVED_CODEX_ID && !isVirtualCodex(id)
}
