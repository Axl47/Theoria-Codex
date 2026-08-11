package com.theoriacodex.app.ui.routes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.search.UnknownAnimatedDurationPolicy
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.media.MediaDurationRouteViewModel
import com.theoriacodex.app.search.filterSearchResults
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.app.viewer.ViewerMediaPrefetcher
import com.theoriacodex.app.viewer.ViewerPostResolver
import com.theoriacodex.app.viewer.ViewerScreen
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.app.viewer.mergeViewerPosts
import com.theoriacodex.app.viewer.state.ViewerAction
import com.theoriacodex.app.viewer.state.ViewerEffect
import com.theoriacodex.app.viewer.state.ViewerSessionIdentity
import com.theoriacodex.app.viewer.state.ViewerUiState
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Dependencies whose lifetime is longer than one Viewer destination composition. */
internal data class ViewerRouteDependencies(
    val sessionRetentionOwner: ViewerSessionRetentionViewModel,
    val postResolver: ViewerPostResolver,
    val mediaPrefetcher: ViewerMediaPrefetcher,
    val mediaDurationCoordinator: MediaDurationCoordinator,
    val restoreSession: suspend (ViewerSessionIdentity) -> ViewerSession?,
)

/** Immutable values consumed by the Viewer renderer. */
internal data class ViewerRouteRenderConfig(
    val pixivUgoiraClient: PixivUgoiraClient? = null,
    val tagVideoCountProvider: (SourceKey, String) -> Int? = { _, _ -> null },
    val fetchTagVideoCounts: suspend (SourceKey, List<String>) -> Map<String, Int?> = { _, _ ->
        emptyMap()
    },
    val invertMultiImageScrollDirection: Boolean = false,
    val likedPostIds: Set<PostId> = emptySet(),
    val creatorBrowsingSources: Set<SourceKey>,
)

