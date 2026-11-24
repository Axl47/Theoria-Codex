package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.comments.CommentsLoading
import com.theoriacodex.app.comments.UnsupportedCommentsLoader
import com.theoriacodex.app.comments.ViewerComments
import com.theoriacodex.app.related.accumulatedPosts
import com.theoriacodex.app.viewer.LocalViewerReturnTransition

import com.theoriacodex.data.repository.CodexUsageTarget
import com.theoriacodex.app.statistics.statisticsCodexId
import com.theoriacodex.app.statistics.statisticsPostKey
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.media.MediaDurationState
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.related.ViewerFeedSnapshot
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostDurations
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.ViewerContextActions
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.app.viewer.ViewerScreenCallbacks
import com.theoriacodex.app.viewer.ViewerScreenConfig
import com.theoriacodex.app.viewer.ViewerScreenHost
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerTrail
import com.theoriacodex.app.ui.trails.ViewerLevel
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.newViewerPosts
import com.theoriacodex.app.viewer.ViewerMediaRecoveryRequest
import com.theoriacodex.app.viewer.recoverMedia
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.related.requestOrNull
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dependencies whose lifetime is longer than one Viewer destination composition. */
internal data class ViewerRouteDependencies(
    val sessionRetentionOwner: ViewerSessionRetentionViewModel,
    val postResolver: ViewerPostResolver,
    val mediaPrefetcher: ViewerMediaPrefetcher,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val commentsLoader: CommentsLoading = UnsupportedCommentsLoader,
    val restoreSession: suspend (ViewerRestorationRequest) -> ViewerSession?,
    val canonicalizePost: (Post) -> Post = { it },
    val onCodexUsageTargetChanged: (CodexUsageTarget?) -> Unit = {},
)

/**
 * The live feeds a Viewer stream can extend from, keyed by the stream each one launches, plus the library state
 * their visibility filters read.
 *
 * Exact query identity prevents a source refresh or route replacement from appending unrelated
 * posts. Visibility policy is applied at the Viewer boundary so newly paged posts obey the same
 * filters as the launch payload.
 */
internal data class ViewerRouteLiveSourceState(
    val feeds: Map<ViewerStreamSource, ViewerFeedSnapshot> = emptyMap(),
    val likedPostIds: Set<PostId> = emptySet(),
    val savedPostIds: Set<PostId> = emptySet(),
    val watchedPostIds: Set<PostId> = emptySet(),
) {
    fun forSource(source: ViewerStreamSource): ViewerFeedSnapshot? = feeds[source]

    fun relatedSourceFor(session: ViewerSession): ViewerFeedSnapshot? {
        val source = session.relatedBinding?.source ?: session.context.streamSource
        val query = session.relatedBinding?.queryHash ?: session.context.queryHash
        return forSource(source)?.takeIf { it.queryHash == query }
    }

    /** The snapshot of the source [session] was launched from, while that source still shows the launch's query. */
    fun matching(session: ViewerSession?): ViewerFeedSnapshot? = session?.let { active ->
        forSource(active.context.streamSource)?.takeIf { snapshot -> snapshot.queryHash == active.context.queryHash }
    }

    fun visiblePostsFor(session: ViewerSession): List<Post>? {
        if (!session.liveSearchBinding) return null
        val source = session.context.streamSource
        val snapshot = matching(session) ?: return null
        val appliesSavedAndLikedPolicy = source != ViewerStreamSource.FOR_YOU
        return filterSearchResults(
            results = snapshot.results,
            filters = session.searchVisibilityFilters,
            likedPostIds = if (appliesSavedAndLikedPolicy) likedPostIds else emptySet(),
            retainedLikedPostIds = snapshot.retainedLikedPostIds,
            savedPostIds = if (appliesSavedAndLikedPolicy) savedPostIds else emptySet(),
            watchedPostIds = if (appliesSavedAndLikedPolicy) watchedPostIds else emptySet(),
        )
    }
}

internal data class ViewerDownloadRequest(
    val post: Post,
    val media: ImageRef,
    val pageIndex: Int,
    val totalPages: Int,
)

