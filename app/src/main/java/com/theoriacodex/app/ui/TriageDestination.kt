package com.theoriacodex.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.theoriacodex.app.ui.navigation.TriageOpener
import com.theoriacodex.app.ui.navigation.TriageRequest
import com.theoriacodex.data.repository.ViewerStreamSource

/** [AppRoute.Triage]'s arguments. */
internal val triageArguments: List<NamedNavArgument> = listOf(
    navArgument("source") { type = NavType.StringType },
    navArgument("feedKey") { type = NavType.StringType; defaultValue = "" },
)

/** The [TriageRequest] this back stack entry was opened with; an unknown source reads as Search. */
internal fun NavBackStackEntry.triageRequest(): TriageRequest {
    val source = arguments?.getString("source")
    return TriageRequest(
        source = ViewerStreamSource.entries.firstOrNull { it.name == source } ?: ViewerStreamSource.SEARCH,
        feedKey = arguments?.getString("feedKey").orEmpty(),
    )
}

/** Opens the triage deck by pushing [AppRoute.Triage]. */
@Composable
internal fun rememberTriageOpener(shell: TheoriaShell): TriageOpener = remember(shell) {
    TriageOpener { request -> shell.navController.navigate(AppRoute.triage(request)) }
}

/** The triage deck over [request]'s feed. Not built yet (stream T): it closes as soon as it opens. */
@Composable
internal fun TriageDestination(shell: TheoriaShell, request: TriageRequest) {
    LaunchedEffect(request) { shell.navController.popBackStack() }
    Box(Modifier.fillMaxSize())
}
