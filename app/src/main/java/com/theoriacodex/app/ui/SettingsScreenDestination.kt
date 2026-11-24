package com.theoriacodex.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.theoriacodex.app.series.openExtensions
import com.theoriacodex.app.settings.SettingsAction
import com.theoriacodex.app.settings.SettingsPage
import com.theoriacodex.app.settings.SettingsSheet
import com.theoriacodex.app.settings.StatsActions
import com.theoriacodex.app.settings.rememberPictureTagsEntry
import com.theoriacodex.app.settings.rememberSettingsBackupEntry
import com.theoriacodex.app.settings.sites.rememberCustomSitesEntry
import com.theoriacodex.app.ui.routes.SettingsDestinationStateBoundary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreenDestination(shell: TheoriaShell) {
    val owner = shell.owners.settings
    val entryPage = rememberSaveable { owner.state.value.page }
    LaunchedEffect(owner) { owner.onAction(SettingsAction.SettingsEntered) }
    SettingsDestinationStateBoundary(
        owner, libraryStorageSummary(shell.transfers),
        sites = rememberCustomSitesEntry(shell.container, shell.owners.siteChecks),
        backup = rememberSettingsBackupEntry(shell.transfers),
        pictures = shell.container.pictures?.let { rememberPictureTagsEntry(it, shell.container.applicationScope) },
        statsActions = remember(shell) {
            StatsActions(shell::openStatisticsPost, shell::shareStats, shell::readStatsFile, shell::previewStatisticsPost, shell::downloadStats)
        },
        diagnostics = shell.diagnosticsEntry,
        storageTools = { LibraryTransferSettings(shell.transfers) },
        series = shell.container.series,
        onClose = { shell.navController.popBackStack() },
        closesFrom = entryPage,
        onOpenExtensions = shell.container.series?.let { shell::openExtensions },
    )
}

internal fun TheoriaShell.openSettings(page: SettingsPage = SettingsPage.HOME, sheet: SettingsSheet? = null) {
    owners.settings.onAction(SettingsAction.OpenPage(page, sheet))
    showSettingsRoute()
}

internal fun TheoriaShell.showSettingsRoute() {
    scope.launch {
        snapshotFlow { navReady }.first { ready -> ready }
        if (navigation.currentRoute != AppRoute.Settings) navController.navigate(AppRoute.Settings)
    }
}
