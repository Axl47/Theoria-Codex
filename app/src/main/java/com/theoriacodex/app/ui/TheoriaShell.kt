package com.theoriacodex.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.app.appshell.AppShellAction
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.codex.CodexCommandViewModel
import com.theoriacodex.app.codex.CodexSaveViewModel
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.codex.transfer.CodexExportResult
import com.theoriacodex.app.codex.transfer.CodexImportViewModel
import com.theoriacodex.app.codex.transfer.readCodexDocument
import com.theoriacodex.app.codex.transfer.writeCodexDocument
import com.theoriacodex.app.creator.CreatorFollowsViewModel
import com.theoriacodex.app.creator.CreatorOpeningWorkflow
import com.theoriacodex.app.creator.state.CreatorAction
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.media.PostDownloadItem
import com.theoriacodex.app.post.PostMutationViewModel
import com.theoriacodex.app.recents.RecentsClearWorkflow
import com.theoriacodex.app.recents.SavedSearchViewModel
import com.theoriacodex.app.recents.fypSeedBySource
import com.theoriacodex.app.recommend.state.ForYouAction
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.parseExternalCreatorDeepLink
import com.theoriacodex.app.source.parseCustomSitePostDeepLink
import com.theoriacodex.app.source.parseExternalPostDeepLink
import com.theoriacodex.app.source.unaddedBooruPostHost
import com.theoriacodex.app.source.ExternalPostDeepLink
import com.theoriacodex.app.ui.components.FeedFabRestoreRegistry
import com.theoriacodex.app.ui.components.rememberFeedFabRestoreRegistry
import com.theoriacodex.app.ui.routes.CreatorProfileStateSaver
import com.theoriacodex.app.ui.routes.CreatorRouteOwnerHandle
import com.theoriacodex.app.ui.routes.ForYouRouteOwnerHandle
import com.theoriacodex.app.ui.routes.PendingRouteActions
import com.theoriacodex.app.ui.routes.RouteOwnerHandleBinding
import com.theoriacodex.app.ui.routes.SearchRouteOwnerHandle
import com.theoriacodex.app.ui.routes.ViewerRouteOwnerHandle
import com.theoriacodex.app.ui.routes.ViewerRouteWorkflow
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import com.theoriacodex.app.update.ReleaseChangelogEntry
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.app.tags.PostTagSearchActions
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.QueryMode
import com.theoriacodex.domain.model.SearchTerm
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Search, For You, and Creator owners the shell may reach, and actions waiting for one to exist.
 *
 * These bindings outlive individual destination compositions. Viewer can keep paging through a
 * still-live back-stack owner; only ViewModel lease closure removes the matching handle. Hold one
 * instance for the shell's whole lifetime and close it only when the shell leaves composition.
 */
@Stable
internal class RouteOwners {
    var searchOwner by mutableStateOf<SearchRouteOwnerHandle?>(null)
        private set
    var forYouOwner by mutableStateOf<ForYouRouteOwnerHandle?>(null)
        private set
    var creatorOwner by mutableStateOf<CreatorRouteOwnerHandle?>(null)
        private set
    val searchBinding = RouteOwnerHandleBinding<SearchRouteOwnerHandle> { owner -> searchOwner = owner }
    val forYouBinding = RouteOwnerHandleBinding<ForYouRouteOwnerHandle> { owner -> forYouOwner = owner }
    val creatorBinding = RouteOwnerHandleBinding<CreatorRouteOwnerHandle> { owner -> creatorOwner = owner }
    private val pendingSearchActions = PendingRouteActions<SearchAction>()
    private val pendingForYouActions = PendingRouteActions<ForYouAction>()

    fun close() {
        searchBinding.close()
        forYouBinding.close()
        creatorBinding.close()
    }

    fun publishSearch(owner: SearchRouteOwnerHandle) {
        searchBinding.publish(owner)
        pendingSearchActions.flush(owner::dispatch)
    }

    fun publishForYou(owner: ForYouRouteOwnerHandle) {
        forYouBinding.publish(owner)
        pendingForYouActions.flush(owner::dispatch)
    }

