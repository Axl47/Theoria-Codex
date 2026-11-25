package com.theoriacodex.app.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.comments.PostCommentsController
import com.theoriacodex.domain.model.keepingListingDataOf
import com.theoriacodex.app.comments.UnsupportedCommentsLoader
import com.theoriacodex.app.ui.state.AppRouteSavedStateKeys
import com.theoriacodex.app.ui.state.RouteStateOwner
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.app.viewer.state.ViewerMediaState
import com.theoriacodex.app.viewer.state.ViewerPageState
import com.theoriacodex.domain.model.ViewerPlaybackMode
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchRequest
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.app.viewer.state.ViewerResolutionStatus
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.app.viewer.state.reduceViewerState
import com.theoriacodex.data.repository.ANIME_FINISHED_PERCENT
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface ViewerPostResolver {
    suspend fun resolve(session: ViewerSessionIdentity, postId: PostId): Post?
    fun requiresResolution(post: Post): Boolean = false
}

fun interface ViewerMediaPrefetcher {
    suspend fun prefetch(session: ViewerSessionIdentity, media: ViewerMediaState, request: ViewerPrefetchRequest): ViewerPrefetchResult
}

class ViewerViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val postResolver: ViewerPostResolver,
    private val mediaPrefetcher: ViewerMediaPrefetcher,
    private val playbackModeProvider: () -> ViewerPlaybackMode,
    scopeOverride: CoroutineScope? = null,
    commentsLoader: CommentsLoading = UnsupportedCommentsLoader,
) : ViewModel(), RouteStateOwner<ViewerUiState, ViewerAction, ViewerEffect.Route> {
    private val lock = Any()
    private val workScope = scopeOverride ?: viewModelScope
    private val effectChannel = Channel<ViewerEffect.Route>(capacity = Channel.BUFFERED)
    private val sessionJobs = mutableMapOf<ViewerWorkKey, Job>()
    private val recordedVisiblePosts = mutableSetOf<PostId>()
    private val prefetchSemaphore = Semaphore(VIEWER_PREFETCH_MAX_CONCURRENCY)
    private var desiredPrefetchKeys = emptySet<ViewerMediaKey>()
    private val prefetchLedger = ViewerPrefetchLedger()
    private val firstMediaShown = MutableStateFlow(false)
    private var lastCurrentPostId: PostId? = null

    private val mutableState = MutableStateFlow(restoredViewerState(savedStateHandle))
    override val state: StateFlow<ViewerUiState> = mutableState.asStateFlow()
    override val effects: Flow<ViewerEffect.Route> = effectChannel.receiveAsFlow()
    private val mutableSession = MutableStateFlow<ViewerSession?>(null)
    @Volatile private var reportingSession: ViewerSession? = null
    @Volatile private var watchedThrough: PostId? = null
    val session: StateFlow<ViewerSession?> = mutableSession.asStateFlow()

    val comments = PostCommentsController(commentsLoader, workScope, beforeFirstFetch = ::awaitFirstMediaShown)

    val pendingRestoration: ViewerSessionIdentity?
        get() = state.value.takeIf { current -> current.pages.isEmpty() }?.session

    val restorationRequest: ViewerRestorationRequest?
        get() = pendingRestoration?.let { identity ->
            ViewerRestorationRequest(
                session = identity,
                selectedPostId = decodeViewerPostId(savedStateHandle[ViewerSavedStateKeys.SELECTED_POST]),
                orderedPostIds = savedStateHandle.get<ArrayList<String>>(ViewerSavedStateKeys.ORDERED_POSTS)
                    .orEmpty().mapNotNull(::decodeViewerPostId),
                selectedMediaIndex = savedStateHandle[ViewerSavedStateKeys.MEDIA_INDEX] ?: 0,
                recentsSection = savedStateHandle.get<String>(ViewerSavedStateKeys.RECENTS_SECTION)?.let { encoded ->
                    com.theoriacodex.data.repository.RecentPostSection.entries.firstOrNull { it.name == encoded }
                },
            )
        }

    override fun onAction(action: ViewerAction) {
        if (action is ViewerAction.QueuePrefetch) {
            queuePrefetch(action.requests)
            return
        }
        if (action is ViewerAction.RequestPageResolution && !mayResolve(action.postId)) return
        val reduction = synchronized(lock) {
            if (action is ViewerAction.ReplaceSession) resetSessionWork(action)
            var result = reduceViewerState(mutableState.value, action)
            if (action is ViewerAction.ReplaceSession &&
                (action.session != mutableState.value.session || mutableState.value.pages.isEmpty())) {
                result = reduceViewerState(result.state, ViewerAction.SetPlaybackMode(startingPlaybackMode(result.state.series)))
            }
            result.also {
                mutableState.value = result.state
                persist(result.state)
                noteFirstMediaShown(result.state)
            }
        }
        reduction.effects.forEach(::handleEffect)
        if (action is ViewerAction.SelectPage || action is ViewerAction.Navigate ||
            action is ViewerAction.ReplaceSession || action is ViewerAction.AppendPosts ||
            action is ViewerAction.IncludeRelatedPosts || action is ViewerAction.PlaybackCompleted ||
            action is ViewerAction.SlideshowAdvance) {
            reconcileResolutionLookahead()
            showComments()
        }
    }

    private fun mayResolve(postId: PostId): Boolean {
        val current = mutableState.value
        return current.series !is ViewerSeries.Watching || postId == current.currentPage?.post?.id || postId == current.nextEpisodeId()
    }

    private fun ViewerUiState.nextEpisodeId(): PostId? {
        if (watchedThrough == null || currentPage?.post?.id != watchedThrough) return null
        val navigation = viewerPostNavigation(this)
        return navigation.pages.getOrNull(navigation.currentIndex + 1)?.post?.id
    }

    private fun noteWatchedPoint(post: Post, progress: ViewerPlaybackProgress) {
        val current = state.value
        if (current.series !is ViewerSeries.Watching || current.currentPage?.post?.id != post.id || watchedThrough == post.id) return
        if (progress.durationMs <= 0 || progress.positionMs * 100 < progress.durationMs * ANIME_FINISHED_PERCENT) return
        watchedThrough = post.id
        reconcileResolutionLookahead()
    }

    private fun startingPlaybackMode(series: ViewerSeries?): ViewerPlaybackMode =
        if (series is ViewerSeries.Watching) ViewerPlaybackMode.NEXT else playbackModeProvider()

    private fun showComments() {
        val current = state.value
        current.currentPage?.post?.let(comments::show)
        viewerPostNavigation(current).neighbours.forEach { page -> comments.show(page.post) }
    }

    private fun resetSessionWork(action: ViewerAction.ReplaceSession) {
        if (action.session != mutableState.value.session) {
            recordedVisiblePosts.clear()
            firstMediaShown.value = false
            lastCurrentPostId = null
            watchedThrough = null
            comments.reset()
        }
        cancelSessionJobs()
    }

    private fun noteFirstMediaShown(state: ViewerUiState) {
        if (state.currentMedia?.ready == true || state.mediaError != null) firstMediaShown.value = true
    }

    fun replaceSession(session: ViewerSession) {
        val isNewLaunch = state.value.session != session.toViewerSessionIdentity()
        mutableSession.value = session
        reportingSession = session
        onAction(session.toReplaceViewerSessionAction(::requiresResolution))
        if (isNewLaunch) {
            val target = session.focusComment
            val post = session.posts.getOrNull(session.context.startIndex)
            if (target != null && post != null) comments.focus(post, target)
        }
    }

    fun appendSessionPosts(session: ViewerSession, basis: List<ViewerPageState>, posts: List<Post>) {
        val current = mutableSession.value ?: return
        if (current.sessionId != session.sessionId) return
        val pages = state.value.pages
        val added = if (pages === basis) posts else newViewerPosts(pages.map { it.post }, posts)
        if (added.isEmpty()) return
        onAction(
            ViewerAction.AppendPosts(
                session = current.toViewerSessionIdentity(),
                posts = added,
                resolutionRequiredPostIds = current.resolutionRequiredPostIds(added, ::requiresResolution),
                initialMediaIndices = current.initialMediaIndices,
            ),
        )
    }

    fun includeRelatedPosts(seedId: PostId, posts: List<Post>) {
        val current = mutableSession.value ?: return
        onAction(
            ViewerAction.IncludeRelatedPosts(
                session = current.toViewerSessionIdentity(),
                seedId = seedId,
                posts = posts,
                resolutionRequiredPostIds = current.resolutionRequiredPostIds(posts, ::requiresResolution),
                initialMediaIndices = current.initialMediaIndices,
            ),
        )
    }

    private fun requiresResolution(post: Post): Boolean = postResolver.requiresResolution(post)

    fun applyResolvedPost(resolved: Post, recovered: Boolean = false) {
        if (mutableSession.value == null) return
        val listed = state.value.pages.firstOrNull { page -> page.post.id == resolved.id }?.post ?: return
        val post = resolved.keepingListingDataOf(listed)
        synchronized(lock) {
            sessionJobs.keys.filterIsInstance<ViewerWorkKey.Prefetch>().filter { it.mediaKey.postId == post.id }
                .forEach { key -> sessionJobs.remove(key)?.cancel() }
            prefetchLedger.forget(post.id)
        }
        state.value.session?.let { identity ->
            onAction(ViewerAction.ResolutionCompleted(identity, post, System.currentTimeMillis(), recovered))
        }
    }

    fun recordVisiblePost(
        identity: ViewerSessionIdentity,
        post: Post,
        viewedMediaNumber: Int,
        record: suspend (Post, Int, ViewerSession) -> Unit,
    ) {
        val current = synchronized(lock) {
            val active = mutableSession.value ?: return
            if (active.toViewerSessionIdentity() != identity ||
                state.value.pages.none { it.post.id == post.id } || !recordedVisiblePosts.add(post.id)
            ) return
            active
        }
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingPreservingCancellation { record(post, viewedMediaNumber, current) }
        }
    }

    fun recordVisibleMedia(
        post: Post,
        viewedMediaNumber: Int,
        pageOffset: Float?,
        record: suspend (Post, Int, Float?, ViewerSession) -> Unit,
    ) {
        val current = mutableSession.value ?: return
        if (state.value.currentPage?.post?.id != post.id) return
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingPreservingCancellation { record(post, viewedMediaNumber, pageOffset, current) }
        }
    }

    fun recordPlaybackProgress(
        post: Post,
        progress: ViewerPlaybackProgress,
        record: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit,
    ) {
        noteWatchedPoint(post, progress)
        val reporting = reportingSession ?: return
        if (reporting.posts.none { it.id == post.id } && state.value.pages.none { it.post.id == post.id }) return
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingPreservingCancellation { record(post, progress, reporting) }
        }
    }

    fun clearSession(retainPresentation: Boolean = false) {
        synchronized(lock) {
            cancelSessionJobs()
            recordedVisiblePosts.clear()
            firstMediaShown.value = false
            lastCurrentPostId = null
            watchedThrough = null
            comments.reset()
            mutableSession.value = null
            val current = mutableState.value
            mutableState.value = if (retainPresentation) ViewerUiState(
                series = current.series,
                pages = current.pages,
                currentPageIndex = current.currentPageIndex,
                controls = current.controls.copy(
                    chromeVisible = false,
                    panel = null,
                    slideshow = false,
                    playback = current.controls.playback.copy(playing = false),
                ),
            ) else ViewerUiState.Empty
            clearSavedState()
        }
    }

    private fun handleEffect(effect: ViewerEffect) {
        if (effect.session != state.value.session) return
        when (effect) {
            is ViewerEffect.ResolvePost -> launchResolution(effect)
            is ViewerEffect.Route -> effectChannel.trySend(effect)
        }
    }

    private fun reconcileResolutionLookahead() {
        val current = state.value
        val session = current.session ?: return
        val navigation = viewerPostNavigation(current)
        val neighbours = if (current.series is ViewerSeries.Watching) {
            listOfNotNull(navigation.pages.getOrNull(navigation.currentIndex + 1)?.takeIf { it.post.id == current.nextEpisodeId() })
        } else {
            navigation.neighbours
        }
        val desired = setOfNotNull(current.currentPage?.post?.id) + neighbours.map { it.post.id }
        val obsolete = sessionJobs.keys.filterIsInstance<ViewerWorkKey.Resolution>().filter { it.postId !in desired }
        obsolete.forEach { key ->
            sessionJobs.remove(key)?.cancel()
            onAction(ViewerAction.ResolutionCancelled(session, key.postId))
        }
        requestCurrentPageResolution(current)
        neighbours
            .filter { it.resolution.status == ViewerResolutionStatus.IDLE }
            .forEach { onAction(ViewerAction.RequestPageResolution(it.post.id)) }
    }

    private fun requestCurrentPageResolution(current: ViewerUiState) {
        val page = current.currentPage ?: return
        val postId = page.post.id
        val returning = postId != lastCurrentPostId
        lastCurrentPostId = postId
        val status = page.resolution.status
        val wanted = status == ViewerResolutionStatus.IDLE ||
            (returning && status == ViewerResolutionStatus.FAILED && page.resolution.recoverable)
        if (wanted) onAction(ViewerAction.RequestPageResolution(postId))
    }

    private fun launchResolution(effect: ViewerEffect.ResolvePost) {
        launchSessionJob(ViewerWorkKey.Resolution(effect.postId)) {
            awaitResolutionTurn(effect.postId)
            onAction(ViewerAction.ResolutionStarted(effect.session, effect.postId))
            runCatchingPreservingCancellation {
                val outcome = withTimeoutOrNull(viewerResolutionTimeoutMs(state.value.series)) { Result.success(postResolver.resolve(effect.session, effect.postId)) }
                    ?: throw java.io.IOException("Post resolution timed out")
                val resolved = outcome.getOrThrow()
                coroutineContext.ensureActive()
                if (resolved == null) {
                    onAction(
                        ViewerAction.ResolutionFailed(
                            session = effect.session,
                            postId = effect.postId,
                            message = "Post was deleted",
                            recoverable = false,
                        )
                    )
                } else if (resolved.id != effect.postId) {
                    onAction(
                        ViewerAction.ResolutionFailed(
                            session = effect.session,
                            postId = effect.postId,
                            message = "Provider returned a different post",
                            recoverable = false,
                        )
                    )
                } else {
                    applyResolvedPost(resolved)
                }
            }.onFailure { error ->
                onAction(
                    ViewerAction.ResolutionFailed(
                        session = effect.session,
                        postId = effect.postId,
                        message = error.message ?: "Could not resolve this post",
                        recoverable = true,
                    )
                )
            }
        }
    }

    private fun launchPrefetch(
        session: ViewerSessionIdentity,
        request: ViewerPrefetchRequest,
        media: ViewerMediaState,
    ) {
        val downloaded = prefetchLedger.isDownloaded(request.key)
        launchSessionJob(ViewerWorkKey.Prefetch(request.key)) {
            awaitFirstMediaShown()
            if (downloaded) {
                runPrefetch(session, request, media)
            } else {
                prefetchSemaphore.withPermit { runPrefetch(session, request, media) }
            }
        }
    }

    private suspend fun runPrefetch(
        session: ViewerSessionIdentity,
        request: ViewerPrefetchRequest,
        media: ViewerMediaState,
    ) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (request.key !in desiredPrefetchKeys) throw CancellationException("Prefetch superseded")
        }
        val result = runCatchingPreservingCancellation {
            mediaPrefetcher.prefetch(session, media, request).also { currentCoroutineContext().ensureActive() }
        }.getOrElse {
            ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
        }
        synchronized(lock) {
            if (state.value.session == session) prefetchLedger.record(request.key, result)
        }
    }

    private suspend fun awaitFirstMediaShown() {
        withTimeoutOrNull(VIEWER_PREFETCH_READY_WAIT_MS) { firstMediaShown.first { shown -> shown } }
    }

    private suspend fun awaitResolutionTurn(postId: PostId) {
        fun ready(shown: Boolean, current: ViewerUiState) = shown || current.currentPage?.post?.id == postId
        if (ready(firstMediaShown.value, mutableState.value)) return
        withTimeoutOrNull(VIEWER_PREFETCH_READY_WAIT_MS) { combine(firstMediaShown, mutableState, ::ready).first { it } }
    }

    private fun queuePrefetch(requests: List<ViewerPrefetchRequest>) {
        synchronized(lock) {
            val current = state.value
            val session = current.session ?: return
            fun mediaFor(key: ViewerMediaKey) = current.pages.firstOrNull { page -> page.post.id == key.postId }
                ?.media?.getOrNull(key.mediaIndex)?.takeIf { media -> media.key == key }
            val wanted = requests.asSequence()
                .filterNot(prefetchLedger::isDone)
                .distinctBy(ViewerPrefetchRequest::key)
                .mapNotNull { request -> mediaFor(request.key)?.let { media -> request to media } }
                .toList()
            desiredPrefetchKeys = wanted.mapTo(mutableSetOf()) { (request, _) -> request.key }
            sessionJobs.keys
                .filterIsInstance<ViewerWorkKey.Prefetch>()
                .filterNot { key -> key.mediaKey in desiredPrefetchKeys }
                .forEach { key -> sessionJobs.remove(key)?.cancel() }
            wanted.forEach { (request, media) ->
                if (ViewerWorkKey.Prefetch(request.key) !in sessionJobs) launchPrefetch(session, request, media)
            }
        }
    }

    private fun launchSessionJob(key: ViewerWorkKey, block: suspend CoroutineScope.() -> Unit) {
        synchronized(lock) {
            sessionJobs.remove(key)?.cancel()
            val job = workScope.launch(start = CoroutineStart.LAZY, block = block)
            sessionJobs[key] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (sessionJobs[key] === job) sessionJobs.remove(key)
                }
            }
            job.start()
        }
    }

    private fun cancelSessionJobs() {
        desiredPrefetchKeys = emptySet()
        prefetchLedger.clear()
        sessionJobs.values.toList().forEach(Job::cancel)
        sessionJobs.clear()
    }

    private fun persist(rendered: ViewerUiState) {
        val current = rendered.forOriginalNavigation()
        val session = current.session ?: run {
            clearSavedState()
            return
        }
        saveIfChanged(AppRouteSavedStateKeys.VIEWER_SESSION_ID, session.value)
        saveIfChanged(ViewerSavedStateKeys.QUERY_HASH, session.queryHash)
        saveIfChanged(ViewerSavedStateKeys.STREAM_KEY, session.streamKey)
        val mediaIndex = current.currentPage?.targetMediaIndex ?: 0
        saveIfChanged(ViewerSavedStateKeys.MEDIA_INDEX, mediaIndex)
        current.currentPage?.let { page ->
            saveIfChanged(ViewerSavedStateKeys.SELECTED_POST, encodeViewerPostId(page.post.id))
            saveIfChanged(
                ViewerSavedStateKeys.ORDERED_POSTS,
                ArrayList(viewerRestorationWindow(current.pages, current.currentPageIndex)
                    .map { encodeViewerPostId(it.post.id) }),
            )
            saveIfChanged(ViewerSavedStateKeys.RECENTS_SECTION, mutableSession.value?.context?.recentsSection?.name)
        }
    }

    private fun <T> saveIfChanged(key: String, value: T) {
        if (!savedStateHandle.contains(key) || savedStateHandle.get<T>(key) != value) {
            savedStateHandle[key] = value
        }
    }

    private fun clearSavedState() {
        savedStateHandle.remove<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.QUERY_HASH)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.STREAM_KEY)
        savedStateHandle.remove<Int>(ViewerSavedStateKeys.MEDIA_INDEX)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.SELECTED_POST)
        savedStateHandle.remove<ArrayList<String>>(ViewerSavedStateKeys.ORDERED_POSTS)
        savedStateHandle.remove<String>(ViewerSavedStateKeys.RECENTS_SECTION)
    }

    override fun onCleared() {
        synchronized(lock) {
            cancelSessionJobs()
            effectChannel.close()
        }
    }

    companion object {
        fun factory(
            postResolver: ViewerPostResolver,
            mediaPrefetcher: ViewerMediaPrefetcher,
            playbackModeProvider: () -> ViewerPlaybackMode,
            commentsLoader: CommentsLoading = UnsupportedCommentsLoader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ViewerViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    postResolver = postResolver,
                    mediaPrefetcher = mediaPrefetcher,
                    playbackModeProvider = playbackModeProvider,
                    commentsLoader = commentsLoader,
                )
            }
        }
    }

    private sealed interface ViewerWorkKey {
        data class Resolution(val postId: PostId) : ViewerWorkKey
        data class Prefetch(val mediaKey: ViewerMediaKey) : ViewerWorkKey
    }
}