/** Side effects emitted by the immutable Viewer reducer and hosted by the destination. */
internal data class ViewerRouteEffectCallbacks(
    val onSavePost: (Post, ViewerLaunchContext) -> Unit,
    val onDownloadMedia: suspend (ViewerDownloadRequest?) -> Unit,
    val onToggleLike: suspend (Post) -> Unit,
    val onOpenCreatorProfile: suspend (CreatorProfile) -> Unit,
    val onApplyTag: (Post, PostTaxonomyTerm, Boolean) -> Unit,
    /** Recovers media for a post; the last argument says whether the result still applies before anything records it. */
    /** Working media for a post whose media failed, asked for in the session's launch context; null when there is none. */
    val onRecoverMedia: suspend (Post, ImageRef, ViewerLaunchContext, () -> Boolean) -> Post?,
    val onLoadMore: (ViewerStreamSource) -> Unit,
    val onDismiss: suspend () -> Unit,
    val onRestorationUnavailable: suspend () -> Unit,
)

/**
 * Navigation-scoped Viewer host.
 *
 * The activity owner only holds a pending process-local navigation payload. This destination
 * consumes that payload once, restores a compact saved identity when necessary, and then keeps
 * all Viewer mutations, work, and effects behind [ViewerViewModel].
 */
@Composable
internal fun ViewerRoute(
    dependencies: ViewerRouteDependencies,
    config: ViewerScreenConfig,
    liveSourceState: ViewerRouteLiveSourceState,
    effectCallbacks: ViewerRouteEffectCallbacks,
    screenCallbacks: ViewerScreenCallbacks,
    onOwnerChanged: (ViewerViewModel?) -> Unit = {},
    level: ViewerLevel = ViewerLevel.Root,
) {
    val viewerReturn = LocalViewerReturnTransition.current
    val latestLevel = rememberUpdatedState(level)
    DisposableEffect(viewerReturn) { onDispose { if (latestLevel.value.clearsReturnOnDispose()) viewerReturn?.clear() } }
    val latestDependencies = rememberUpdatedState(dependencies)
    val latestEffectCallbacks = rememberUpdatedState(effectCallbacks)
    val latestScreenCallbacks = rememberUpdatedState(screenCallbacks)
    val latestOnOwnerChanged = rememberUpdatedState(onOwnerChanged)
    val routeScope = rememberCoroutineScope()
    val mediaRetries = remember { Mutex() }
    val viewerOwner = rememberViewerOwner(latestDependencies, rememberUpdatedState(config))
    val durationOwner = viewModel<MediaDurationRouteViewModel>(
        key = "viewer-duration-owner",
        factory = MediaDurationRouteViewModel.factory(dependencies.mediaDurationCoordinator, "viewer"),
    )
    val viewerState by viewerOwner.state.collectAsStateWithLifecycle()
    val session by viewerOwner.session.collectAsStateWithLifecycle()
    val retainedPostIds = remember(viewerState.pages) { viewerState.pages.mapTo(mutableSetOf()) { it.post.id } }
    // A fork's posts are not in the grid underneath; the root's stay retained there.
    SideEffect { if (!level.isFork) viewerReturn?.retainPosts(retainedPostIds) }
    val restoration = rememberViewerSessionRestoration(viewerOwner, latestDependencies)
    DisposableEffect(viewerOwner) {
        latestOnOwnerChanged.value(viewerOwner)
        onDispose { latestOnOwnerChanged.value(null) }
    }
    LaunchedEffect(viewerOwner, restoration.claimedSessionId, restoration.attempt) { restoration.run() }
    ViewerLiveSourceMergeEffect(viewerOwner, session, liveSourceState)
    CollectRouteEffects(viewerOwner.effects) { effect ->
        handleViewerEffect(effect, viewerOwner, latestEffectCallbacks.value, latestDependencies, viewerReturn.takeUnless { latestLevel.value.isFork }, routeScope, mediaRetries)
    }
    ViewerUsageTargetEffect(session?.context, viewerState, dependencies.onCodexUsageTargetChanged)

    val activeSession = session
    if (activeSession == null && viewerState.pages.isEmpty()) {
        ViewerRestorationPlaceholder(
            failed = restoration.failed,
            inProgress = restoration.inProgress,
            canRetry = viewerOwner.pendingRestoration?.streamKey != ViewerStreamSource.RELATED.name,
            onRetry = restoration::retry,
            onBackToBrowsing = { routeScope.launch { latestEffectCallbacks.value.onRestorationUnavailable() } },
        )
        return
    }
    // A series' entry is not a post: its duration is the player's, never probed at its stream nor cached as a post's.
    val seriesSession = viewerState.series != null
    if (!seriesSession) ViewerDurationSync(activeSession, viewerState, durationOwner, dependencies.canonicalizePost)
    val sourceState = liveSourceState.matching(activeSession)
    val related = rememberViewerRelatedPosts(activeSession, liveSourceState)
    ViewerScreen(
        uiState = viewerState,
        host = rememberViewerScreenHost(viewerOwner, durationOwner.takeUnless { seriesSession }, screenCallbacks, latestDependencies, latestEffectCallbacks, routeScope),
        config = config,
        saveToCodexQuery = activeSession?.saveQuery,
        saveFromForYou = activeSession?.context?.streamSource == ViewerStreamSource.FOR_YOU,
        canLoadMoreFromSource = activeSession?.liveSearchBinding == true && sourceState?.canLoadMore == true,
        loadingMoreFromSource = sourceState?.loadingMore == true,
        contextActions = rememberViewerContextActions(related.source, activeSession?.context, latestScreenCallbacks),
        relatedShelf = rememberViewerRelatedShelf(related, viewerOwner, latestScreenCallbacks),
        comments = rememberViewerComments(viewerOwner, config.commentOrder, screenCallbacks.onCommentOrderSelected, screenCallbacks.onOpenUrl),
        trail = rememberViewerTrail(level, activeSession),
    )
}

