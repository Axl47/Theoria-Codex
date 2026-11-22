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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.theoriacodex.app.appshell.AppShellAction
import com.theoriacodex.app.appshell.AppShellViewModel
import com.theoriacodex.app.appshell.ViewerSessionRetentionViewModel
import com.theoriacodex.app.codex.SavedSearchOpenMarks
import com.theoriacodex.app.codex.CodexCommandViewModel
import com.theoriacodex.app.codex.CodexSaveViewModel
import com.theoriacodex.app.codex.FOLLOWED_CODEX_ID
import com.theoriacodex.app.codex.SAVED_SEARCHES_CODEX_ID
import com.theoriacodex.app.codex.CodexChosen
import com.theoriacodex.app.codex.profileScopedCodexId
import com.theoriacodex.app.codex.transfer.CodexExportResult
import com.theoriacodex.app.codex.transfer.CodexImportViewModel
import com.theoriacodex.app.codex.CODEX_SHARE_MAX_BYTES
import com.theoriacodex.app.codex.transfer.ExportDocument
import com.theoriacodex.app.codex.transfer.readBoundedTextDocument
import com.theoriacodex.app.codex.transfer.saveExportToDownloads
import com.theoriacodex.app.codex.transfer.writeExportDocument
import com.theoriacodex.app.creator.CreatorFollowsViewModel
import com.theoriacodex.app.creator.CreatorProfileViewModel
import com.theoriacodex.app.creator.CreatorOpeningWorkflow
import com.theoriacodex.app.source.FileCopyLookup
import com.theoriacodex.app.di.TheoriaAppContainer
import com.theoriacodex.app.media.PostDownloadItem
import com.theoriacodex.app.post.PostMutationViewModel
import com.theoriacodex.app.recents.RecentsClearWorkflow
import com.theoriacodex.app.recents.SavedSearchViewModel
import com.theoriacodex.app.recommend.ForYouDeckViewModel
import com.theoriacodex.app.related.LikeToggleOutcome
import com.theoriacodex.app.related.ViewerFeedOwner
import com.theoriacodex.app.search.SearchViewModel
import com.theoriacodex.app.search.state.SearchAction
import com.theoriacodex.app.search.state.SearchSourceScope
import com.theoriacodex.app.codex.SearchSaveMemory
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsViewModel
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.source.parseExternalCreatorDeepLink
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.app.settings.SettingsDiagnosticsEntry
import com.theoriacodex.app.statistics.STATS_SHARE_MAX_BYTES
import com.theoriacodex.app.statistics.StatsShareFile
import com.theoriacodex.app.statistics.encodeStatsShare
import com.theoriacodex.app.statistics.parseStatsShare
import com.theoriacodex.app.statistics.statisticsPostKey
import com.theoriacodex.app.source.SharedPostFix
import com.theoriacodex.app.source.parseCustomSitePostDeepLink
import com.theoriacodex.app.source.sharedPostFix
import com.theoriacodex.app.source.parseExternalPostDeepLink
import com.theoriacodex.app.source.unaddedBooruPostHost
import com.theoriacodex.app.source.ExternalPostDeepLink
import com.theoriacodex.app.ui.components.FeedFabRestoreRegistry
import com.theoriacodex.app.ui.components.rememberFeedFabRestoreRegistry
import com.theoriacodex.app.ui.routes.CreatorProfileStateSaver
import com.theoriacodex.app.ui.routes.PendingRouteActions
import com.theoriacodex.app.ui.routes.RouteOwnerSlot
import com.theoriacodex.app.series.SeriesReader
import com.theoriacodex.app.ui.routes.ViewerRouteWorkflow
import com.theoriacodex.app.ui.routes.activeRecommendationProfile
import com.theoriacodex.app.update.ReleaseChangelogEntry
import com.theoriacodex.app.viewer.ViewerSession
import com.theoriacodex.app.viewer.ViewerViewModel
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.RecentSearchEntry
import com.theoriacodex.data.repository.RecentSearchKind
import com.theoriacodex.data.repository.SavedSearchEntry
import com.theoriacodex.data.repository.ViewerLaunchContext
import com.theoriacodex.data.repository.ViewerStreamSource
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.app.tags.PostTagSearchActions
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.Query
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The navigation-owned ViewModels the shell may reach, and actions waiting for Search to exist.
 *
 * The slots outlive individual destination compositions: Viewer can keep paging through a still-live
 * back-stack owner, and only that owner's clear empties its slot. Hold one instance for the shell's
 * whole lifetime and close it only when the shell leaves composition.
 */