private class ViewerPrefetchLedger {
    private val downloaded = mutableSetOf<ViewerMediaKey>()
    private val decoded = mutableSetOf<ViewerMediaKey>()
    private val settled = mutableSetOf<ViewerMediaKey>()

    fun isDownloaded(key: ViewerMediaKey) = key in downloaded

    fun isDone(request: ViewerPrefetchRequest) = request.key in settled ||
        (request.key in downloaded && !(request.decode && request.key !in decoded))

    fun record(key: ViewerMediaKey, result: ViewerPrefetchResult) {
        when (result.outcome) {
            ViewerPrefetchOutcome.WARMED -> downloaded += key
            ViewerPrefetchOutcome.SKIPPED, ViewerPrefetchOutcome.FAILED -> settled += key
        }
        if (result.decoded) decoded += key
    }

    fun forget(postId: PostId) {
        listOf(downloaded, decoded, settled).forEach { keys -> keys.removeAll { key -> key.postId == postId } }
    }

    fun clear() {
        listOf(downloaded, decoded, settled).forEach(MutableSet<ViewerMediaKey>::clear)
    }
}

internal fun viewerResolutionTimeoutMs(series: ViewerSeries?): Long =
    if (series is ViewerSeries.Watching) VIEWER_SERIES_RESOLUTION_TIMEOUT_MS else VIEWER_RESOLUTION_TIMEOUT_MS

