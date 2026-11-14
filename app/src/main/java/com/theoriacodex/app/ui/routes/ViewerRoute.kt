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
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.related.RelatedPostsUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerRestorationRequest
import com.theoriacodex.app.viewer.ViewerReturnTransition
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.mergeViewerPosts
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.withRelatedPosts
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
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

/** Immutable values consumed by the Viewer renderer. */
internal data class ViewerRouteRenderConfig(
    val pixivUgoiraClient: PixivUgoiraClient? = null,
    val tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    val fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ ->
        emptyMap()
    },
    val playbackMode: com.theoriacodex.domain.model.ViewerPlaybackMode = com.theoriacodex.domain.model.ViewerPlaybackMode.LOOP,
    val videoQuality: com.theoriacodex.domain.model.VideoQuality = com.theoriacodex.domain.model.VideoQuality.AUTO,
    val invertMultiImageScrollDirection: Boolean = false,
    val instantSeek: Boolean = false,
    val autoRotateVideos: Boolean = false,
    val commentOrder: com.theoriacodex.domain.model.CommentOrder = com.theoriacodex.domain.model.CommentOrder.OLDEST,
    val likedPostIds: Set<PostId> = emptySet(),
    val codexPostIds: Set<PostId> = emptySet(),
    val creatorBrowsingSources: Set<SourceKey>,
)

/** Immutable source-owner snapshot consumed by a live Viewer session. */
internal data class ViewerRouteLiveSourceSnapshot(
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val retainedLikedPostIds: Set<PostId> = emptySet(),
    val relatedPosts: RelatedPostsUiState = RelatedPostsUiState.Idle,
)

/**
 * Route-owned Search, For You, and Creator state needed to extend a live Viewer stream.
 *
 * Exact query identity prevents a source refresh or route replacement from appending unrelated
 * posts. Visibility policy is applied at the Viewer boundary so newly paged posts obey the same
 * filters as the launch payload.
 */
