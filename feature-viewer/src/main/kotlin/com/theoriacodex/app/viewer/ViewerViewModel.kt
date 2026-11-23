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
import com.theoriacodex.app.viewer.state.ViewerControlsState
import com.theoriacodex.app.viewer.state.reduceViewerState
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resolves a post without exposing a provider, repository, or Android handle to Viewer state. */
fun interface ViewerPostResolver {
    suspend fun resolve(session: ViewerSessionIdentity, postId: PostId): Post?
    fun requiresResolution(post: Post): Boolean = false
}

/** Performs bounded media prefetch outside immutable state and platform playback ownership. */
/** Warms one media item; [distance] is how many media items it sits from the one on screen. */
fun interface ViewerMediaPrefetcher {
    suspend fun prefetch(session: ViewerSessionIdentity, media: ViewerMediaState, distance: Int): ViewerPrefetchResult
}

/**
 * Route-scoped Viewer owner.
 *
 * Posts remain process-local. Saved state contains only the compact identity and current selection
 * needed to ask the shell/repository layer to reconstruct the session after process recreation.
 * ExoPlayer, animated decoders, launchers, and other Android handles remain in renderers/holders.
 */
class ViewerViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val postResolver: ViewerPostResolver,
    private val mediaPrefetcher: ViewerMediaPrefetcher,
    /** The app's playback setting, which every launched or restored session starts in. */
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
    /** Whether this session has shown its first media; speculative loads wait for it (see [awaitFirstMediaShown]). */
    private val firstMediaShown = MutableStateFlow(false)
    /** The page the last lookahead saw as current; a different one means the user arrived on a page. */
    private var lastCurrentPostId: PostId? = null

    private val mutableState = MutableStateFlow(restoredViewerState(savedStateHandle))
    override val state: StateFlow<ViewerUiState> = mutableState.asStateFlow()
    override val effects: Flow<ViewerEffect.Route> = effectChannel.receiveAsFlow()
    private val mutableSession = MutableStateFlow<ViewerSession?>(null)
    /**
     * The session progress reports belong to: the current one, or after [clearSession] the one that just closed, whose
     * retained pages still report as their players let go. The next [replaceSession] replaces it.
     */
    @Volatile private var reportingSession: ViewerSession? = null
    val session: StateFlow<ViewerSession?> = mutableSession.asStateFlow()

    /**
     * Each shown post's comments; the current and adjacent posts' first pages load once the media is up
     * (see [awaitFirstMediaShown]).
     */
    val comments = PostCommentsController(commentsLoader, workScope, beforeFirstFetch = ::awaitFirstMediaShown)

    /** Compact identity awaiting a process-restoration payload, or null for a live/empty session. */
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
                result = reduceViewerState(result.state, ViewerAction.SetPlaybackMode(startingPlaybackMode()))
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

    /**
     * While a series is watched only the episode on screen resolves: its lookup scrapes third-party sites for links that
     * soon expire, so a drag toward a neighbour (which asks for it as the swipe's target) must not start one.
     */
    private fun mayResolve(postId: PostId): Boolean =
        mutableSession.value?.series !is ViewerSeries.Watching || postId == mutableState.value.currentPage?.post?.id

    /** The app's playback setting, except while a series is watched: an episode goes on to the next one, never loops. */
    private fun startingPlaybackMode(): ViewerPlaybackMode =
        if (mutableSession.value?.series is ViewerSeries.Watching) ViewerPlaybackMode.NEXT else playbackModeProvider()

    /** The current post's first comment page, then its neighbours', so a swipe lands on a known count. */
    private fun showComments() {
        val current = state.value
        current.currentPage?.post?.let(comments::show)
        viewerPostNavigation(current).neighbours.forEach { page -> comments.show(page.post) }
    }

    /** A replacement cancels the old session's work; a new session also waits again for its first media. */
    private fun resetSessionWork(action: ViewerAction.ReplaceSession) {
        if (action.session != mutableState.value.session) {
            recordedVisiblePosts.clear()
            firstMediaShown.value = false
            lastCurrentPostId = null
            comments.reset()
        }
        cancelSessionJobs()
    }

    /** The current media became ready, or failed: speculative loads may start (see [awaitFirstMediaShown]). */
    private fun noteFirstMediaShown(state: ViewerUiState) {
        if (state.currentMedia?.ready == true || state.mediaError != null) firstMediaShown.value = true
    }

    /**
     * Bridges the existing process-local shell handoff into the route contract. A cold restoration arrives here too,
     * already starting on its saved post and media.
     */
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

    /**
     * Appends to [session] the [posts] its pages lacked when they were [basis]. It builds pages for the new posts
     * only and keeps the session's work, selection and playback, so a feed page costs what it adds. Ignored once
     * another session replaced [session]; posts the pages gained since [basis] are not repeated.
     */
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

    /** Enters the related shelf's recommendation branch from [seedId], keeping every page the session already has. */
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

    /** Replaces a page's post with its [resolved] form, keeping what only its listing knew. */
    fun applyResolvedPost(resolved: Post) {
        if (mutableSession.value == null) return
        val listed = state.value.pages.firstOrNull { page -> page.post.id == resolved.id }?.post ?: return
        val post = resolved.keepingListingDataOf(listed)
        synchronized(lock) {
            sessionJobs.keys.filterIsInstance<ViewerWorkKey.Prefetch>().filter { it.mediaKey.postId == post.id }
                .forEach { key -> sessionJobs.remove(key)?.cancel() }
            // The resolved post's media may differ, so what was warmed for the listed one no longer counts.
            prefetchLedger.forget(post.id)
        }
        state.value.session?.let { identity ->
            onAction(ViewerAction.ResolutionCompleted(identity, post))
        }
    }

    /** Retains one-shot visibility admission and its write across renderer/Activity reattachment. */
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
            // A partial best-effort write must not become a duplicate lifetime event on reattachment.
            runCatchingPreservingCancellation { record(post, viewedMediaNumber, current) }
        }
    }

    /** Media-position writes belong to the retained route, not an Activity composition. */
    fun recordVisibleMedia(
        post: Post,
        viewedMediaNumber: Int,
        record: suspend (Post, Int, ViewerSession) -> Unit,
    ) {
        val current = mutableSession.value ?: return
        if (state.value.currentPage?.post?.id != post.id) return
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingPreservingCancellation { record(post, viewedMediaNumber, current) }
        }
    }

    /**
     * An episode's playback position, written by the retained route like [recordVisibleMedia]. Unlike it, a post that
     * is no longer the current one still reports: its player's last report comes as it is swiped away.
     */
    fun recordPlaybackProgress(
        post: Post,
        progress: ViewerPlaybackProgress,
        record: suspend (Post, ViewerPlaybackProgress, ViewerSession) -> Unit,
    ) {
        val reporting = reportingSession ?: return
        if (reporting.posts.none { it.id == post.id } && state.value.pages.none { it.post.id == post.id }) return
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingPreservingCancellation { record(post, progress, reporting) }
        }
    }

    /** Exit may retain paused render pages until navigation disposes the outgoing destination. */
    fun clearSession(retainPresentation: Boolean = false) {
        synchronized(lock) {
            cancelSessionJobs()
            recordedVisiblePosts.clear()
            firstMediaShown.value = false
            lastCurrentPostId = null
            comments.reset()
            mutableSession.value = null
            // The retained pages keep their episode choice: a stream label dropped here would rebind the closing player.
            mutableState.value = if (retainPresentation) ViewerUiState(
                pages = mutableState.value.pages,
                currentPageIndex = mutableState.value.currentPageIndex,
                controls = ViewerControlsState(chromeVisible = false, episode = mutableState.value.controls.episode),
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

    /**
     * Current-page work wins; only the immediately adjacent posts (next first, then previous) resolve speculatively,
     * except while a series is watched: an episode's lookup scrapes third-party sites for links that soon expire, so
     * only the episode on screen resolves.
     */
    private fun reconcileResolutionLookahead() {
        val current = state.value
        val session = current.session ?: return
        val watching = mutableSession.value?.series is ViewerSeries.Watching
        val neighbours = if (watching) emptyList() else viewerPostNavigation(current).neighbours
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

    /**
     * Resolves the current page once, as soon as it becomes current. A recoverable failure retries only when the user
     * returns to the page, or through Retry; nothing retries it on its own.
     */
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
            onAction(ViewerAction.ResolutionStarted(effect.session, effect.postId))
            try {
                val outcome = withTimeoutOrNull(viewerResolutionTimeoutMs(mutableSession.value?.series)) { Result.success(postResolver.resolve(effect.session, effect.postId)) }
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
                    // A provider that ignores the requested id would otherwise leave the page requesting forever.
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
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
        // Decoding an image that is already downloaded reads the disk, so it never holds a download slot.
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
        val result = try {
            mediaPrefetcher.prefetch(session, media, request.distance).also { currentCoroutineContext().ensureActive() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
        }
        synchronized(lock) {
            if (state.value.session == session) prefetchLedger.record(request.key, result)
        }
    }

    /**
     * Speculative loads wait until the session's first media is on screen, so opening the Viewer never splits
     * the link between what was tapped (a gallery's first page, a video's first frame) and its neighbours. A
     * failed or slow first media releases them after [VIEWER_PREFETCH_READY_WAIT_MS]. Later pages do not wait:
     * someone skimming faster than pages load is better served by neighbours loading ahead.
     */
    private suspend fun awaitFirstMediaShown() {
        withTimeoutOrNull(VIEWER_PREFETCH_READY_WAIT_MS) { firstMediaShown.first { shown -> shown } }
    }

    /**
     * Warms the [requests] this session's media still needs, most wanted first, and cancels prefetch work no longer
     * requested. Runs on the main thread for every selection, so it looks up only the few requested keys.
     */
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

/** What this session's prefetch has finished, kept out of UI state so prefetch progress recomposes nothing. */
private class ViewerPrefetchLedger {
    private val downloaded = mutableSetOf<ViewerMediaKey>()
    private val decoded = mutableSetOf<ViewerMediaKey>()
    private val settled = mutableSetOf<ViewerMediaKey>()

    fun isDownloaded(key: ViewerMediaKey) = key in downloaded

    /** Skipped and failed media stay done; a downloaded image is done unless it now wants decoding and is not decoded. */
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

/**
 * How long a post lookup may take, the first one or one asked again to recover expired media. An episode is looked up
 * by its extension, which can take half a minute to find its streams; a chapter keeps a post's limit.
 */
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
    )
}

/** Which of [candidates] must resolve before the Viewer can show their media. */
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