internal const val VIEWER_RESOLUTION_TIMEOUT_MS = 15_000L
internal const val VIEWER_SERIES_RESOLUTION_TIMEOUT_MS = 60_000L
internal const val VIEWER_PREFETCH_MAX_CONCURRENCY = 2
internal const val VIEWER_PREFETCH_READY_WAIT_MS = 1_500L

internal object ViewerSavedStateKeys {
    const val QUERY_HASH = "viewer_query_hash"
    const val STREAM_KEY = "viewer_stream_key"
    const val MEDIA_INDEX = "viewer_media_index"
    const val SELECTED_POST = "viewer_selected_post"
    const val ORDERED_POSTS = "viewer_ordered_posts"
    const val RECENTS_SECTION = "viewer_recents_section"
}

internal fun ViewerSession.toViewerSessionIdentity(): ViewerSessionIdentity {
    return ViewerSessionIdentity(
        value = sessionId,
        queryHash = context.queryHash,
        streamKey = context.streamSource.name,
    )
}

internal fun ViewerSession.toReplaceViewerSessionAction(
    requiresResolution: (Post) -> Boolean = { false },
): ViewerAction.ReplaceSession {
    return ViewerAction.ReplaceSession(
        session = toViewerSessionIdentity(),
        posts = posts.toList(),
        initialPageIndex = context.startIndex,
        initialMediaIndices = initialMediaIndices,
        resolutionRequiredPostIds = resolutionRequiredPostIds(posts, requiresResolution),
        series = series,
    )
}

private fun ViewerSession.resolutionRequiredPostIds(
    candidates: List<Post>,
    requiresResolution: (Post) -> Boolean,
): Set<PostId> = candidates
    .asSequence()
    .filter { post -> requiresResolution(post) || requiresViewerPostResolution(post, context.streamSource) }
    .mapTo(linkedSetOf(), Post::id)

private fun restoredViewerState(savedStateHandle: SavedStateHandle): ViewerUiState {
    val sessionId = savedStateHandle.get<String>(AppRouteSavedStateKeys.VIEWER_SESSION_ID)
        ?.takeIf(String::isNotBlank)
        ?: return ViewerUiState.Empty
    return ViewerUiState(
        session = ViewerSessionIdentity(
            value = sessionId,
            queryHash = savedStateHandle[ViewerSavedStateKeys.QUERY_HASH],
            streamKey = savedStateHandle[ViewerSavedStateKeys.STREAM_KEY],
        ),
    )
}