/** The trail row's crumbs and query for [level], naming a root level after what [session] was opened from. */
@Composable
private fun rememberViewerTrail(level: ViewerLevel, session: ViewerSession?): ViewerTrail {
    LaunchedEffect(session?.sessionId) { session?.let { level.nameRoot(it.context, it.saveQuery) } }
    return remember(level, session?.saveQuery) { ViewerTrail(level.crumbs, session?.saveQuery, level.popTo) }
}

/** The navigation-scoped owner. Its factory reads the latest dependencies and settings, not the first ones. */
@Composable
private fun rememberViewerOwner(
    latestDependencies: State<ViewerRouteDependencies>,
    latestConfig: State<ViewerScreenConfig>,
): ViewerViewModel {
    val ownerFactory = remember {
        ViewerViewModel.factory(
            playbackModeProvider = { latestConfig.value.playbackMode },
            postResolver = object : ViewerPostResolver {
                override suspend fun resolve(session: com.theoriacodex.app.viewer.state.ViewerSessionIdentity, postId: PostId) =
                    latestDependencies.value.postResolver.resolve(session, postId)
                override fun requiresResolution(post: Post) = latestDependencies.value.postResolver.requiresResolution(post)
            },
            mediaPrefetcher = ViewerMediaPrefetcher { session, media, request ->
                latestDependencies.value.mediaPrefetcher.prefetch(session, media, request)
            },
            commentsLoader = latestDependencies.value.commentsLoader,
        )
    }
    return viewModel<ViewerViewModel>(factory = ownerFactory)
}

/** Keeps [durationOwner] working on the session's posts, nearest first. */
@Composable
private fun ViewerDurationSync(
    activeSession: ViewerSession?,
    viewerState: ViewerUiState,
    durationOwner: MediaDurationRouteViewModel,
    canonicalizePost: (Post) -> Post,
) {
    // Remembered so an unchanged session keeps one list, which is how the owner recognizes no new work.
    val durationPosts = remember(viewerState.pages) { viewerState.pages.map { page -> canonicalizePost(page.post) } }
    val nearbyPostIds = remember(viewerState) {
        val navigation = com.theoriacodex.app.viewer.viewerPostNavigation(viewerState)
        listOfNotNull(viewerState.currentPage).plus(navigation.neighbours)
            .mapTo(mutableSetOf()) { page -> canonicalizePost(page.post).id }
    }
    SideEffect {
        if (activeSession == null) return@SideEffect
        durationOwner.synchronize(
            identity = activeSession.sessionId,
            posts = durationPosts,
            resolveInBackground = false,
        )
        durationOwner.onNearbyPostsChanged(nearbyPostIds)
    }
    // The Viewer has no scrolling feed, so its duration work waits only on the lifecycle.
    com.theoriacodex.app.ui.components.DurationRouteEnvironmentEffect({ false }, durationOwner::onEnvironmentChanged)
}

