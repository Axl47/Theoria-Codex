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
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
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
) {
    private val ownerLease = owner.createRouteOwnerLease()

    /** Read-only state while this handle is published by the composed destination. */
    val state: StateFlow<SearchUiState> = owner.state

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

    fun <Result> withOwner(block: (Owner) -> Result): Result? = synchronized(lock) {
        ownerReference?.get()?.let(block)
    }

    override fun close() {
        synchronized(lock) {
            ownerReference?.clear()
            ownerReference = null
        }
    }
}

internal fun <Owner : ViewModel> Owner.createRouteOwnerLease(): WeakRouteOwnerLease<Owner> {
    return WeakRouteOwnerLease(this).also { lease -> addCloseable(lease) }
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
    pixivUgoiraClient: PixivUgoiraClient?,
    config: SearchRouteConfig,
    callbacks: SearchRouteCallbacks,
    onOwnerAvailable: (SearchRouteOwnerHandle) -> Unit = {},
) {
    val owner = viewModel<SearchViewModel>(
        key = SEARCH_ROUTE_OWNER_KEY,
        factory = SearchViewModel.factory(coordinator),
    )
    val state by owner.state.collectAsStateWithLifecycle()
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

    SearchScreen(
        state = state,
        creatorBrowsingSources = config.creatorBrowsingSources,
        onAction = owner::onAction,
        resolvePostById = coordinator::resolvePostForSearch,
        recoverPostMedia = coordinator::recoverPostMedia,
        tagVideoCountProvider = coordinator::tagVideoCount,
        fetchTagVideoCounts = coordinator::fetchTagVideoCounts,
        pixivUgoiraClient = pixivUgoiraClient,
        likedPostIds = config.likedPostIds,
        savedPostIds = config.savedPostIds,
        favoriteTags = config.favoriteTags,
        resolveUnknownAnimatedDurations = config.resolveUnknownAnimatedDurations,
        onToggleLike = callbacks.onToggleLike,
        onOpenCreatorProfile = callbacks.onOpenCreatorProfile,
        onOpenLegacyCreatorProfile = callbacks.onOpenLegacyCreatorProfile,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onAddFavoriteTag = callbacks.onAddFavoriteTag,
        onRemoveFavoriteTag = callbacks.onRemoveFavoriteTag,
    )
}

private const val SEARCH_ROUTE_OWNER_KEY = "search-route-owner"
