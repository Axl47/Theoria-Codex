package com.theoriacodex.app.ui.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchScreen
import com.theoriacodex.app.search.SearchViewModel
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.io.Closeable
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.StateFlow

/** App-level inputs rendered by Search or used to reconcile its long-lived engine. */
internal data class SearchRouteConfig(
    val settings: AppSettings,
    val availableSources: Set<SourceKey>,
    val creatorBrowsingSources: Set<SourceKey>,
    val likedPostIds: Set<PostId>,
    val savedPostIds: Set<PostId>,
    val watchedPostIds: Set<PostId>,
    val favoriteTags: Map<SourceKey, List<String>>,
    val resolveUnknownAnimatedDurations: Boolean,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal data class SearchRouteCallbacks(
    val onOpenViewer: suspend (SearchEffect.OpenViewer) -> Unit,
    val onShowMessage: (String) -> Unit,
    val onToggleLike: ((Post) -> Unit)?,
    val onOpenCreatorProfile: (CreatorProfile) -> Unit,
    val onOpenLegacyCreatorProfile: (Post) -> Unit,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveToDevice: (Post) -> Unit,
    val onPostUrlCopied: (Post) -> Unit = {},
    val onAddFavoriteTag: (SourceKey, String) -> Unit,
    val onRemoveFavoriteTag: (SourceKey, String) -> Unit,
)

/**
 * Non-owning access to the Search destination for cross-route intents.
 *
 * The navigation entry remains the lifecycle owner. Keeping this handle in the app shell does not
 * retain the ViewModel after that entry is cleared.
 */
internal class SearchRouteOwnerHandle(
    owner: SearchViewModel,
) : ObservableRouteOwnerHandle {
    private val ownerLease = owner.createRouteOwnerLease()

    /** Read-only state while the navigation-owned ViewModel remains alive. */
    val state: StateFlow<SearchUiState>?
        get() = ownerLease.withOwner { activeOwner -> activeOwner.state }

    override fun invokeOnOwnerCleared(listener: () -> Unit): Closeable {
        return ownerLease.invokeOnClose(listener)
    }

    fun dispatch(action: SearchAction): Boolean {
        return ownerLease.withOwner { activeOwner ->
            activeOwner.onAction(action)
            true
        } ?: false
    }

    fun currentState(): SearchUiState? = ownerLease.withOwner { activeOwner -> activeOwner.state.value }
}

/**
 * A weak reference with an explicit destination lifetime.
 *
 * Weak references prevent accidental retention, while [close] makes owner destruction deterministic:
 * once it returns, later shell actions cannot reach the previous destination owner. The lease is
 * registered with ViewModel.addCloseable, so temporary composition removal does not invalidate it.
 */
internal class WeakRouteOwnerLease<Owner : Any>(owner: Owner) : Closeable {
    private val lock = Any()
    private var ownerReference: WeakReference<Owner>? = WeakReference(owner)
    private var closed = false
    private var nextCloseListenerId = 0L
    private val closeListeners = linkedMapOf<Long, () -> Unit>()

    fun <Result> withOwner(block: (Owner) -> Result): Result? = synchronized(lock) {
        ownerReference?.get()?.let(block)
    }

    /**
     * Observes the deterministic ViewModel-owned close signal.
     *
     * A listener registered after closure is invoked immediately. The returned registration removes
     * only that listener and never closes the owner lease itself.
     */
    fun invokeOnClose(listener: () -> Unit): Closeable {
        var listenerId: Long? = null
        val invokeImmediately = synchronized(lock) {
            if (closed) {
                true
            } else {
                listenerId = nextCloseListenerId++
                closeListeners[requireNotNull(listenerId)] = listener
                false
            }
        }
        if (invokeImmediately) listener()
        return Closeable {
            listenerId?.let { id ->
                synchronized(lock) {
                    closeListeners.remove(id)
                }
            }
        }
    }

    override fun close() {
        val listeners = synchronized(lock) {
            if (closed) {
                emptyList()
            } else {
                closed = true
                ownerReference?.clear()
                ownerReference = null
                closeListeners.values.toList().also { closeListeners.clear() }
            }
        }
        listeners.forEach { listener -> listener() }
    }
}

/**
 * Returns the single weak route-owner lease attached to this ViewModel lifetime.
 *
 * A navigation entry can leave and re-enter composition without clearing its ViewModel. Reusing a
 * keyed closeable prevents each newly remembered route handle from accumulating another closeable
 * while preserving the ViewModel as the only lifetime owner.
 */
internal fun <Owner : ViewModel> Owner.createRouteOwnerLease(): WeakRouteOwnerLease<Owner> {
    return synchronized(this) {
        getCloseable<WeakRouteOwnerLease<Owner>>(ROUTE_OWNER_LEASE_KEY)
            ?: WeakRouteOwnerLease(this).also { lease ->
                addCloseable(ROUTE_OWNER_LEASE_KEY, lease)
            }
    }
}

/** A route handle whose lifetime ends only when its navigation-owned ViewModel is cleared. */
internal interface ObservableRouteOwnerHandle {
    fun invokeOnOwnerCleared(listener: () -> Unit): Closeable
}

/**
 * Keeps one shell-visible route handle and removes it only when that exact owner is cleared.
 *
 * Publishing a replacement unsubscribes the previous listener. The identity check also protects a
 * newer owner from an old close callback that was already in flight when replacement occurred.
 */
internal class RouteOwnerHandleBinding<Handle : ObservableRouteOwnerHandle>(
    private val onHandleChanged: (Handle?) -> Unit,
) : Closeable {
    private var currentHandle: Handle? = null
    private var currentCloseRegistration: Closeable? = null

    val current: Handle?
        get() = currentHandle

    fun publish(handle: Handle) {
        if (currentHandle === handle) return

        currentCloseRegistration?.close()
        currentCloseRegistration = null
        currentHandle = handle
        onHandleChanged(handle)

        val registration = handle.invokeOnOwnerCleared {
            clearIfCurrent(handle)
        }
        if (currentHandle === handle) {
            currentCloseRegistration = registration
        } else {
            // The owner may already have been closed when the listener was registered.
            registration.close()
        }
    }

    private fun clearIfCurrent(expected: Handle) {
        if (currentHandle !== expected) return

        currentCloseRegistration?.close()
        currentCloseRegistration = null
        currentHandle = null
        onHandleChanged(null)
    }

    override fun close() {
        currentCloseRegistration?.close()
        currentCloseRegistration = null
        if (currentHandle != null) {
            currentHandle = null
            onHandleChanged(null)
        }
    }
}

/** Delivers one Search resume action for each lifecycle resume period. */
internal class SearchRouteResumeObserver(
    private val onResume: () -> Unit,
) : LifecycleEventObserver {
    private var deliveredForCurrentResume = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        onLifecycleEvent(event)
    }

    fun synchronize(state: Lifecycle.State) {
        if (state.isAtLeast(Lifecycle.State.RESUMED)) {
            deliverResumeOnce()
        }
    }

    internal fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> deliverResumeOnce()
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY,
            -> deliveredForCurrentResume = false
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_ANY,
            -> Unit
        }
    }

    private fun deliverResumeOnce() {
        if (deliveredForCurrentResume) return
        deliveredForCurrentResume = true
        onResume()
    }
}