/**
 * The screen's callbacks, bound to [viewerOwner]. One per owner and shell, so the screen's controls stay put while
 * Search, For You or settings change; the callbacks read the owner's current session when they run. A series session
 * passes no [durationOwner], so its entries get no post durations.
 */
@Composable
private fun rememberViewerScreenHost(
    viewerOwner: ViewerViewModel,
    durationOwner: MediaDurationRouteViewModel?,
    callbacks: ViewerScreenCallbacks,
    latestDependencies: State<ViewerRouteDependencies>,
    latestEffectCallbacks: State<ViewerRouteEffectCallbacks>,
    routeScope: CoroutineScope,
) = remember(viewerOwner, durationOwner, callbacks) {
    ViewerScreenHost(
        onAction = { action ->
            viewerOwner.onAction(action)
            if (action is ViewerAction.SetPlaybackMode) callbacks.onPlaybackModeSelected(action.mode)
        },
        onVideoQualitySelected = callbacks.onVideoQualitySelected,
        onGoToSearch = {
            viewerOwner.clearSession(retainPresentation = true)
            latestDependencies.value.sessionRetentionOwner.clear()
            callbacks.onGoToSearch()
        },
        onOpenCreatorFallback = callbacks.onOpenCreatorFallback,
        onGalleryReadingModeSelected = callbacks.onGalleryReadingModeSelected,
        onRequestMediaRecovery = object : ViewerMediaRecoveryRequest {
            override fun ask(post: Post, failedMedia: ImageRef) {
                routeScope.launch { recoverAndApplyMedia(viewerOwner, post, failedMedia, latestEffectCallbacks.value) }
            }

            override suspend fun recoverStreams(post: Post, failedMedia: ImageRef) =
                recoverAndApplyMedia(viewerOwner, post, failedMedia, latestEffectCallbacks.value)
        },
        onVisiblePostChanged = { post, viewedMediaNumber ->
            viewerOwner.state.value.session?.let { identity ->
                viewerOwner.recordVisiblePost(identity, post, viewedMediaNumber, callbacks.onVisiblePostChanged)
            }
        },
        onVisibleMediaChanged = { post, viewedMediaNumber ->
            viewerOwner.recordVisibleMedia(post, viewedMediaNumber, callbacks.onVisibleMediaChanged)
        },
        onPlaybackProgress = { post, progress ->
            viewerOwner.recordPlaybackProgress(post, progress, callbacks.onPlaybackProgress)
        },
        // The Viewer's window onto the duration owner, keyed by each post's canonical identity; none for a series.
        durations = durationOwner?.let { owner ->
            ViewerPostDurations(
                observe = { post ->
                    owner.observeState(latestDependencies.value.canonicalizePost(post))
                        .map { state -> (state as? MediaDurationState.Known)?.durationMs }
                },
                onPlayerDuration = { post, durationMs ->
                    owner.publishPlayerDuration(latestDependencies.value.canonicalizePost(post), durationMs)
                },
            )
        } ?: ViewerPostDurations.None,
    )
}

@Composable
private fun rememberViewerRelatedShelf(
    related: ViewerRelatedPosts,
    viewerOwner: ViewerViewModel,
    callbacks: State<ViewerScreenCallbacks>,
) = remember(related, viewerOwner, callbacks) {
    com.theoriacodex.app.viewer.ViewerRelatedShelf(
        state = related.state,
        posts = related.posts,
        onInclude = { related.state.requestOrNull?.seed?.id?.let { seedId -> viewerOwner.includeRelatedPosts(seedId, related.posts) } },
        onDismiss = { related.source?.let(callbacks.value.onDismissRelatedPosts) },
        onRetry = { related.source?.let(callbacks.value.onRetryRelatedPosts) },
    )
}