    fun dispatchOrQueue(action: SearchAction): Boolean =
        pendingSearchActions.dispatchOrEnqueue(action) { queuedAction -> searchOwner?.dispatch(queuedAction) == true }

    fun dispatchOrQueue(action: ForYouAction): Boolean =
        pendingForYouActions.dispatchOrEnqueue(action) { queuedAction -> forYouOwner?.dispatch(queuedAction) == true }

    val addSearchIncludeTerm: (Post, SearchTerm) -> Boolean = { post, term ->
        dispatchOrQueue(SearchAction.AddPostIncludeTerm(post, term))
    }
    val addSearchExcludeTerm: (Post, SearchTerm) -> Boolean = { post, term ->
        dispatchOrQueue(SearchAction.AddPostExcludeTerm(post, term))
    }
    val removeSearchIncludeTerm: (Post, SearchTerm) -> Unit = { _, term ->
        dispatchOrQueue(SearchAction.RemoveIncludeTerm(term))
    }
    val removeSearchExcludeTerm: (Post, SearchTerm) -> Unit = { _, term ->
        dispatchOrQueue(SearchAction.RemoveExcludeTerm(term))
    }
    val postTagSearchActions = PostTagSearchActions(
        addAlternative = { post, groupIndex, term ->
            dispatchOrQueue(SearchAction.AddPostIncludeAlternative(post, groupIndex, term))
        },
        applyDraft = { dispatchOrQueue(SearchAction.ApplyDraft) },
    )
}