@Stable
internal class RouteOwners {
    val search = RouteOwnerSlot<SearchViewModel>()
    val forYou = RouteOwnerSlot<ForYouDeckViewModel>()
    val creator = RouteOwnerSlot<CreatorProfileViewModel>()
    /** The Viewer while its destination is composed. */
    val viewer = RouteOwnerSlot<ViewerViewModel>()
    private val pendingSearchActions = PendingRouteActions<SearchAction>()

    /** The live feeds a Viewer session can follow. */
    val feeds: List<ViewerFeedOwner>
        get() = listOfNotNull(search.current, forYou.current, creator.current)

    /** The live feed that launches Viewer streams from [source], if any. */
    fun feed(source: ViewerStreamSource): ViewerFeedOwner? = feeds.firstOrNull { feed -> feed.streamSource == source }

    fun close() {
        search.clear()
        forYou.clear()
        creator.clear()
        viewer.clear()
    }

    fun publishSearch(owner: SearchViewModel) {
        search.publish(owner)
        pendingSearchActions.flush { action -> owner.onAction(action); true }
    }

    fun publishForYou(owner: ForYouDeckViewModel) = forYou.publish(owner)

    fun dispatchOrQueue(action: SearchAction): Boolean =
        pendingSearchActions.dispatchOrEnqueue(action) { queued -> search.current?.onAction(queued) != null }

