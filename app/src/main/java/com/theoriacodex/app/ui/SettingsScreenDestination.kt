package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.theoriacodex.app.settings.StatsActions
import com.theoriacodex.app.settings.sites.rememberCustomSitesEntry
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary

/** Settings: today the Settings tab, and the pushed [AppRoute.Settings] screen that replaces it. */
@Composable
internal fun SettingsScreenDestination(shell: TheoriaShell) {
    SettingsDestinationStateBoundary(
        shell.owners.settings, shell.routes.search.current, libraryStorageSummary(shell.transfers),
        sites = rememberCustomSitesEntry(shell.container),
        statsActions = remember(shell) {
            StatsActions(shell::openStatisticsPost, shell::shareStats, shell::readStatsFile, shell::previewStatisticsPost, shell::downloadStats)
        },
        diagnostics = shell.diagnosticsEntry,
    ) {
        LibraryTransferSettings(shell.transfers)
    }
}