@Composable
private fun rememberViewerContextActions(
    relatedSource: ViewerStreamSource?,
    launch: ViewerLaunchContext?,
    callbacks: State<ViewerScreenCallbacks>,
) = remember(relatedSource, launch, callbacks) {
    ViewerContextActions(
        showRelated = relatedSource?.takeIf { it in ViewerRelatedShelves.SOURCES }?.let { source ->
            { post -> callbacks.value.onShowRelatedPosts(source, post) }
        },
        removeFromCodex = launch?.removableCodexId()?.let { id -> { post -> callbacks.value.onRemoveFromCodex(id, post) } },
    )
}

/** The owner's comments as the screen reads them, recomposing as pages arrive. */
@Composable
private fun rememberViewerComments(
    viewerOwner: ViewerViewModel,
    order: com.theoriacodex.domain.model.CommentOrder,
    onOrderSelected: (com.theoriacodex.domain.model.CommentOrder) -> Unit,
    onOpenUrl: (String) -> Unit,
): ViewerComments {
    val states by viewerOwner.comments.state.collectAsStateWithLifecycle()
    val latestOnOrderSelected by rememberUpdatedState(onOrderSelected)
    val latestOnOpenUrl by rememberUpdatedState(onOpenUrl)
    return remember(states, viewerOwner, order) {
        ViewerComments(states, viewerOwner.comments, order, { latestOnOrderSelected(it) }) { latestOnOpenUrl(it) }
    }
}

