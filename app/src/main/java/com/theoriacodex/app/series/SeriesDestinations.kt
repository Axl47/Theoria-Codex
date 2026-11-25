package com.theoriacodex.app.series

import com.theoriacodex.app.ui.LocalTabShown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.theoriacodex.app.ui.AppRoute
import com.theoriacodex.app.ui.ReportLaunchContent
import com.theoriacodex.app.ui.TheoriaShell
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.launchViewer
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val extensionsArguments = listOf(
    navArgument("page") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

internal val seriesArguments = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("address") { type = NavType.StringType },
    navArgument("entry") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

private const val ROUTE_ENTRY_OPENED = "routeEntryOpened"

internal class SeriesGenreRequests {
    private val mutablePending = MutableStateFlow<Pair<SourceKey, String>?>(null)
    val pending: StateFlow<Pair<SourceKey, String>?> = mutablePending.asStateFlow()

    fun request(source: SourceKey, genre: String) {
        mutablePending.value = source to genre
    }

    fun consume(request: Pair<SourceKey, String>) {
        if (mutablePending.value == request) mutablePending.value = null
    }
}

internal val LocalSeriesGenreSearch = staticCompositionLocalOf { SeriesGenreRequests() }

@Composable
internal fun SeriesTabDestination(shell: TheoriaShell) {
    val series = shell.container.series
    if (series == null) return ReportLaunchContent(TopLevelDestination.Series)
    val viewModel = viewModel {
        SeriesTabViewModel(
            series.repository,
            series.sources,
            series.extensions.revision,
            createSavedStateHandle(),
            SharedPreferencesSeriesLibraryPreferences(shell.appContext),
            series.downloads.downloadedCounts(),
        ) { only, onProgress -> series.checkFollowed(only = only, onProgress = onProgress) { notifySeriesUpdate(shell.appContext, it) } }
    }
    val tabShown = LocalTabShown.current
    LaunchedEffect(viewModel, tabShown) { viewModel.followShown(snapshotFlow(tabShown)) }
    val genreRequests = LocalSeriesGenreSearch.current
    val pendingGenre by genreRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGenre) {
        pendingGenre?.let { request ->
            genreRequests.consume(request)
            viewModel.openGenreSearch(request.first, request.second)
        }
    }
    val settings by shell.data.settingsRepository.observeSettings().collectAsStateWithLifecycle(initialValue = null)
    val content by viewModel.content.collectAsStateWithLifecycle()
    ReportLaunchContent(TopLevelDestination.Series, drawn = content.firstContentDrawn)
    SeriesTabScreen(
        viewModel,
        onOpenSeries = { id -> shell.navController.navigate(AppRoute.series(id)) },
        onOpenExtensions = shell::openExtensions,
        oldSearchBar = settings?.oldSearchBar == true,
    )
}

@Composable
internal fun ExtensionsDestination(shell: TheoriaShell, entry: NavBackStackEntry) {
    val series = shell.container.series ?: return
    val page = entry.arguments?.getString("page")?.let { name -> ExtensionsSubpage.entries.firstOrNull { it.name == name } }
    val viewModel = viewModel { ExtensionsViewModel(series.extensions, series.repos, page) }
    val missing = remember(series) {
        MissingExtensionsActions(
            missing = { MissingSeriesViewModel(series.repository, series.sources, series.extensions.revision) },
            switchSource = { id ->
                SwitchSourceViewModel(id, series.sources, series.extensions.revision, store = { series.repository.store(it) }) { source, to ->
                    series.switchSource(id, source, to)
                }
            },
            onOpenSeries = { id -> shell.navController.navigate(AppRoute.series(id)) },
        )
    }
    ExtensionsScreen(viewModel, onBack = { shell.navController.popBackStack() }, missing = missing)
}

internal fun TheoriaShell.openExtensions(page: ExtensionsSubpage? = null) {
    navController.navigate(AppRoute.extensions(page)) { launchSingleTop = true }
}

@Composable
internal fun SeriesPageDestination(shell: TheoriaShell, entry: NavBackStackEntry) {
    val series = shell.container.series ?: return
    val source = entry.arguments?.getString("source")?.let(SourceKey::parse) ?: return
    val id = SeriesId(source, entry.arguments?.getString("address") ?: return)
    val chapterPreferences = remember(shell.appContext) { SharedPreferencesSeriesChapterPreferences(shell.appContext) }
    val genreRequests = LocalSeriesGenreSearch.current
    val viewModel = viewModel {
        SeriesPageViewModel(
            id = id,
            repository = series.repository,
            loadSources = series.sources,
            sourcesChanged = series.extensions.revision,
            downloads = series.downloads,
            chapterPreferences = chapterPreferences,
        )
    }
    val switchSource = viewModel {
        SwitchSourceViewModel(id, series.sources, series.extensions.revision, store = { series.repository.store(it) }) { source, to ->
            viewModel.whileMoving { series.switchSource(id, source, to) }
        }
    }
    val openEntry: suspend (String) -> Unit = { address ->
        shell.seriesReader?.session(id, address)?.let { session -> shell.launchViewer(session, saveContext = false, singleTop = true) }
    }
    LaunchedEffect(entry) {
        val routed = entry.arguments?.getString("entry") ?: return@LaunchedEffect
        if (entry.savedStateHandle.get<Boolean>(ROUTE_ENTRY_OPENED) == true) return@LaunchedEffect
        entry.savedStateHandle[ROUTE_ENTRY_OPENED] = true
        openEntry(routed)
    }
    SeriesPageScreen(
        viewModel = viewModel,
        onBack = { shell.navController.popBackStack() },
        onOpenExtensions = shell::openExtensions,
        switchSource = switchSource,
        onOpenSeries = { opened -> shell.navController.navigate(AppRoute.series(opened)) },
        onSwitched = { moved ->
            if (shell.navController.currentBackStackEntry == entry) {
                shell.navController.navigate(AppRoute.series(moved)) { popUpTo(entry.destination.id) { inclusive = true } }
            }
        },
        onOpenEntry = { address -> shell.scope.launch { openEntry(address) } },
        onSearchGenre = { genre ->
            genreRequests.request(id.source, genre)
            shell.navigation.goToTab(TopLevelDestination.Series)
        },
    )
}

private val SeriesTabContent.firstContentDrawn: Boolean
    get() {
        val sources = sources
        val search = search
        return when {
            view == SeriesView.FOLLOWING -> following != null
            sources == null -> false
            search != null -> search.firstOrNull()?.listing !is SeriesListing.Loading
            else -> browse?.let { it.listing !is SeriesListing.Loading } ?: sources.isEmpty()
        }
    }
