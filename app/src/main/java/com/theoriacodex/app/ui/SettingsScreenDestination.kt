package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsPage
import com.theoriacodex.app.settings.SettingsSheet
import com.theoriacodex.app.settings.StatsActions
import com.theoriacodex.app.settings.rememberSettingsBackupEntry
import com.theoriacodex.app.settings.sites.rememberCustomSitesEntry
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The pushed [AppRoute.Settings] screen. It remembers the page it was opened at, so Back from that page
 * returns to the screen that opened it (Search's "Sites" jump, for example) instead of Settings' home.
 */
@Composable
internal fun SettingsScreenDestination(shell: TheoriaShell) {
    val owner = shell.owners.settings
    val entryPage = rememberSaveable { owner.state.value.page }
    LaunchedEffect(owner) { owner.onAction(SettingsAction.SettingsEntered) }
    SettingsDestinationStateBoundary(
        owner, libraryStorageSummary(shell.transfers),
        sites = rememberCustomSitesEntry(shell.container),
        backup = rememberSettingsBackupEntry(shell.transfers),
        statsActions = remember(shell) {
            StatsActions(shell::openStatisticsPost, shell::shareStats, shell::readStatsFile, shell::previewStatisticsPost, shell::downloadStats)
        },
        diagnostics = shell.diagnosticsEntry,
        storageTools = { LibraryTransferSettings(shell.transfers) },
        onClose = { shell.navController.popBackStack() },
        closesFrom = entryPage,
    )
}

/**
 * Opens Settings at [page], with [sheet] over its home, above the current screen; a jump during startup
 * waits for it to finish.
 */
internal fun TheoriaShell.openSettings(page: SettingsPage = SettingsPage.HOME, sheet: SettingsSheet? = null) {
    owners.settings.onAction(SettingsAction.OpenPage(page, sheet))
    showSettingsRoute()
}

/** Pushes the Settings route once startup is done, so restoring the last tab cannot cover it. */
internal fun TheoriaShell.showSettingsRoute() {
    scope.launch {
        snapshotFlow { navReady }.first { ready -> ready }
        if (navigation.currentRoute != AppRoute.Settings) navController.navigate(AppRoute.Settings)
    }
}