/** Claims the pending session once per owner, or rebuilds one from its compact saved identity. */
@Stable
private class ViewerSessionRestoration(
    private val owner: ViewerViewModel,
    val claimedSessionId: String?,
    private val dependencies: State<ViewerRouteDependencies>,
) {
    var attempt by mutableIntStateOf(0)
        private set
    var failed by mutableStateOf(false)
        private set
    var inProgress by mutableStateOf(false)
        private set

    fun retry() {
        attempt++
    }

    suspend fun run() {
        failed = false
        val current = dependencies.value
        val handedOff = current.sessionRetentionOwner.handoffTo(owner = owner, claimedSessionId = claimedSessionId)
        if (handedOff || owner.session.value != null) return
        val restored = restore(current)
        if (restored == null) failed = true else owner.replaceSession(restored)
    }

    private suspend fun restore(current: ViewerRouteDependencies): ViewerSession? {
        val pending = owner.restorationRequest
        return try {
            inProgress = pending != null
            pending?.let { identity -> current.restoreSession(identity) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            inProgress = false
        }
    }
}

@Composable
private fun rememberViewerSessionRestoration(
    owner: ViewerViewModel,
    dependencies: State<ViewerRouteDependencies>,
): ViewerSessionRestoration = remember(owner) {
    ViewerSessionRestoration(owner, dependencies.value.sessionRetentionOwner.session.value?.sessionId, dependencies)
}

/** Extends a live session with what its source has since loaded, under the launch's filters. */
@Composable
private fun ViewerLiveSourceMergeEffect(
    viewerOwner: ViewerViewModel,
    session: ViewerSession?,
    liveSourceState: ViewerRouteLiveSourceState,
) {
    val activeLiveSource = session?.let { active -> liveSourceState.forSource(active.context.streamSource) }
    LaunchedEffect(
        viewerOwner,
        session?.sessionId,
        session?.searchVisibilityFilters,
        activeLiveSource?.queryHash,
        activeLiveSource?.results,
        activeLiveSource?.retainedLikedPostIds,
        liveSourceState.likedPostIds,
        liveSourceState.savedPostIds,
        liveSourceState.watchedPostIds,
    ) {
        appendLiveSourcePosts(viewerOwner, liveSourceState)
    }
}

/**
 * Appends to the live session what its source has loaded since, under the launch's filters. Filtering the source's
 * feed and finding the posts the session lacks walk both whole lists, so they run on [background]; the caller's
 * thread only appends the result, building pages for the new posts alone.
 */
internal suspend fun appendLiveSourcePosts(
    viewerOwner: ViewerViewModel,
    liveSourceState: ViewerRouteLiveSourceState,
    background: CoroutineContext = Dispatchers.Default,
) {
    val session = viewerOwner.session.value ?: return
    val basis = viewerOwner.state.value.pages
    val appended = withContext(background) {
        liveSourceState.visiblePostsFor(session)?.let { incoming -> newViewerPosts(basis.map { it.post }, incoming) }
    }
    if (appended.isNullOrEmpty()) return
    viewerOwner.appendSessionPosts(session, basis, appended)
}

/**
 * Handles one effect against the state at its delivery, not when it was emitted. Effects run in
 * order because the collector awaits each one; media recovery waits on a provider, so it runs in
 * [routeScope] instead, where it cannot hold a later Dismiss behind it. [mediaRetries] keeps manual retries in
 * order, so an older retry cannot finish after a newer one and replace its post.
 */
private suspend fun handleViewerEffect(
    effect: ViewerEffect.Route,
    viewerOwner: ViewerViewModel,
    callbacks: ViewerRouteEffectCallbacks,
    dependencies: State<ViewerRouteDependencies>,
    viewerReturn: ViewerReturnTransition?,
    routeScope: CoroutineScope,
    mediaRetries: Mutex,
) {
    val currentState = viewerOwner.state.value
    if (!effect.isCurrentFor(currentState)) return
    // A series' chapters and episodes are not posts: whichever control asked, none is saved, liked, tagged or downloaded.
    if (effect.collectsPost && viewerOwner.session.value?.series != null) return
    fun post(postId: PostId): Post? = currentState.pages.firstOrNull { page -> page.post.id == postId }?.post
    when (effect) {
        is ViewerEffect.SavePost -> post(effect.postId)?.let { selected ->
            viewerOwner.session.value?.context?.let { context -> callbacks.onSavePost(selected, context) }
        }
        is ViewerEffect.DownloadMedia -> callbacks.onDownloadMedia(viewerDownloadRequest(effect, currentState))
        is ViewerEffect.SetLiked -> post(effect.postId)?.let { selected ->
            val launchAction = viewerOwner.session.value?.onToggleLike
            if (launchAction != null) launchAction(selected) else callbacks.onToggleLike(selected)
        }
        is ViewerEffect.OpenCreatorProfile -> callbacks.onOpenCreatorProfile(effect.creator)
        is ViewerEffect.ApplyTag -> post(effect.postId)?.let { selected ->
            callbacks.onApplyTag(selected, effect.term, effect.excluded)
        }
        is ViewerEffect.RetryMedia -> routeScope.launch {
            mediaRetries.withLock {
                val state = viewerOwner.state.value
                if (effect.isCurrentFor(state)) retryViewerMedia(effect, state, viewerOwner, callbacks)
            }
        }
        is ViewerEffect.LoadMore -> viewerOwner.session.value?.context?.streamSource?.let(callbacks.onLoadMore)
        is ViewerEffect.Dismiss -> {
            viewerOwner.clearSession(retainPresentation = true)
            dependencies.value.sessionRetentionOwner.clear()
            callbacks.onDismiss()
            currentState.currentPage?.post?.id?.let { viewerReturn?.begin(it) }
        }
    }
}

/** Whether this effect keeps the post somewhere of the user's: a Codex, likes, tags or the device. */
private val ViewerEffect.Route.collectsPost: Boolean
    get() = this is ViewerEffect.SavePost || this is ViewerEffect.SetLiked || this is ViewerEffect.ApplyTag ||
        this is ViewerEffect.DownloadMedia

private fun viewerDownloadRequest(effect: ViewerEffect.DownloadMedia, state: ViewerUiState): ViewerDownloadRequest? {
    val page = state.pages.firstOrNull { candidate -> candidate.post.id == effect.postId } ?: return null
    val media = page.media.firstOrNull { candidate -> candidate.key == effect.mediaKey }?.ref ?: return null
    return ViewerDownloadRequest(
        post = page.post,
        media = media,
        pageIndex = effect.mediaKey.mediaIndex,
        totalPages = page.media.size,
    )
}

private suspend fun retryViewerMedia(
    effect: ViewerEffect.RetryMedia,
    state: ViewerUiState,
    viewerOwner: ViewerViewModel,
    callbacks: ViewerRouteEffectCallbacks,
) {
    val page = state.pages.firstOrNull { candidate -> candidate.post.id == effect.mediaKey.postId } ?: return
    val failedMedia = page.media.firstOrNull { candidate -> candidate.key == effect.mediaKey }?.ref ?: return
    recoverAndApplyMedia(viewerOwner, page.post, failedMedia, callbacks)
}

/** Reports which Codex and media this session is spending time on, for usage statistics. */
@Composable
private fun ViewerUsageTargetEffect(
    context: ViewerLaunchContext?,
    state: ViewerUiState,
    onChanged: (CodexUsageTarget?) -> Unit,
) {
    val usageCodexId = statisticsCodexId(context)
    val usagePost = state.currentPage?.post
    val usageMedia = state.currentPage?.selectedMedia?.ref
    val usageTarget = usageCodexId?.let { id -> usagePost?.let { post ->
        CodexUsageTarget(
            id, statisticsPostKey(post.id), usageMedia?.let { com.theoriacodex.app.media.isVideoMediaRef(it) } == true,
        )
    } }
    LaunchedEffect(usageTarget) { onChanged(usageTarget) }
}

@Composable
private fun ViewerRestorationPlaceholder(
    failed: Boolean,
    inProgress: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onBackToBrowsing: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (failed) {
                Text("This viewing session could not be restored.")
                if (canRetry) TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onBackToBrowsing) { Text("Back to browsing") }
            } else if (inProgress) {
                CircularProgressIndicator()
                Text("Restoring your place…")
            }
        }
    }
}