/** Immutable source-owner snapshot consumed by a live Viewer session. */
internal data class ViewerRouteLiveSourceSnapshot(
    val queryHash: String? = null,
    val results: List<Post> = emptyList(),
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
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
        ViewerStreamSource.CODEX,
        ViewerStreamSource.RECENTS,
        -> null
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
    val onSavePost: (Post) -> Unit,
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
    val onInvertMultiImageScrollDirectionChange: (Boolean) -> Unit = {},
    val onVisiblePostChanged: (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onVisibleMediaChanged: (Post, Int, ViewerSession) -> Unit = { _, _, _ -> },
    val onOpenInBrowser: (Post) -> Unit,
    val onRemoveIncludeTerm: (Post, SearchTerm) -> Unit,
    val onRemoveExcludeTerm: (Post, SearchTerm) -> Unit,
    val onFavoriteTagLongPress: ((SourceKey, String) -> Unit)? = null,
    val onGoToSearch: () -> Unit,
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
            activeOwner.clearSession()
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
    val latestDependencies = rememberUpdatedState(dependencies)
    val latestEffectCallbacks = rememberUpdatedState(effectCallbacks)
    val latestScreenCallbacks = rememberUpdatedState(screenCallbacks)
    val routeScope = rememberCoroutineScope()
    val ownerFactory = remember {
        ViewerViewModel.factory(
            postResolver = ViewerPostResolver { session, postId ->
                latestDependencies.value.postResolver.resolve(session, postId)
            },
            mediaPrefetcher = ViewerMediaPrefetcher { session, media ->
                latestDependencies.value.mediaPrefetcher.prefetch(session, media)
            },
        )
    }
    val viewerOwner = viewModel<ViewerViewModel>(factory = ownerFactory)
    val durationOwner = viewModel<MediaDurationRouteViewModel>(
        key = "viewer-duration-owner",
        factory = MediaDurationRouteViewModel.factory(
            dependencies.mediaDurationCoordinator,
            "viewer",
        ),
    )
    val ownerHandle = remember(viewerOwner) { ViewerRouteOwnerHandle(viewerOwner) }
    val viewerState by viewerOwner.state.collectAsStateWithLifecycle()
    val session by viewerOwner.session.collectAsStateWithLifecycle()
    val claimedSessionId = remember(viewerOwner) {
        dependencies.sessionRetentionOwner.session.value?.sessionId
    }

    DisposableEffect(viewerOwner) {
        latestScreenCallbacks.value.onOwnerChanged(ownerHandle)
        onDispose {
            latestScreenCallbacks.value.onOwnerChanged(null)
        }
    }

    LaunchedEffect(viewerOwner, claimedSessionId) {
        val currentDependencies = latestDependencies.value
        val handedOff = currentDependencies.sessionRetentionOwner.handoffTo(
            owner = viewerOwner,
            claimedSessionId = claimedSessionId,
        )
        if (!handedOff && viewerOwner.session.value == null) {
            val pending = viewerOwner.pendingRestoration
            val restored = try {
                pending?.let { identity -> currentDependencies.restoreSession(identity) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (restored == null) {
                viewerOwner.clearSession()
                latestEffectCallbacks.value.onRestorationUnavailable()
            } else {
                viewerOwner.replaceSession(restored)
            }
        }
    }

    val activeLiveSource = session?.let { active ->
        liveSourceState.forSource(active.context.streamSource)
    }
    LaunchedEffect(
        viewerOwner,
        session?.sessionId,
        session?.searchVisibilityFilters,
        activeLiveSource,
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

    CollectRouteEffects(viewerOwner.effects) { effect ->
        val callbacks = latestEffectCallbacks.value
        val currentState = viewerOwner.state.value
        if (!effect.isCurrentFor(currentState)) return@CollectRouteEffects
        fun post(postId: PostId): Post? = currentState.pages
            .firstOrNull { page -> page.post.id == postId }
            ?.post

        when (effect) {
            is ViewerEffect.SavePost -> post(effect.postId)?.let(callbacks.onSavePost)

            is ViewerEffect.ShareMedia -> callbacks.onSharePost(post(effect.postId))

            is ViewerEffect.DownloadMedia -> {
                val page = currentState.pages.firstOrNull { candidate ->
                    candidate.post.id == effect.postId
                }
                val media = page?.media?.firstOrNull { candidate ->
                    candidate.key == effect.mediaKey
                }?.ref
                callbacks.onDownloadMedia(
                    if (page != null && media != null) {
                        ViewerDownloadRequest(
                            post = page.post,
                            media = media,
                            pageIndex = effect.mediaKey.mediaIndex,
                            totalPages = page.media.size,
                        )
                    } else {
                        null
                    },
                )
            }

            is ViewerEffect.SetLiked -> post(effect.postId)?.let { selected ->
                callbacks.onToggleLike(selected)
            }

            is ViewerEffect.OpenCreatorProfile -> callbacks.onOpenCreatorProfile(effect.creator)

            is ViewerEffect.ApplyTag -> post(effect.postId)?.let { selected ->
                callbacks.onApplyTag(selected, effect.term, effect.excluded)
            }

            is ViewerEffect.RetryMedia -> {
                val page = currentState.pages.firstOrNull { candidate ->
                    candidate.post.id == effect.mediaKey.postId
                }
                val failedMedia = page?.media?.firstOrNull { candidate ->
                    candidate.key == effect.mediaKey
                }?.ref
                if (page != null && failedMedia != null) {
                    recoverAndApplyMedia(
                        viewerOwner = viewerOwner,
                        post = page.post,
                        failedMedia = failedMedia,
                        callbacks = callbacks,
                    )
                }
            }

            is ViewerEffect.LoadMore -> viewerOwner.session.value
                ?.context
                ?.streamSource
                ?.let(callbacks.onLoadMore)

            is ViewerEffect.Dismiss -> {
                viewerOwner.clearSession()
                latestDependencies.value.sessionRetentionOwner.clear()
                callbacks.onDismiss()
            }

            is ViewerEffect.ResolvePost,
            is ViewerEffect.PrefetchMedia,
            -> Unit
        }
    }

    val activeSession = session
    if (activeSession == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    val matchingSourceState = liveSourceState
        .forSource(activeSession.context.streamSource)
        ?.takeIf { sourceState -> sourceState.queryHash == activeSession.context.queryHash }
    val canLoadMoreFromSource = activeSession.liveSearchBinding &&
        matchingSourceState?.canLoadMore == true
    SideEffect {
        durationOwner.synchronize(
            identity = activeSession.sessionId,
            posts = viewerState.pages.map { page -> page.post },
            resolveInBackground = false,
        )
    }

    ViewerScreen(
        uiState = viewerState,
        creatorBrowsingSources = renderConfig.creatorBrowsingSources,
        onAction = viewerOwner::onAction,
        pixivUgoiraClient = renderConfig.pixivUgoiraClient,
        tagVideoCountProvider = renderConfig.tagVideoCountProvider,
        fetchTagVideoCounts = renderConfig.fetchTagVideoCounts,
        canLoadMoreFromSource = canLoadMoreFromSource,
        loadingMoreFromSource = matchingSourceState?.loadingMore == true,
        invertMultiImageScrollDirection = renderConfig.invertMultiImageScrollDirection,
        onInvertMultiImageScrollDirectionChange =
            screenCallbacks.onInvertMultiImageScrollDirectionChange,
        likedPostIds = renderConfig.likedPostIds,
        onRequestMediaRecovery = { post, failedMedia ->
            routeScope.launch {
                recoverAndApplyMedia(
                    viewerOwner = viewerOwner,
                    post = post,
                    failedMedia = failedMedia,
                    callbacks = latestEffectCallbacks.value,
                )
            }
        },
        onVisiblePostChanged = { post, viewedMediaNumber ->
            viewerOwner.session.value?.let { currentSession ->
                latestScreenCallbacks.value.onVisiblePostChanged(
                    post,
                    viewedMediaNumber,
                    currentSession,
                )
            }
        },
        onVisibleMediaChanged = { post, viewedMediaNumber ->
            viewerOwner.session.value?.let { currentSession ->
                latestScreenCallbacks.value.onVisibleMediaChanged(
                    post,
                    viewedMediaNumber,
                    currentSession,
                )
            }
        },
        onAuthoritativeDurationKnown = durationOwner::publishPlayerDuration,
        onOpenInBrowser = screenCallbacks.onOpenInBrowser,
        onRemoveIncludeTerm = screenCallbacks.onRemoveIncludeTerm,
        onRemoveExcludeTerm = screenCallbacks.onRemoveExcludeTerm,
        onFavoriteTagLongPress = screenCallbacks.onFavoriteTagLongPress,
        onGoToSearch = {
            viewerOwner.clearSession()
            latestDependencies.value.sessionRetentionOwner.clear()
            latestScreenCallbacks.value.onGoToSearch()
        },
        onOpenCreatorFallback = screenCallbacks.onOpenCreatorFallback,
    )
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
