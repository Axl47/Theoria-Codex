package com.theoriacodex.app.series

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
import com.theoriacodex.app.ui.TheoriaShell
import com.theoriacodex.app.ui.TopLevelDestination
import com.theoriacodex.app.ui.launchViewer
import com.theoriacodex.app.ui.tabShown
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [AppRoute.Series]'s arguments. */
internal val seriesArguments = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("address") { type = NavType.StringType },
)

/** Carries a genre chip tap from a series page to the Series tab's source-filtered search. */
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

/** The Series tab. */
@Composable
internal fun SeriesTabDestination(shell: TheoriaShell) {
    val series = shell.container.series ?: return
    val viewModel = viewModel {
        SeriesTabViewModel(series.repository, series.sources, series.extensions.revision, createSavedStateHandle()) { series.checkFollowed() }
    }
    val pager = shell.navigation.pagerState
    LaunchedEffect(viewModel, pager) {
        viewModel.followShown(snapshotFlow { tabShown(TopLevelDestination.Series, pager.targetPage, pager.settledPage) })
    }
    val genreRequests = LocalSeriesGenreSearch.current
    val pendingGenre by genreRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGenre) {
        pendingGenre?.let { request ->
            genreRequests.consume(request)
            viewModel.openGenreSearch(request.first, request.second)
        }
    }
    val settings by shell.data.settingsRepository.observeSettings().collectAsStateWithLifecycle(initialValue = null)
    SeriesTabScreen(
        viewModel,
        onOpenSeries = { id -> shell.navController.navigate(AppRoute.series(id)) },
        onOpenExtensions = shell::openExtensions,
        oldSearchBar = settings?.oldSearchBar == true,
    )
}

/** The Extensions page, pushed from the Series tab and from Settings. */
@Composable
internal fun ExtensionsDestination(shell: TheoriaShell) {
    val series = shell.container.series ?: return
    val viewModel = viewModel { ExtensionsViewModel(series.extensions, series.repos) }
    ExtensionsScreen(viewModel, onBack = { shell.navController.popBackStack() })
}

internal fun TheoriaShell.openExtensions() {
    navController.navigate(AppRoute.Extensions) { launchSingleTop = true }
}

/** One series' page. A chapter opens in the Viewer as a series session, which the saved launch context never names. */
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
    SeriesPageScreen(
        viewModel = viewModel,
        onBack = { shell.navController.popBackStack() },
        onOpenExtensions = shell::openExtensions,
        switchSource = switchSource,
        onOpenSeries = { opened -> shell.navController.navigate(AppRoute.series(opened)) },
        // The old series is gone, so its page leaves the back stack; only while it is still the page on screen.
        onSwitched = { moved ->
            if (shell.navController.currentBackStackEntry == entry) {
                shell.navController.navigate(AppRoute.series(moved)) { popUpTo(entry.destination.id) { inclusive = true } }
            }
        },
        onOpenEntry = { address ->
            shell.scope.launch {
                shell.seriesReader?.session(id, address)?.let { session -> shell.launchViewer(session, saveContext = false, singleTop = true) }
            }
        },
        onSearchGenre = { genre ->
            genreRequests.request(id.source, genre)
            shell.navigation.goToTab(TopLevelDestination.Series)
        },
    )
}