/** The related drawer for this session: which source feeds it, its state, and its visible posts. */
private class ViewerRelatedPosts(
    val source: ViewerStreamSource?,
    val state: RelatedPostsUiState,
    val posts: List<Post>,
)

@Composable
private fun rememberViewerRelatedPosts(
    activeSession: ViewerSession?,
    liveSourceState: ViewerRouteLiveSourceState,
): ViewerRelatedPosts {
    val relatedSource = activeSession?.relatedBinding?.source ?: activeSession?.context?.streamSource
    val relatedSnapshot = activeSession?.let(liveSourceState::relatedSourceFor)
    val relatedState = relatedSnapshot?.relatedPosts ?: RelatedPostsUiState.Idle
    // New likes extend the drawer instead of replacing it, for as long as this Viewer session lasts.
    var shownRelatedPosts by remember(activeSession?.sessionId) { mutableStateOf(emptyList<Post>()) }
    val accumulatedRelatedPosts = remember(shownRelatedPosts, relatedState) { relatedState.accumulatedPosts(shownRelatedPosts) }
    SideEffect { shownRelatedPosts = accumulatedRelatedPosts }
    val relatedPosts = remember(
        activeSession?.searchVisibilityFilters, accumulatedRelatedPosts, relatedSource, liveSourceState.likedPostIds,
        liveSourceState.savedPostIds, liveSourceState.watchedPostIds, relatedSnapshot?.retainedLikedPostIds,
    ) {
        activeSession?.let { active ->
            filterSearchResults(
                results = accumulatedRelatedPosts, filters = active.searchVisibilityFilters,
                likedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.likedPostIds,
                savedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.savedPostIds,
                watchedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.watchedPostIds,
                retainedLikedPostIds = relatedSnapshot?.retainedLikedPostIds.orEmpty(),
            )
        }.orEmpty()
    }
    return remember(relatedSource, relatedState, relatedPosts) { ViewerRelatedPosts(relatedSource, relatedState, relatedPosts) }
}

/** Prevents buffered or suspended effects from crossing a Viewer session replacement. */
internal fun ViewerEffect.isCurrentFor(state: ViewerUiState): Boolean = session == state.session

/** Asks the source for working media for [post], in the session's launch context; see [recoverMedia]. */
private suspend fun recoverAndApplyMedia(
    viewerOwner: ViewerViewModel,
    post: Post,
    failedMedia: ImageRef,
    callbacks: ViewerRouteEffectCallbacks,
) = viewerOwner.recoverMedia(post, failedMedia) { session, stillApplies ->
    callbacks.onRecoverMedia(post, failedMedia, session.context, stillApplies)
}