internal data class ViewerRouteLiveSourceState(
    val search: ViewerRouteLiveSourceSnapshot = ViewerRouteLiveSourceSnapshot(),
    val forYou: ViewerRouteLiveSourceSnapshot = ViewerRouteLiveSourceSnapshot(),
    val creatorProfile: ViewerRouteLiveSourceSnapshot = ViewerRouteLiveSourceSnapshot(),
    val likedPostIds: Set<PostId> = emptySet(),
    val savedPostIds: Set<PostId> = emptySet(),
    val watchedPostIds: Set<PostId> = emptySet(),
    val unknownAnimatedDurationPolicy: UnknownAnimatedDurationPolicy =
        UnknownAnimatedDurationPolicy.HIDE_UNKNOWNS,
) {
    fun forSource(source: ViewerStreamSource): ViewerRouteLiveSourceSnapshot? = when (source) {
        ViewerStreamSource.SEARCH -> search
        ViewerStreamSource.FOR_YOU -> forYou
        ViewerStreamSource.CREATOR_PROFILE -> creatorProfile
        ViewerStreamSource.RELATED,
        ViewerStreamSource.CODEX,
        ViewerStreamSource.RECENTS,
        -> null
    }

    fun relatedSourceFor(session: ViewerSession): ViewerRouteLiveSourceSnapshot? {
        val source = session.relatedBinding?.source ?: session.context.streamSource
        val query = session.relatedBinding?.queryHash ?: session.context.queryHash
        return forSource(source)?.takeIf { it.queryHash == query }
    }

    fun visiblePostsFor(session: ViewerSession): List<Post>? {
        if (!session.liveSearchBinding) return null
        val source = session.context.streamSource
        val snapshot = forSource(source) ?: return null
        if (snapshot.queryHash != session.context.queryHash) return null
        val appliesSavedAndLikedPolicy = source != ViewerStreamSource.FOR_YOU
        return filterSearchResults(
            results = snapshot.results,
            filters = session.searchVisibilityFilters,
            likedPostIds = if (appliesSavedAndLikedPolicy) likedPostIds else emptySet(),
            retainedLikedPostIds = snapshot.retainedLikedPostIds,
            savedPostIds = if (appliesSavedAndLikedPolicy) savedPostIds else emptySet(),
            watchedPostIds = if (appliesSavedAndLikedPolicy) watchedPostIds else emptySet(),
            unknownAnimatedDurationPolicy = unknownAnimatedDurationPolicy,
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
    val onSharePost: (Post?) -> Unit,
    val onDownloadMedia: suspend (ViewerDownloadRequest?) -> Unit,
    val onToggleLike: suspend (Post) -> Unit,
    val onOpenCreatorProfile: suspend (CreatorProfile) -> Unit,
    val onApplyTag: (Post, PostTaxonomyTerm, Boolean) -> Unit,
    val onRecoverMedia: suspend (Post, ImageRef, ViewerStreamSource) -> Post?,
    val onLoadMore: (ViewerStreamSource) -> Unit,
    val onDismiss: suspend () -> Unit,
    val onRestorationUnavailable: suspend () -> Unit,
)

/** Renderer callbacks that cross into navigation, persistence, or Android platform behavior. */
internal data class ViewerRouteScreenCallbacks(
    val onOwnerChanged: (ViewerRouteOwnerHandle?) -> Unit = {},
    val onPlaybackModeSelected: (com.theoriacodex.domain.model.ViewerPlaybackMode) -> Unit = {},
    val onCommentOrderSelected: (com.theoriacodex.domain.model.CommentOrder) -> Unit = {},
    val onVisiblePostChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onVisibleMediaChanged: suspend (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onOpenInBrowser: (Post) -> Unit,
    val onOpenOriginalPost: (Post) -> Unit = {},
    /** The "Find on other sites" action for a post, or null to hide it. */
    val findOnOtherSites: (Post) -> (() -> Unit)? = { null },
    val onOpenUrl: (String) -> Unit = {},
    val onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    val onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    val onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    val onGoToSearch: () -> Unit,
    val onDismissRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onRetryRelatedPosts: (ViewerStreamSource) -> Unit = {},
    val onOpenCreatorFallback: ((Post) -> Unit)? = null,
)

/**
 * Non-owning access to the small amount of Viewer state the navigation shell must coordinate.
 *
 * The handle deliberately does not expose actions, effects, renderer state, or platform jobs. It
 * also rejects work after the navigation-scoped owner is cleared instead of relying on eventual
 * garbage collection of a weak reference.
 */
internal class ViewerRouteOwnerHandle(
    owner: ViewerViewModel,
) {
    private val ownerLease = owner.createRouteOwnerLease()

    val session: StateFlow<ViewerSession?>?
        get() = ownerLease.withOwner { activeOwner -> activeOwner.session }

    fun clearSession(): Boolean {
        return ownerLease.withOwner { activeOwner ->
            activeOwner.clearSession(retainPresentation = true)
            true
        } ?: false
    }
}

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
    renderConfig: ViewerRouteRenderConfig,
    liveSourceState: ViewerRouteLiveSourceState,
    effectCallbacks: ViewerRouteEffectCallbacks,
    screenCallbacks: ViewerRouteScreenCallbacks,
) {
    val viewerReturn = LocalViewerReturnTransition.current
    DisposableEffect(viewerReturn) {
        onDispose { viewerReturn?.clear() }
    }
    val latestDependencies = rememberUpdatedState(dependencies)
    val latestRenderConfig = rememberUpdatedState(renderConfig)
    val latestEffectCallbacks = rememberUpdatedState(effectCallbacks)
    val latestScreenCallbacks = rememberUpdatedState(screenCallbacks)
    val routeScope = rememberCoroutineScope()
    val ownerFactory = remember {
        ViewerViewModel.factory(
            playbackModeProvider = { latestRenderConfig.value.playbackMode },
            postResolver = object : ViewerPostResolver {
                override suspend fun resolve(session: com.theoriacodex.app.viewer.state.ViewerSessionIdentity, postId: PostId) =
                    latestDependencies.value.postResolver.resolve(session, postId)
                override fun requiresResolution(post: Post) = latestDependencies.value.postResolver.requiresResolution(post)
            },
            mediaPrefetcher = ViewerMediaPrefetcher { session, media, distance ->
                latestDependencies.value.mediaPrefetcher.prefetch(session, media, distance)
            },
            commentsLoader = dependencies.commentsLoader,
        )
    }
    val viewerOwner = viewModel<ViewerViewModel>(factory = ownerFactory)
    val durationOwner = viewModel<MediaDurationRouteViewModel>(
        key = "viewer-duration-owner",
        factory = MediaDurationRouteViewModel.factory(dependencies.mediaDurationCoordinator, "viewer"),
    )
    val ownerHandle = remember(viewerOwner) { ViewerRouteOwnerHandle(viewerOwner) }
    val viewerState by viewerOwner.state.collectAsStateWithLifecycle()
    val session by viewerOwner.session.collectAsStateWithLifecycle()
    val retainedPostIds = remember(viewerState.pages) { viewerState.pages.mapTo(mutableSetOf()) { it.post.id } }
    SideEffect { viewerReturn?.retainPosts(retainedPostIds) }
    val restoration = rememberViewerSessionRestoration(viewerOwner, latestDependencies)
    DisposableEffect(viewerOwner) {
        latestScreenCallbacks.value.onOwnerChanged(ownerHandle)
        onDispose {
            latestScreenCallbacks.value.onOwnerChanged(null)
        }
    }
    LaunchedEffect(viewerOwner, restoration.claimedSessionId, restoration.attempt) { restoration.run() }
    ViewerLiveSourceMergeEffect(viewerOwner, session, liveSourceState)
    CollectRouteEffects(viewerOwner.effects) { effect ->
        handleViewerEffect(effect, viewerOwner, latestEffectCallbacks.value, latestDependencies, viewerReturn)
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
    ViewerRouteScreen(
        activeSession, viewerState, viewerOwner, durationOwner, liveSourceState, renderConfig, dependencies,
        screenCallbacks, latestDependencies, latestEffectCallbacks, latestScreenCallbacks, routeScope,
    )
}

/** Renders the claimed or restored session, once the route has one to show. */
@Composable
private fun ViewerRouteScreen(
    activeSession: ViewerSession?,
    viewerState: ViewerUiState,
    viewerOwner: ViewerViewModel,
    durationOwner: MediaDurationRouteViewModel,
    liveSourceState: ViewerRouteLiveSourceState,
    renderConfig: ViewerRouteRenderConfig,
    dependencies: ViewerRouteDependencies,
    screenCallbacks: ViewerRouteScreenCallbacks,
    latestDependencies: State<ViewerRouteDependencies>,
    latestEffectCallbacks: State<ViewerRouteEffectCallbacks>,
    latestScreenCallbacks: State<ViewerRouteScreenCallbacks>,
    routeScope: CoroutineScope,
) {
    val matchingSourceState = activeSession?.let { active ->
        liveSourceState.forSource(active.context.streamSource)
            ?.takeIf { sourceState -> sourceState.queryHash == active.context.queryHash }
    }
    val canLoadMoreFromSource = activeSession?.liveSearchBinding == true &&
        matchingSourceState?.canLoadMore == true
    // Remembered so an unchanged session keeps one list, which is how the owner recognizes no new work.
    val durationPosts = remember(viewerState.pages) { viewerState.pages.map { page -> dependencies.canonicalizePost(page.post) } }
    SideEffect {
        if (activeSession == null) return@SideEffect
        durationOwner.synchronize(
            identity = activeSession.sessionId,
            posts = durationPosts,
            resolveInBackground = false,
        )
    }
    val related = rememberViewerRelatedPosts(activeSession, liveSourceState)
    androidx.compose.runtime.CompositionLocalProvider(
        com.theoriacodex.app.viewer.LocalViewerInstantSeek provides renderConfig.instantSeek,
        com.theoriacodex.app.viewer.LocalViewerAutoRotateVideos provides renderConfig.autoRotateVideos,
    ) {
        ViewerRouteRenderer(
            activeSession, viewerState, viewerOwner, durationOwner, renderConfig, related, screenCallbacks,
            canLoadMoreFromSource, matchingSourceState?.loadingMore == true,
            latestDependencies, latestEffectCallbacks, latestScreenCallbacks, routeScope,
        )
    }
}

@Composable
private fun ViewerRouteRenderer(
    activeSession: ViewerSession?,
    viewerState: ViewerUiState,
    viewerOwner: ViewerViewModel,
    durationOwner: MediaDurationRouteViewModel,
    renderConfig: ViewerRouteRenderConfig,
    related: ViewerRelatedPosts,
    screenCallbacks: ViewerRouteScreenCallbacks,
    canLoadMoreFromSource: Boolean,
    loadingMoreFromSource: Boolean,
    latestDependencies: State<ViewerRouteDependencies>,
    latestEffectCallbacks: State<ViewerRouteEffectCallbacks>,
    latestScreenCallbacks: State<ViewerRouteScreenCallbacks>,
    routeScope: CoroutineScope,
) {
    ViewerScreen(
        comments = rememberViewerComments(viewerOwner, renderConfig.commentOrder, screenCallbacks.onCommentOrderSelected, screenCallbacks.onOpenUrl),
        uiState = viewerState.copy(related = com.theoriacodex.app.viewer.state.ViewerRelatedUiState(
            related.state, related.posts, activeSession?.relatedNavigation, activeSession?.relatedPostIds.orEmpty(),
        )),
        saveToCodexQuery = activeSession?.saveQuery,
        creatorBrowsingSources = renderConfig.creatorBrowsingSources,
        onAction = { action -> viewerOwner.dispatch(action, screenCallbacks) },
        pixivUgoiraClient = renderConfig.pixivUgoiraClient,
        tagVideoCountProvider = renderConfig.tagVideoCountProvider,
        fetchTagVideoCounts = renderConfig.fetchTagVideoCounts,
        canLoadMoreFromSource = canLoadMoreFromSource,
        loadingMoreFromSource = loadingMoreFromSource,
        invertMultiImageScrollDirection = renderConfig.invertMultiImageScrollDirection,
        videoQuality = renderConfig.videoQuality,
        likedPostIds = renderConfig.likedPostIds,
        codexPostIds = renderConfig.codexPostIds,
        onIncludeRelatedPosts = {
            related.state.requestOrNull?.seed?.id?.let { seedId ->
                viewerOwner.updateSession { current -> current.withRelatedPosts(seedId, related.posts) }
            }
        },
        onDismissRelatedPosts = { related.source?.let(screenCallbacks.onDismissRelatedPosts) },
        onRetryRelatedPosts = { related.source?.let(screenCallbacks.onRetryRelatedPosts) },
        onRequestMediaRecovery = { post, failedMedia ->
            routeScope.launch {
                if (viewerOwner.session.value == null) return@launch
                recoverAndApplyMedia(viewerOwner, post, failedMedia, latestEffectCallbacks.value)
            }
        },
        onVisiblePostChanged = { post, viewedMediaNumber ->
            viewerState.session?.let { identity ->
                viewerOwner.recordVisiblePost(
                    identity, post, viewedMediaNumber,
                    latestScreenCallbacks.value.onVisiblePostChanged,
                )
            }
        },
        onVisibleMediaChanged = { post, viewedMediaNumber ->
            viewerOwner.recordVisibleMedia(post, viewedMediaNumber, latestScreenCallbacks.value.onVisibleMediaChanged)
        },
        onAuthoritativeDurationKnown = { post, durationMs ->
            durationOwner.publishPlayerDuration(latestDependencies.value.canonicalizePost(post), durationMs)
        },
        onOpenInBrowser = screenCallbacks.onOpenInBrowser,
        onOpenOriginalPost = screenCallbacks.onOpenOriginalPost,
        findOnOtherSites = screenCallbacks.findOnOtherSites,
        onRemoveIncludeTerm = screenCallbacks.onRemoveIncludeTerm,
        onRemoveExcludeTerm = screenCallbacks.onRemoveExcludeTerm,
        onFavoriteTagLongPress = screenCallbacks.onFavoriteTagLongPress,
        onGoToSearch = {
            viewerOwner.clearSession(retainPresentation = true)
            latestDependencies.value.sessionRetentionOwner.clear()
            latestScreenCallbacks.value.onGoToSearch()
        },
        onOpenCreatorFallback = screenCallbacks.onOpenCreatorFallback,
    )
}

/** Applies a Viewer action, telling the route when the user picked a playback mode. */
private fun ViewerViewModel.dispatch(action: ViewerAction, screenCallbacks: ViewerRouteScreenCallbacks) {
    onAction(action)
    if (action is ViewerAction.SetPlaybackMode) screenCallbacks.onPlaybackModeSelected(action.mode)
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
        liveSourceState.unknownAnimatedDurationPolicy,
    ) {
        val currentSession = viewerOwner.session.value ?: return@LaunchedEffect
        val incoming = liveSourceState.visiblePostsFor(currentSession) ?: return@LaunchedEffect
        val merged = mergeViewerPosts(currentSession.posts, incoming)
        if (merged.size != currentSession.posts.size) {
            viewerOwner.updateSession { latest ->
                if (latest.sessionId == currentSession.sessionId) {
                    latest.copy(posts = mergeViewerPosts(latest.posts, incoming))
                } else {
                    latest
                }
            }
        }
    }
}

/**
 * Handles one effect against the state at its delivery, not when it was emitted. Effects run in
 * order because the collector awaits each one, so nothing here may launch work of its own.
 */
private suspend fun handleViewerEffect(
    effect: ViewerEffect,
    viewerOwner: ViewerViewModel,
    callbacks: ViewerRouteEffectCallbacks,
    dependencies: State<ViewerRouteDependencies>,
    viewerReturn: ViewerReturnTransition?,
) {
    val currentState = viewerOwner.state.value
    if (!effect.isCurrentFor(currentState)) return
    fun post(postId: PostId): Post? = currentState.pages.firstOrNull { page -> page.post.id == postId }?.post
    when (effect) {
        is ViewerEffect.SavePost -> post(effect.postId)?.let { selected ->
            viewerOwner.session.value?.context?.let { context -> callbacks.onSavePost(selected, context) }
        }
        is ViewerEffect.ShareMedia -> callbacks.onSharePost(post(effect.postId))
        is ViewerEffect.DownloadMedia -> callbacks.onDownloadMedia(viewerDownloadRequest(effect, currentState))
        is ViewerEffect.SetLiked -> post(effect.postId)?.let { selected ->
            val launchAction = viewerOwner.session.value?.onToggleLike
            if (launchAction != null) launchAction(selected) else callbacks.onToggleLike(selected)
        }
        is ViewerEffect.OpenCreatorProfile -> callbacks.onOpenCreatorProfile(effect.creator)
        is ViewerEffect.ApplyTag -> post(effect.postId)?.let { selected ->
            callbacks.onApplyTag(selected, effect.term, effect.excluded)
        }
        is ViewerEffect.RetryMedia -> retryViewerMedia(effect, currentState, viewerOwner, callbacks)
        is ViewerEffect.LoadMore -> viewerOwner.session.value?.context?.streamSource?.let(callbacks.onLoadMore)
        is ViewerEffect.Dismiss -> {
            viewerOwner.clearSession(retainPresentation = true)
            dependencies.value.sessionRetentionOwner.clear()
            callbacks.onDismiss()
            currentState.currentPage?.post?.id?.let { viewerReturn?.begin(it) }
        }
        is ViewerEffect.ResolvePost,
        is ViewerEffect.PrefetchMedia,
        -> Unit
    }
}

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
        liveSourceState.unknownAnimatedDurationPolicy,
    ) {
        activeSession?.let { active ->
            filterSearchResults(
                results = accumulatedRelatedPosts, filters = active.searchVisibilityFilters,
                likedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.likedPostIds,
                savedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.savedPostIds,
                watchedPostIds = if (relatedSource == ViewerStreamSource.FOR_YOU) emptySet() else liveSourceState.watchedPostIds,
                retainedLikedPostIds = relatedSnapshot?.retainedLikedPostIds.orEmpty(),
                unknownAnimatedDurationPolicy = liveSourceState.unknownAnimatedDurationPolicy,
            )
        }.orEmpty()
    }
    return remember(relatedSource, relatedState, relatedPosts) { ViewerRelatedPosts(relatedSource, relatedState, relatedPosts) }
}

/** Prevents buffered or suspended effects from crossing a Viewer session replacement. */
internal fun ViewerEffect.isCurrentFor(state: ViewerUiState): Boolean = session == state.session

private suspend fun recoverAndApplyMedia(
    viewerOwner: ViewerViewModel,
    post: Post,
    failedMedia: ImageRef,
    callbacks: ViewerRouteEffectCallbacks,
) {
    val session = viewerOwner.session.value ?: return
    if (session.posts.none { current -> current.id == post.id }) return
    val recovered = try {
        callbacks.onRecoverMedia(post, failedMedia, session.context.streamSource)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return
    val currentSession = viewerOwner.session.value
    if (currentSession?.sessionId != session.sessionId) return
    if (currentSession.posts.none { current -> current.id == post.id }) return
    viewerOwner.applyResolvedPost(recovered)
}
