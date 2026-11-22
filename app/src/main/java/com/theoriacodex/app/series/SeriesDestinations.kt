package com.theoriacodex.app.series

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.theoriacodex.app.ui.AppRoute
import com.theoriacodex.app.ui.TheoriaShell
import com.theoriacodex.app.ui.launchViewer
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.launch

/** [AppRoute.Series]'s arguments. */
internal val seriesArguments = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("address") { type = NavType.StringType },
)

/** The Series tab; the pager shows it only in a build with extensions. */
@Composable
internal fun SeriesTabDestination(shell: TheoriaShell) {
    val series = shell.container.series ?: return
    val viewModel = viewModel { SeriesTabViewModel(series.repository, series.sources, series.extensions.revision) { series.checkFollowed() } }
    SeriesTabScreen(
        viewModel,
        onOpenSeries = { id -> shell.navController.navigate(AppRoute.series(id)) },
        onOpenExtensions = shell::openExtensions,
    )
}

/** The Extensions page, pushed from the Series tab and from Settings. */
@Composable
internal fun ExtensionsDestination(shell: TheoriaShell) {
    val series = shell.container.series ?: return
    val viewModel = viewModel { ExtensionsViewModel(series.extensions) }
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
    val viewModel = viewModel { SeriesPageViewModel(id, series.repository, series.sources) }
    SeriesPageScreen(
        viewModel = viewModel,
        onBack = { shell.navController.popBackStack() },
        onOpenEntry = { address ->
            shell.scope.launch {
                shell.seriesReader?.session(id, address)?.let { session -> shell.launchViewer(session, saveContext = false, singleTop = true) }
            }
        },
    )
}