    val postTagSearchActions = PostTagSearchActions(
        addInclude = { post, term -> dispatchOrQueue(SearchAction.AddPostIncludeTerm(post, term)) },
        addExclude = { post, term -> dispatchOrQueue(SearchAction.AddPostExcludeTerm(post, term)) },
        removeInclude = { _, term -> dispatchOrQueue(SearchAction.RemoveIncludeTerm(term)) },
        removeExclude = { _, term -> dispatchOrQueue(SearchAction.RemoveExcludeTerm(term)) },
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
    private val scope: CoroutineScope,
) {
    var homeTabRoute by homeTabRoute

    val currentRoute: String?
        get() = navController.currentBackStackEntry?.destination?.route

    /**
     * Selects a tab without a page animation, which is how every cross-tab jump arrives. With another
     * screen on top, the pager moves there when Home returns ([HomeTabEffects] scrolls to [homeTabRoute]).
     */
    fun showTab(destination: TopLevelDestination) {
        homeTabRoute = destination.route
        if (currentRoute == AppRoute.Home) scope.launch { pagerState.scrollToRoute(destination.route) }
    }

    /** Returns to Home from any screen and selects [destination]'s tab. */
    fun goToTab(destination: TopLevelDestination) {
        if (currentRoute != AppRoute.Home && !navController.popBackStack(AppRoute.Home, inclusive = false)) {
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
        showTab(destination)
    }
}

/** Posts on their way into a Codex, and what the save sheet should offer for them. */
@Stable
internal class PendingCodexSave {
    /** Save targets per search this session, so the picker suggests where this search's posts went. */
    val searchSaves = SearchSaveMemory()

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

    /**
     * Set by a requester that saves the picked Codex itself, as the lift and the triage deck do so their Undo covers
     * the save; the sheet then hands over the Codex picked, or the new one named, instead of saving.
     */
    var onPick by mutableStateOf<CodexChosen?>(null)
        private set

    fun request(
        posts: List<Post>,
        fromForYou: Boolean = false,
        excludedCodexId: String? = null,
        searchQuery: Query? = null,
        onPick: CodexChosen? = null,
    ) {
        val uniquePosts = posts.distinctBy(Post::id)
        if (uniquePosts.isEmpty()) return
        this.posts = uniquePosts
        query = searchQuery
        this.fromForYou = fromForYou
        excludedCodexIds = setOfNotNull(excludedCodexId)
        this.onPick = onPick
        visible = true
    }

    fun clear() {
        visible = false
        posts = emptyList()
        query = null
        fromForYou = false
        excludedCodexIds = emptySet()
        onPick = null
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
    val savedSearches = viewModel(key = "saved-searches") { SavedSearchViewModel(data.savedSearches) }
    val codexCommands = viewModel(key = "codex-command-owner") {
        CodexCommandViewModel(
            data.codexRepository, workflows.likesCodexSync::clearProfile, workflows.likesCodexSync::restoreClearedLikes,
            workflows.codexTransfer::reorderVisibleCodices, workflows.codexCleaning, removalScope = container.applicationScope,
        ) { codexId -> container.features.offlineMedia.remove(com.theoriacodex.app.media.codexOfflineOwner(codexId)) }
    }
    val postMutations = viewModel(key = "post-mutations") {
        PostMutationViewModel(data.settingsRepository, workflows.likesCodexSync::toggle)
    }
    val codexSaves = viewModel(key = "codex-save-owner") {
        CodexSaveViewModel(workflows.codexTransfer, data.codexRepository, data.statisticsRepository)
    }
    val settings = viewModel<SettingsViewModel>(key = "settings-route-owner", factory = settingsViewModelFactory(container))
    val follows = viewModel<CreatorFollowsViewModel>(
        key = "creator-follows",
        factory = CreatorFollowsViewModel.factory(data.settingsRepository, container.sources.registry),
    )
    val codexImports = viewModel(key = "codex-import-owner") {
        CodexImportViewModel(
            transfer = workflows.codexTransfer,
            readDocument = { document ->
                readBoundedTextDocument(CODEX_SHARE_MAX_BYTES) { appContext.contentResolver.openInputStream(document.toUri()) }
            },
            newTargetCodexId = { profileScopedCodexId(data.currentActiveRecommendationProfile().profileId) },
        )
    }
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
    var navReady by mutableStateOf(appShellOwner.state.value.appReady)
    val creatorOpening = CreatorOpeningWorkflow(sources.registry, sources.httpClient)
    private val fileCopies = FileCopyLookup(sources.registry)
    val savedSearchOpens = SavedSearchOpenMarks(data.uiRestoreRepository)
    val recentsClearWorkflow = RecentsClearWorkflow(data.recentsRepository)
    /** The tabs this build shows; Series only with extensions. */
    val homeTabs = homeTabs(container.series != null)

    /** Reads series chapters in the Viewer; null in a build without extensions. */
    val seriesReader = container.series?.let(::SeriesReader)
    val viewerRouteWorkflow = ViewerRouteWorkflow(
        data = data,
        cacheScope = scope,
        sources = sources,
        feedOwner = routes::feed,
        offlineMedia = features.offlineMedia,
        series = seriesReader,
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

    fun requestSaveToCodex(post: Post, fromForYou: Boolean = false, searchQuery: Query? = null) {
        pendingSave.request(listOf(post), fromForYou, searchQuery = searchQuery)
    }

    fun requestSaveToDevice(post: Post) {
        val origin = if (navigation.currentRoute == AppRoute.CreatorProfile) {
            "Creator profile"
        } else when (navigation.homeTabRoute) {
            TopLevelDestination.ForYou.route -> "For You"
            TopLevelDestination.Library.route -> "Library"
            else -> "Search"
        }
        transfers.downloads.submitPosts(listOf(post), originLabel = origin)
        transfers.showDownloads = true
    }

    /**
     * Opens [creator] above the current screen, so Back returns to it. A Viewer stays open underneath, except one
     * browsing a creator page's feed: that feed lives in the single creator owner the new page takes over, so it closes.
     */
    suspend fun openCreatorProfile(creator: CreatorProfile) {
        pendingCreatorProfile = creator
        val viewerSession = routes.viewer.current?.session?.value
        val viewerUsesCreatorFeed = viewerSession != null && ViewerStreamSource.CREATOR_PROFILE in
            listOf(viewerSession.context.streamSource, viewerSession.relatedBinding?.source)
        if (navigation.currentRoute == AppRoute.Viewer && viewerUsesCreatorFeed) {
            routes.viewer.current?.clearSession(retainPresentation = true)
            viewerSessions.clear()
            features.search.setViewerLaunchContext(null)
            navController.popBackStack(AppRoute.Viewer, inclusive = true)
        }
        navController.navigate(AppRoute.CreatorProfile) {
            // A creator opened from a creator page replaces it; its entry remembers the creator it started with.
            if (navigation.currentRoute == AppRoute.CreatorProfile) popUpTo(AppRoute.CreatorProfile) { inclusive = true }
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

    /**
     * A like action bound to the feed behind [source] and the query it shows now: a committed like opens or clears
     * that feed's related shelf, unless the feed has since moved to another query or owner.
     */
    fun feedLikeAction(source: ViewerStreamSource): (Post) -> Unit {
        val owner = routes.feed(source)
        val queryHash = owner?.viewerFeed?.queryHash
        return { post ->
            toggleLikeAndSyncCodex(post) { outcome ->
                if (owner != null && routes.feed(source) === owner && owner.viewerFeed.queryHash == queryHash) {
                    owner.related.onLikeCommitted(post, outcome)
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
        navigation.goToTab(TopLevelDestination.Search)
        val tags = query.effectiveIncludeTermGroups.map { group -> group.terms.joinToString(" or ") { it.value } } +
            query.excludeTerms.map { "-${it.value}" }
        toast("Searching ${source.displayName()} Codex tags: ${tags.joinToString(separator = ", ")}")
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
            toast(if (export is CodexExportResult.NotFound) "Codex not found" else "Could not export Codex")
            return
        }
        if (!shareExportDocument(export.payload, "application/json", "Share Codex")) toast("Could not export Codex")
    }

    suspend fun shareStats(file: StatsShareFile) {
        if (!shareExportDocument(statsDocument(file), "application/json", "Share stats")) toast("Could not share stats")
    }

    suspend fun downloadStats(file: StatsShareFile) = downloadExportDocument(statsDocument(file), "application/json")

    private fun statsDocument(file: StatsShareFile) = ExportDocument("Theoria stats", "theoria-stats.json", encodeStatsShare(file))

    /** Reads a stats file someone sent, or explains why it can't be compared. */
    suspend fun readStatsFile(document: String): StatsShareFile? {
        val file = runCatchingPreservingCancellation {
            readBoundedTextDocument(STATS_SHARE_MAX_BYTES) { appContext.contentResolver.openInputStream(document.toUri()) }
                ?.let { raw -> withContext(Dispatchers.Default) { parseStatsShare(raw) } }
        }.onFailure { Diagnostics.report("stats-share", "Could not read a stats file", it) }.getOrNull()
        if (file == null) toast("That isn't a Theoria stats file")
        return file
    }

    private suspend fun shareDiagnosticsReport(report: String) {
        if (!shareExportDocument(diagnosticsDocument(report), "text/plain", "Share diagnostics")) toast("Could not share the report")
    }

    private fun diagnosticsDocument(report: String) = ExportDocument("Theoria diagnostics", "theoria-diagnostics.txt", report)

    private suspend fun downloadExportDocument(document: ExportDocument, mimeType: String) {
        runCatchingPreservingCancellation { saveExportToDownloads(appContext.contentResolver, document, mimeType) }
            .onSuccess { toast("Saved to Downloads/TheoriaCodex") }
            .onFailure {
                Diagnostics.report("share", "Could not save a file to Downloads", it)
                toast("Could not save the file")
            }
    }

    private suspend fun shareExportDocument(document: ExportDocument, mimeType: String, chooserTitle: String): Boolean {
        val exportsDirectory = data.storageDirectory.resolve("exports")
        return runCatchingPreservingCancellation {
            val exportFile = writeExportDocument(exportsDirectory, document)
            val contentUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", exportFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, document.title)
                clipData = ClipData.newRawUri(document.fileName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        }.onFailure { Diagnostics.report("share", "Could not open the share sheet", it) }.isSuccess
    }

    /** Absent when the host application keeps no diagnostics, as in fixture graphs without [TheoriaApplication]. */
    val diagnosticsEntry: SettingsDiagnosticsEntry? = (appContext as? TheoriaApplication)?.diagnosticReporter?.let { reporter ->
        SettingsDiagnosticsEntry(
            reporter::build, ::shareDiagnosticsReport,
            { report -> downloadExportDocument(diagnosticsDocument(report), "text/plain") }, reporter::clear,
        )
    }

    suspend fun completeAppStartup() {
        if (navReady) return
        sources.accounts.refreshAvailability()
        val currentSettings = data.currentSettingsSnapshot()
        workflows.likesCodexSync.ensureProfileCodex(currentSettings.activeRecommendationProfile())
        owners.settings.onAction(SettingsAction.RefreshAccounts)
        navigation.homeTabRoute = data.restoreHomeTabRoute()
        appShellOwner.onAction(AppShellAction.MarkAppReady)
        navReady = true
    }

    /**
     * Opens the page a booru post came from. A post on an active source replaces the open Viewer; anything else, or
     * a post that cannot be loaded, opens in the browser.
     */
    fun openOriginalPost(url: String) {
        val link = parseExternalPostDeepLink(url) ?: parseCustomSitePostDeepLink(url, container.customSites.sites.value)
        val adapter = link?.takeIf { it.source in sources.availableSources.value }
            ?.let { sources.registry.adapterFor(it.source) }
        if (link == null || adapter == null) {
            openInBrowser(appContext, url)
            return
        }
        scope.launch {
            val post = runCatchingPreservingCancellation {
                adapter.resolvePost(PostId(source = link.source, sourcePostId = link.postId))
            }.getOrNull()
            if (post == null) {
                openInBrowser(appContext, url)
            } else {
                openSinglePostViewer("${link.source.id.lowercase()}-deeplink:${link.postId}", link.initialMediaIndex) { post }
            }
        }
    }

    /** "Find on other sites" for [post], or null when it has no file hash or no other site can look one up. */
    fun findOnOtherSites(post: Post): (() -> Unit)? = if (!fileCopies.canFind(post)) null else ({
        scope.launch {
            val copies = fileCopies.find(post) { toast("Searching other sites…") }
            if (copies.isEmpty()) toast("Not on your other sites")
            else openViewerSession("file-copies:${post.id.source.id}:${post.id.sourcePostId}", copies)
        }
    })

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
                container.customSites.requestSetup(unaddedHost, linkAfterSetup = uri.toString())
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
            when (sharedPostFix(deepLink.source, error)) {
                SharedPostFix.ADD_KEY -> container.customSites.requestKeyEntry(deepLink.source.id)
                SharedPostFix.VERIFY -> container.customSites.requestVerification(deepLink.source.id)
                null -> return
            }
            owners.settings.onAction(SettingsAction.OpenCustomSites)
            return
        }
        if (resolved == null) {
            toast("${deepLink.sourceLabel} post was not found")
            return
        }
        data.fileMirrors.learn(listOf(resolved))
        val context = ViewerLaunchContext(
            queryHash = "${deepLink.source.id.lowercase()}-deeplink:${deepLink.postId}",
            startIndex = 0,
            streamSource = ViewerStreamSource.SEARCH,
            scrollOffsetHint = 0,
        )
        val session = ViewerSession(
            posts = listOf(resolved),
            context = context,
            liveSearchBinding = false,
            initialMediaIndices = mapOf(resolved.id to deepLink.initialMediaIndex),
            focusComment = deepLink.comment,
        )
        // Unlike other one-post launches, a shared link leaves an open Viewer underneath and reuses one on top.
        launchViewer(session, saveContext = true, singleTop = true)
    }

    fun openHistoricalSearch(entry: RecentSearchEntry) {
        val historicalScope = when (entry.kind) {
            RecentSearchKind.MULTI_SEARCH -> SearchSourceScope.fromSources(entry.sources)
            RecentSearchKind.SOURCE,
            RecentSearchKind.UNIFIED,
            -> SearchSourceScope.fromQuery(entry.query)
        }
        routes.dispatchOrQueue(SearchAction.ApplyHistoricalQuery(query = entry.query, sourceScope = historicalScope))
        navigation.goToTab(TopLevelDestination.Search)
    }

    /** Opens a finished download in a fresh Viewer session at the page that was saved. */
    suspend fun openDownloadedPost(item: PostDownloadItem) {
        openSinglePostViewer("downloads:${item.id}", item.request.selectedMedia?.pageIndex ?: 0) {
            viewerRouteWorkflow.resolvePost(item.request.post.id, ViewerStreamSource.RECENTS)
        }
    }

    /** Opens a ranked Stats post. A saved copy works offline; the source is only asked for posts no longer saved. */
    suspend fun openStatisticsPost(postId: PostId) {
        openSinglePostViewer("stats:${statisticsPostKey(postId)}") {
            data.codexRepository.getPost(postId) ?: viewerRouteWorkflow.resolvePost(postId, ViewerStreamSource.RECENTS)
        }
    }

    /** Never saves a looked-up post, so a friend's stats leave nothing behind in this library. */
    suspend fun previewStatisticsPost(postId: PostId): Post? = runCatchingPreservingCancellation {
        data.codexRepository.getPost(postId) ?: viewerRouteWorkflow.resolvePostWithoutSaving(postId)
    }.getOrNull()
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
    val pagerState = rememberPagerState(pageCount = { homeTabs(container.series != null).size })
    val routes = remember { RouteOwners() }
    val pendingSave = remember { PendingCodexSave() }
    val overlays = remember { ShellOverlays() }
    val navigation = remember(navController, pagerState) { HomeNavigation(navController, homeTabRoute, pagerState, scope) }
    return remember(container, viewerSessions, appShellOwner, appContext, scope, registry, transfers, owners, codexImportLauncher, navigation) {
        TheoriaShell(
            container, viewerSessions, appShellOwner, appContext, scope, snackbarHostState, registry, transfers, owners,
            codexImportLauncher, routes, navigation, pendingSave, overlays, savedSearches, pendingLikePostIds, pendingCreatorProfile,
        )
    }
}