/** Which home tab is showing, and the routes that lead back to it. */
@Stable
internal class HomeNavigation(
    val navController: NavHostController,
    homeTabRoute: MutableState<String>,
    val pagerState: PagerState,
) {
    var homeTabRoute by homeTabRoute
    var pendingTopLevelRoute by mutableStateOf<String?>(null)

    val currentRoute: String?
        get() = navController.currentBackStackEntry?.destination?.route

    fun goToSearch() {
        homeTabRoute = TopLevelDestination.Search.route
        pendingTopLevelRoute = TopLevelDestination.Search.route
        if (navController.currentDestination?.route != AppRoute.Home &&
            !navController.popBackStack(AppRoute.Home, inclusive = false)
        ) {
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    /** Selects a tab without a page animation, which is how every cross-tab jump arrives. */
    suspend fun showTab(destination: TopLevelDestination) {
        val targetIndex = TopLevelDestination.entries.indexOf(destination)
        homeTabRoute = destination.route
        if (pagerState.currentPage != targetIndex) pagerState.scrollToPage(targetIndex)
    }
}

/** Posts on their way into a Codex, and what the save sheet should offer for them. */
@Stable
internal class PendingCodexSave {
    var visible by mutableStateOf(false)
        private set
    var posts by mutableStateOf<List<Post>>(emptyList())
        private set
    var query by mutableStateOf<Query?>(null)
        private set
    var fromForYou by mutableStateOf(false)
        private set
    var excludedCodexIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun request(
        posts: List<Post>,
        fromForYou: Boolean = false,
        excludedCodexId: String? = null,
        searchQuery: Query? = null,
    ) {
        val uniquePosts = posts.distinctBy(Post::id)
        if (uniquePosts.isEmpty()) return
        this.posts = uniquePosts
        query = searchQuery
        this.fromForYou = fromForYou
        excludedCodexIds = setOfNotNull(excludedCodexId)
        visible = true
    }

    fun clear() {
        visible = false
        posts = emptyList()
        query = null
        fromForYou = false
        excludedCodexIds = emptySet()
    }
}

/** Sheets and dialogs the shell shows over whichever destination is current. */
@Stable
internal class ShellOverlays {
    var showFollowedCreators by mutableStateOf(false)
    var showSavedSearchManagement by mutableStateOf(false)
    var codexActionTargetId by mutableStateOf<String?>(null)
    var releaseHistoryEntries by mutableStateOf<List<ReleaseChangelogEntry>?>(null)

    /** Saved's collections are virtual except Likes, so each opens the menu that manages it. */
    fun openCollectionActions(id: String) {
        when (id) {
            FOLLOWED_CODEX_ID -> showFollowedCreators = true
            SAVED_SEARCHES_CODEX_ID -> showSavedSearchManagement = true
            else -> codexActionTargetId = id
        }
    }
}

/** Activity-scoped ViewModels the shell coordinates; created above the NavHost so entries share them. */
internal class ShellOwners(
    val savedSearches: SavedSearchViewModel,
    val codexCommands: CodexCommandViewModel,
    val postMutations: PostMutationViewModel,
    val codexSaves: CodexSaveViewModel,
    val settings: SettingsViewModel,
    val follows: CreatorFollowsViewModel,
    val codexImports: CodexImportViewModel,
)

@Composable
private fun rememberShellOwners(container: TheoriaAppContainer, appContext: Context): ShellOwners {
    val data = container.data
    val workflows = container.workflows
    val savedSearches = viewModel<SavedSearchViewModel>(
        key = "saved-searches",
        factory = viewModelFactory { initializer { SavedSearchViewModel(data.savedSearches) } },
    )
    val codexCommands = viewModel<CodexCommandViewModel>(
        key = "codex-command-owner",
        factory = viewModelFactory {
            initializer {
                CodexCommandViewModel(
                    data.codexRepository, workflows.likesCodexSync::clearProfile, workflows.likesCodexSync::restoreClearedLikes,
                    workflows.codexTransfer::reorderVisibleCodices, workflows.codexCleaning,
                )
            }
        },
    )
    val postMutations = viewModel<PostMutationViewModel>(
        key = "post-mutations",
        factory = viewModelFactory { initializer { PostMutationViewModel(data.settingsRepository, workflows.likesCodexSync::toggle) } },
    )
    val codexSaves = viewModel<CodexSaveViewModel>(
        key = "codex-save-owner",
        factory = viewModelFactory {
            initializer { CodexSaveViewModel(workflows.codexTransfer, data.codexRepository, data.statisticsRepository) }
        },
    )
    val settings = viewModel<SettingsViewModel>(key = "settings-route-owner", factory = SettingsViewModel.factory(container))
    val follows = viewModel<CreatorFollowsViewModel>(
        key = "creator-follows",
        factory = CreatorFollowsViewModel.factory(data.settingsRepository, container.sources.registry),
    )
    val codexImports = viewModel<CodexImportViewModel>(
        key = "codex-import-owner",
        factory = viewModelFactory {
            initializer {
                CodexImportViewModel(
                    transfer = workflows.codexTransfer,
                    readDocument = { document -> readCodexDocument { appContext.contentResolver.openInputStream(document.toUri()) } },
                    newTargetCodexId = { profileScopedCodexId(data.currentActiveRecommendationProfile().profileId) },
                )
            }
        },
    )
    return remember(savedSearches, codexCommands, postMutations, codexSaves, settings, follows, codexImports) {
        ShellOwners(savedSearches, codexCommands, postMutations, codexSaves, settings, follows, codexImports)
    }
}

/**
 * What the app shell's parts share: dependencies, its state holders, and the commands that cross
 * destinations. Commands read state when they run, so they always act on the current destination.
 */
@Stable
internal class TheoriaShell(
    val container: TheoriaAppContainer,
    val viewerSessions: ViewerSessionRetentionViewModel,
    val appShellOwner: AppShellViewModel,
    val appContext: Context,
    val scope: CoroutineScope,
    val snackbarHostState: SnackbarHostState,
    val feedFabRestoreRegistry: FeedFabRestoreRegistry,
    val transfers: LibraryTransferTools,
    val owners: ShellOwners,
    val codexImportLauncher: ActivityResultLauncher<Array<String>>,
    val routes: RouteOwners,
    val navigation: HomeNavigation,
    val pendingSave: PendingCodexSave,
    val overlays: ShellOverlays,
    savedSearches: State<List<SavedSearchEntry>>,
    pendingLikePostIds: State<Map<String, Set<PostId>>>,
    pendingCreatorProfile: MutableState<CreatorProfile?>,
    thumbnailCacheGeneration: MutableIntState,
) {
    val data = container.data
    val sources = container.sources
    val features = container.features
    val updates = container.updates
    val workflows = container.workflows
    val navController get() = navigation.navController
    val savedSearches by savedSearches
    val pendingLikePostIds by pendingLikePostIds
    var pendingCreatorProfile by pendingCreatorProfile
    var thumbnailCacheGeneration by thumbnailCacheGeneration
    var navReady by mutableStateOf(appShellOwner.state.value.appReady)
    var activeViewerOwner by mutableStateOf<ViewerRouteOwnerHandle?>(null)
    val creatorOpening = CreatorOpeningWorkflow(sources.registry, sources.httpClient)
    val recentsClearWorkflow = RecentsClearWorkflow(data.recentsRepository)
    val viewerRouteWorkflow = ViewerRouteWorkflow(
        data = data,
        cacheScope = scope,
        sources = sources,
        searchOwner = { routes.searchOwner },
        forYouOwner = { routes.forYouOwner },
        creatorOwner = { routes.creatorOwner },
        offlineMedia = features.offlineMedia,
    )
    /** Saves one tag, or an "or" group with an optional name, for the active profile. */
    val addFavoriteTags: (SourceKey, List<String>, String?) -> Unit = { source, tags, name ->
        owners.postMutations.favorite(owners.settings.state.value.activeProfile.profileId, source, tags, true, name)
    }
    val addFavoriteTag: (SourceKey, String) -> Unit = { source, tag -> addFavoriteTags(source, listOf(tag), null) }
    val renameFavoriteTags: (SourceKey, List<String>, String) -> Unit = { source, tags, name ->
        owners.postMutations.renameFavorite(owners.settings.state.value.activeProfile.profileId, source, tags, name)
    }
    val removeFavoriteTags: (SourceKey, List<String>) -> Unit = { source, tags ->
        owners.postMutations.favorite(owners.settings.state.value.activeProfile.profileId, source, tags, false)
    }

    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    fun recordPostUrlCopy() {
        scope.launch {
            runCatchingPreservingCancellation { data.statisticsRepository.recordPostUrlCopy() }
        }
    }

    suspend fun showActionableFeedback(message: String, actionLabel: String): Boolean {
        return snackbarHostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        ) == SnackbarResult.ActionPerformed
    }

    fun requestSaveToCodex(post: Post, fromForYou: Boolean = false, searchQuery: Query? = null) {
        pendingSave.request(listOf(post), fromForYou, searchQuery = searchQuery)
    }

    fun requestSaveToDevice(post: Post) {
        val origin = if (navigation.currentRoute == AppRoute.CreatorProfile) {
            "Creator profile"
        } else when (navigation.homeTabRoute) {
            TopLevelDestination.ForYou.route -> "FYP"
            TopLevelDestination.Recents.route -> "Recents"
            else -> "Search"
        }
        transfers.downloads.submitPosts(listOf(post), originLabel = origin)
        transfers.showDownloads = true
    }

    suspend fun openCreatorProfile(creator: CreatorProfile) {
        pendingCreatorProfile = creator
        if (navigation.currentRoute == AppRoute.Viewer) {
            activeViewerOwner?.clearSession()
            viewerSessions.clear()
            features.search.setViewerLaunchContext(null)
            navController.popBackStack(AppRoute.Viewer, inclusive = true)
        }
        navController.navigate(AppRoute.CreatorProfile) {
            launchSingleTop = true
        }
    }

    suspend fun openCreatorProfile(post: Post) {
        val outcome = creatorOpening.fromPost(post, sources.availableSources.value)
        outcome.resolvedPost?.let { routes.dispatchOrQueue(SearchAction.RememberResolvedPost(it)) }
        val creator = outcome.creator
        if (creator == null) {
            toast("Creator profile unavailable")
            return
        }
        openCreatorProfile(creator)
        val resolvedPost = outcome.resolvedPost ?: post
        viewerSessions.update { session ->
            val index = session.posts.indexOfFirst { current -> current.id == resolvedPost.id }
            if (index < 0) session else session.copy(
                posts = session.posts.toMutableList().apply { this[index] = resolvedPost },
            )
        }
    }

    fun launchOpenCreator(creator: CreatorProfile) {
        scope.launch { openCreatorProfile(creator) }
    }

    fun launchOpenCreator(post: Post) {
        scope.launch { openCreatorProfile(post) }
    }

    /** Commits only while the profile that liked the post is still the active one. */
    fun toggleLikeAndSyncCodex(post: Post, onCommitted: (LikeToggleOutcome) -> Unit = {}) {
        val profile = owners.settings.state.value.activeProfile
        owners.postMutations.like(profile, post) { outcome ->
            if (owners.settings.state.value.activeProfile.profileId == profile.profileId) onCommitted(outcome)
        }
    }

    /** A like action bound to the Search owner and query current now; a later query ignores its result. */
    fun searchLikeAction(): (Post) -> Unit {
        val owner = routes.searchBinding.current
        val query = owner?.state?.value?.query?.appliedQueryHash
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.state?.value?.query?.appliedQueryHash == query) {
                    owner?.dispatch(SearchAction.RelatedLikeCommitted(post, outcome))
                }
            }
        }
    }

    /** A like action bound to the For You owner and seed current now; a later seed ignores its result. */
    fun forYouLikeAction(): (Post) -> Unit {
        val owner = routes.forYouBinding.current
        val seed = owner?.state?.value?.seedId
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.state?.value?.seedId == seed) {
                    owner?.dispatch(ForYouAction.RelatedLikeCommitted(post, outcome))
                }
            }
        }
    }

    /** A like action bound to the Creator owner and query current now; a later query ignores its result. */
    fun creatorLikeAction(): (Post) -> Unit {
        val owner = routes.creatorBinding.current
        val query = owner?.currentState()?.queryHash
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner?.currentState()?.queryHash == query) {
                    owner?.dispatch(CreatorAction.RelatedLikeCommitted(post, outcome))
                }
            }
        }
    }

    /** Replaces the current search with [query], built in a Codex for [source]. */
    fun searchFromCodex(source: SourceKey, query: Query) {
        if (source !in sources.registry.availableSources()) {
            toast("${source.displayName()} source is unavailable")
            return
        }
        routes.dispatchOrQueue(
            SearchAction.ApplyTagSearch(query.effectiveIncludeTermGroups, query.excludeTerms, query.mode),
        )
        navigation.goToSearch()
        val tags = query.effectiveIncludeTermGroups.map { group -> group.terms.joinToString(" or ") { it.value } } +
            query.excludeTerms.map { "-${it.value}" }
        toast("Searching ${source.displayName()} codex tags: ${tags.joinToString(separator = ", ")}")
    }

    suspend fun downloadCodex(codexId: String) {
        val posts = data.codexRepository
            .observeCodexPosts(codexId, CodexSortMode.NEWEST_SAVED)
            .first()
        if (posts.isEmpty()) {
            toast("Codex has no posts to download")
            return
        }
        val name = data.codexRepository.observeCodices().first().firstOrNull { it.codexId == codexId }?.name
        transfers.downloads.submitPosts(posts, "Codex downloads", "Codex · ${name ?: "Collection"}")
        transfers.showDownloads = true
    }

    suspend fun shareCodex(codexId: String) {
        val export = workflows.codexTransfer.export(codexId)
        if (export !is CodexExportResult.Success) {
            toast(if (export is CodexExportResult.NotFound) "Codex not found" else "Could not export codex")
            return
        }
        val payload = export.payload
        val exportsDirectory = data.storageDirectory.resolve("exports")
        runCatchingPreservingCancellation {
            val exportFile = writeCodexDocument(exportsDirectory, payload)
            val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", exportFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, payload.title)
                clipData = ClipData.newRawUri("codex_export", contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share codex").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        }.onFailure {
            toast("Could not export codex")
        }
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        sources.accounts.refreshAvailability()
        val currentSettings = data.currentSettingsSnapshot()
        workflows.likesCodexSync.ensureProfileCodex(currentSettings.activeRecommendationProfile())
        owners.settings.onAction(SettingsAction.RefreshAccounts)
        navigation.homeTabRoute = data.uiRestoreRepository.migrateLegacyLastTab(currentSettings.lastSelectedTabRoute)
            ?.takeIf { route -> TopLevelDestination.entries.any { destination -> destination.route == route } }
            ?: TopLevelDestination.Search.route
        appShellOwner.onAction(AppShellAction.MarkAppReady)
        navReady = true
    }

    /** Opens a shared link as a creator profile or a one-post Viewer session, or says why it cannot. */
    suspend fun openExternalContent(uri: Uri) {
        val customSites = container.customSites.sites.value
        val deepLink = parseExternalPostDeepLink(uri) ?: parseCustomSitePostDeepLink(uri.toString(), customSites)
        if (deepLink != null) {
            openExternalPost(deepLink)
            return
        }
        val creatorDeepLink = parseExternalCreatorDeepLink(uri)
        if (creatorDeepLink == null) {
            // A post on a booru that isn't added yet: offer the Sites setup with its address filled in.
            val unaddedHost = unaddedBooruPostHost(uri.toString(), customSites)
            if (unaddedHost != null) {
                container.customSites.requestSetup(unaddedHost)
                owners.settings.onAction(SettingsAction.OpenCustomSites)
            } else {
                toast("Unsupported URL format")
            }
            return
        }
        val creator = creatorOpening.fromDeepLink(creatorDeepLink, sources.availableSources.value)
        if (creator == null) {
            toast("Could not open ${creatorDeepLink.sourceLabel} creator URL")
        } else {
            openCreatorProfile(creator)
        }
    }

    private suspend fun openExternalPost(deepLink: ExternalPostDeepLink) {
        val adapter = sources.registry.adapterFor(deepLink.source)
        if (adapter == null) {
            toast("${deepLink.sourceLabel} source is unavailable")
            return
        }
        val resolved = try {
            adapter.resolvePost(PostId(source = deepLink.source, sourcePostId = deepLink.postId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            toast(error.message ?: "Could not open ${deepLink.sourceLabel} URL")
            return
        }
        if (resolved == null) {
            toast("${deepLink.sourceLabel} post was not found")
            return
        }
        val context = ViewerLaunchContext(
            queryHash = "${deepLink.source.id.lowercase()}-deeplink:${deepLink.postId}",
            startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 0,
        )
        viewerSessions.retain(
            ViewerSession(
                posts = listOf(resolved),
                context = context,
                liveSearchBinding = false,
                initialMediaIndex = deepLink.initialMediaIndex,
            ),
        )
        data.uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer) {
            launchSingleTop = true
        }
    }

    fun openHistoricalSearch(entry: RecentSearchEntry) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val historicalScope = when (entry.kind) {
                RecentSearchKind.MULTI_SEARCH -> SearchSourceScope.fromSources(entry.sources)
                RecentSearchKind.SOURCE,
                RecentSearchKind.UNIFIED,
                RecentSearchKind.FYP,
                -> SearchSourceScope.fromQuery(entry.query)
            }
            routes.dispatchOrQueue(SearchAction.ApplyHistoricalQuery(query = entry.query, sourceScope = historicalScope))
            navigation.pendingTopLevelRoute = TopLevelDestination.Search.route
            navigation.homeTabRoute = TopLevelDestination.Search.route
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            navigation.showTab(TopLevelDestination.Search)
        }
    }

    fun openHistoricalForYouSearch(entry: RecentSearchEntry) {
        routes.dispatchOrQueue(ForYouAction.ReplaySearch(seedBySource = entry.fypSeedBySource(), sortMode = entry.query.sort))
        navigation.homeTabRoute = TopLevelDestination.ForYou.route
        scope.launch { navigation.showTab(TopLevelDestination.ForYou) }
    }

    /** Opens a finished download in a fresh Viewer session at the page that was saved. */
    suspend fun openDownloadedPost(item: PostDownloadItem) {
        val resolved = runCatchingPreservingCancellation {
            viewerRouteWorkflow.resolvePost(item.request.post.id, ViewerStreamSource.RECENTS)
        }.getOrNull()
        if (resolved == null) {
            snackbarHostState.showSnackbar("This post could not be opened. Try again.")
            return
        }
        if (navigation.currentRoute == AppRoute.Viewer) {
            activeViewerOwner?.clearSession()
            viewerSessions.clear()
            navController.popBackStack(AppRoute.Viewer, inclusive = true)
        }
        val context = ViewerLaunchContext("downloads:${item.id}", 0, ViewerStreamSource.RECENTS, 0)
        viewerSessions.retain(
            ViewerSession(
                posts = listOf(resolved),
                context = context,
                liveSearchBinding = false,
                initialMediaIndex = item.request.selectedMedia?.pageIndex ?: 0,
            ),
        )
        data.uiRestoreRepository.setViewerLaunchContext(context)
        navController.navigate(AppRoute.Viewer)
    }

    /** Opens the Viewer from a Codex; resumes at the saved position when this post has one. */
    fun openCodexViewer(codexId: String, visiblePosts: List<Post>, index: Int) {
        openStaticViewer(visiblePosts, "codex:$codexId", index, ViewerStreamSource.CODEX, persistContext = true)
    }

    /** Opens a related shelf no feed owner pages, such as a live Codex's; like other related streams it is not restored. */
    fun openRelatedViewer(seed: Post, shelfPosts: List<Post>, index: Int) {
        val queryHash = "related:${seed.id.source.id}:${seed.id.sourcePostId}"
        openStaticViewer(shelfPosts, queryHash, index, ViewerStreamSource.RELATED, persistContext = false)
    }

    private fun openStaticViewer(
        visiblePosts: List<Post>,
        queryHash: String,
        index: Int,
        streamSource: ViewerStreamSource,
        persistContext: Boolean,
    ) {
        val context = ViewerLaunchContext(
            queryHash = queryHash,
            startIndex = index,
            streamSource = streamSource,
            scrollOffsetHint = 0,
        )
        scope.launch {
            viewerSessions.retain(
                viewerRouteWorkflow.prepareResumableSession(
                    ViewerSession(posts = visiblePosts, context = context, liveSearchBinding = false),
                ),
            )
            if (persistContext) data.uiRestoreRepository.setViewerLaunchContext(context)
            navController.navigate(AppRoute.Viewer)
        }
    }
}

