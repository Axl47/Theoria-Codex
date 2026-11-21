package com.theoriacodex.app.ui.routes

import com.theoriacodex.app.ui.components.FeedMarks
import com.theoriacodex.app.ui.components.feedDurations
import com.theoriacodex.app.ui.LocalNetworkOnline
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.SavedSearchEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theoriacodex.app.search.SearchCoordinator
import com.theoriacodex.app.search.SearchScreen
import com.theoriacodex.app.search.searchViewModelFactory
import com.theoriacodex.app.search.SearchViewModel
import com.theoriacodex.app.related.RelatedPostsLoading
import com.theoriacodex.app.related.loadedPosts
import com.theoriacodex.app.media.MediaDurationCoordinator
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchEffect
import com.theoriacodex.app.search.state.SearchUiState
import com.theoriacodex.app.viewer.PixivUgoiraClient
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.FavoriteTagEntry
import com.theoriacodex.data.repository.FeedFabRestoreState
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey

/** App-level inputs rendered by Search or used to reconcile its long-lived engine. */
internal data class SearchRouteConfig(
    val settings: AppSettings,
    val availableSources: Set<SourceKey>,
    val savedSearches: List<SavedSearchEntry> = emptyList(),
    val creatorBrowsingSources: Set<SourceKey>,
    val marks: FeedMarks,
    val favoriteTags: List<FavoriteTagEntry>,
    val resolveUnknownAnimatedDurations: Boolean,
)

/** Platform and navigation effects that remain owned by the application shell. */
internal data class SearchRouteCallbacks(
    val onOpenViewer: suspend (SearchEffect.OpenViewer) -> Unit,
    val onShowMessage: (String) -> Unit,
    val onToggleLike: ((Post) -> Unit)?,
    val onRequestSaveToCodex: (Post) -> Unit,
    val onSaveToDevice: (Post) -> Unit,
    val onOpenSourceAccounts: () -> Unit = {},
    val onOpenSavedSearch: (SavedSearchEntry) -> Unit = {},
    val onSaveSearch: (String, RecentSearchEntry) -> Unit = { _, _ -> },
    val onManageSavedSearches: () -> Unit = {},
    val onAddFavoriteTag: (SourceKey, List<String>, String?) -> Unit,
    val onRemoveFavoriteTag: (SourceKey, List<String>) -> Unit,
    val onRenameFavoriteTag: (SourceKey, List<String>, String) -> Unit,
)

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
    relatedPostsLoader: RelatedPostsLoading,
    mediaDurationCoordinator: MediaDurationCoordinator,
    pixivUgoiraClient: PixivUgoiraClient?,
    config: SearchRouteConfig,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: SearchRouteCallbacks,
    onOwnerAvailable: (SearchViewModel) -> Unit = {},
) {
    val owner = viewModel<SearchViewModel>(
        key = SEARCH_ROUTE_OWNER_KEY,
        factory = searchViewModelFactory(coordinator, relatedPostsLoader, LocalNetworkOnline.current),
    )
    val state by owner.state.collectAsStateWithLifecycle()
    val durationPosts = remember(state.content.results, state.relatedPosts) {
        (state.content.results + state.relatedPosts.loadedPosts).distinctBy(Post::id)
    }
    val duration = rememberMediaDurationRouteBinding(
        coordinator = mediaDurationCoordinator,
        routeName = "search",
        ownerKey = SEARCH_DURATION_OWNER_KEY,
        contentIdentity = state.query.appliedQueryHash.ifBlank { "unapplied" },
        posts = durationPosts,
        resolveInBackground = config.resolveUnknownAnimatedDurations,
    )
    // Held here so a typed gallery ID can close the composer before the Viewer opens over it.
    val composerOpen = rememberSaveable { mutableStateOf(false) }
    val currentOnOwnerAvailable by rememberUpdatedState(onOwnerAvailable)
    val lifecycleOwner = LocalLifecycleOwner.current

    SideEffect { currentOnOwnerAvailable(owner) }

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
            is SearchEffect.OpenViewer -> {
                if (effect.fromTypedId) composerOpen.value = false
                callbacks.onOpenViewer(effect)
            }
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
        composerOpen = composerOpen,
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
    composerOpen: MutableState<Boolean>,
    fabRestoreState: FeedFabRestoreState,
    onFabRestoreStateChange: (FeedFabRestoreState) -> Unit,
    callbacks: SearchRouteCallbacks,
) {
    // Capture only what each callback reads, so keystrokes and paging leave result cards untouched.
    val appliedQueryHash = state.query.appliedQueryHash
    val resolvePostById: suspend (PostId) -> Post? = remember(coordinator, appliedQueryHash) {
        { id -> coordinator.resolvePostForSearch(id, appliedQueryHash) }
    }
    val related = rememberFeedRelatedShelf(state.relatedPosts, owner.related) { posts, index ->
        owner.onAction(SearchAction.OpenRelatedResult(index, posts))
    }
    SearchScreen(
        state = state,
        related = related,
        composerOpen = composerOpen,
        oldSearchBar = config.settings.oldSearchBar,
        savedSearches = config.savedSearches,
        onOpenSavedSearch = callbacks.onOpenSavedSearch,
        onSaveSearch = callbacks.onSaveSearch,
        onManageSavedSearches = callbacks.onManageSavedSearches,
        creatorBrowsingSources = config.creatorBrowsingSources,
        onAction = owner::onAction,
        resolvePostById = resolvePostById,
        recoverPostMedia = coordinator::recoverPostMedia,
        pixivUgoiraClient = pixivUgoiraClient,
        marks = config.marks,
        favoriteTags = config.favoriteTags,
        durations = duration.owner.feedDurations(duration.states, config.resolveUnknownAnimatedDurations),
        onToggleLike = callbacks.onToggleLike,
        onRequestSaveToCodex = callbacks.onRequestSaveToCodex,
        onSaveToDevice = callbacks.onSaveToDevice,
        onOpenSourceAccounts = callbacks.onOpenSourceAccounts,
        onAddFavoriteTag = callbacks.onAddFavoriteTag,
        onRemoveFavoriteTag = callbacks.onRemoveFavoriteTag,
        onRenameFavoriteTag = callbacks.onRenameFavoriteTag,
        fabRestoreState = fabRestoreState,
        onFabRestoreStateChange = onFabRestoreStateChange,
    )
}

private const val SEARCH_ROUTE_OWNER_KEY = "search-route-owner"
private const val SEARCH_DURATION_OWNER_KEY = "search-duration-owner"