/** Navigation-scoped Search owner plus its immutable rendering boundary. */
@Composable
internal fun SearchRoute(
    coordinator: SearchCoordinator,
    mediaDurationCoordinator: MediaDurationCoordinator,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: SearchRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: SearchRouteCallbacks,
    onOwnerAvailable: (SearchRouteOwnerHandle) -> Unit = {},
) {
    val owner = viewModel<SearchViewModel>(
        key = SEARCH_ROUTE_OWNER_KEY,
        factory = SearchViewModel.factory(coordinator),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val duration = rememberMediaDurationRouteBinding(
        coordinator = mediaDurationCoordinator,
        routeName = "search",
        ownerKey = SEARCH_DURATION_OWNER_KEY,
        contentIdentity = state.query.appliedQueryHash.ifBlank { "unapplied" },
        posts = state.content.results,
        resolveInBackground = config.resolveUnknownAnimatedDurations,
    )
    val ownerHandle = remember(owner) { SearchRouteOwnerHandle(owner) }
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)
    val lifecycleOwner = LocalLifecycleOwner.current

    SideEffect { currentOnOwnerAvailable(ownerHandle) }

    LaunchedEffect(owner) {
        owner.onAction(SearchAction.Restore)
    }
    LaunchedEffect(owner, config.settings, config.availableSources) {
        owner.synchronizeEnvironment(config.settings)
    }
    DisposableEffect(owner, lifecycleOwner) {
        val observer = SearchRouteResumeObserver {
            owner.onAction(SearchAction.Resume)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        observer.synchronize(lifecycleOwner.lifecycle.currentState)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CollectRouteEffects(owner.effects) { effect ->
        when (effect) {
            is SearchEffect.OpenViewer -> callbacks.onOpenViewer(effect)
            is SearchEffect.ShowMessage -> callbacks.onShowMessage(effect.message)
        }
    }

    SearchRouteContent(
        state = state,
        owner = owner,
        coordinator = coordinator,
        duration = duration,
        pixivUgoiraClient = pixivUgoiraClient,
        config = config,
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = onFabRestoreStateChange,
        callbacks = callbacks,
    )
}

@Composable
private fun SearchRouteContent(
    state: SearchUiState,
    owner: SearchViewModel,
    coordinator: SearchCoordinator,
    duration: MediaDurationRouteBinding,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: SearchRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: SearchRouteCallbacks,
) {
    SearchScreen(
        state = state,
        creatorBrowsingSources = config.creatorBrowsingSources,
        onAction = owner::onAction,
        resolvePostById = { id -> coordinator.resolvePostForSearch(id, state.query.appliedQueryHash) },
        recoverPostMedia = coordinator::recoverPostMedia,
        tagVideoCountProvider = { source, tag ->
            coordinator.tagVideoCount(
                source, tag, state.suggestions.autocomplete, state.suggestions.trending,
            )
        },
        fetchTagVideoCounts = { source, tags ->
            coordinator.fetchTagVideoCounts(
                source, tags, state.suggestions.autocomplete, state.suggestions.trending,
            )
        },
        pixivUgoiraClient = pixivUgoiraClient,
        likedPostIds = config.likedPostIds,
        savedPostIds = config.savedPostIds,
        watchedPostIds = config.watchedPostIds,
        favoriteTags = config.favoriteTags,
        resolveUnknownAnimatedDurations = config.resolveUnknownAnimatedDurations,
        durationStates = duration.states,
        durationStateForPost = duration.stateForPost,
        onDurationFilterChanged = duration.owner::onFilterChanged,
        onDurationPostVisibilityChanged = duration.owner::onPostVisibilityChanged,
        onDurationEnvironmentChanged = duration.owner::onEnvironmentChanged,
        onAuthoritativeDurationKnown = duration.owner::publishPlayerDuration,
        onToggleLike = callbacks.onToggleLike,
        onOpenCreatorProfile = callbacks.onOpenCreatorProfile,
        onOpenLegacyCreatorProfile = callbacks.onOpenLegacyCreatorProfile,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onPostUrlCopied = callbacks.onPostUrlCopied,
        onAddFavoriteTag = callbacks.onAddFavoriteTag,
        onRemoveFavoriteTag = callbacks.onRemoveFavoriteTag,
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = onFabRestoreStateChange,
    )
}

private const val SEARCH_ROUTE_OWNER_KEY = "search-route-owner"
private const val SEARCH_DURATION_OWNER_KEY = "search-duration-owner"
private const val ROUTE_OWNER_LEASE_KEY = "route-owner-lease"