/** Creates the shell once per container; ViewModels resolve against the Activity's store here. */
@Composable
internal fun rememberTheoriaShell(
    container: TheoriaAppContainer,
    viewerSessions: ViewerSessionRetentionViewModel,
    appShellOwner: AppShellViewModel,
): TheoriaShell {
    val navController = rememberNavController()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val registry = rememberFeedFabRestoreRegistry(container.data.uiRestoreRepository)
    val snackbarHostState = remember { SnackbarHostState() }
    val transfers = rememberLibraryTransferTools(container) { snackbarHostState.showSnackbar(it) }
    val owners = rememberShellOwners(container, appContext)
    val codexImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) owners.codexImports.start(uri.toString())
    }
    val savedSearches = owners.savedSearches.savedSearches.collectAsStateWithLifecycle()
    val pendingLikePostIds = owners.postMutations.pendingLikePostIds.collectAsStateWithLifecycle()
    val homeTabRoute = rememberSaveable { mutableStateOf(TopLevelDestination.Search.route) }
    val pendingCreatorProfile = rememberSaveable(stateSaver = CreatorProfileStateSaver) { mutableStateOf<CreatorProfile?>(null) }
    val thumbnailCacheGeneration = rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { TopLevelDestination.entries.size })
    val routes = remember { RouteOwners() }
    val pendingSave = remember { PendingCodexSave() }
    val overlays = remember { ShellOverlays() }
    val navigation = remember(navController, pagerState) { HomeNavigation(navController, homeTabRoute, pagerState) }
    return remember(container, viewerSessions, appShellOwner, appContext, scope, registry, transfers, owners, codexImportLauncher, navigation) {
        TheoriaShell(
            container, viewerSessions, appShellOwner, appContext, scope, snackbarHostState, registry, transfers, owners,
            codexImportLauncher, routes, navigation, pendingSave, overlays, savedSearches, pendingLikePostIds, pendingCreatorProfile,
            thumbnailCacheGeneration,
        )
    }
}
