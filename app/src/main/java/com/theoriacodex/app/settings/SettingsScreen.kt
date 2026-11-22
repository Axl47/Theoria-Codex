package com.theoriacodex.app.settings

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.theoriacodex.data.repository.SeriesUpdateInterval
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import com.theoriacodex.app.ui.motion.depthTransform
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.allProfileCodices
import com.theoriacodex.app.statistics.shareOverall
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.NameEntryDialog
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.repository.SettingChoices
import com.theoriacodex.data.repository.SettingDestinations
import com.theoriacodex.data.repository.SettingToggles
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.data.storage.CorruptionRecovery
import kotlinx.coroutines.launch
import com.theoriacodex.app.ui.components.SettingsHeader
import com.theoriacodex.app.ui.components.SettingsGroup
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsPageColumn

private val STORAGE_TOGGLES = listOf(SettingToggles.KeepMediaOffline, SettingToggles.FindAnimationDurations)

/**
 * Settings hub: each row shows its current value; short choices use sheets, larger tasks use pages.
 * Back on [closesFrom], the page Settings was opened at, calls [onClose]; on any other page it goes home.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    statistics: StatisticsSummary = StatisticsSummary(),
    storageSummary: String? = null,
    sites: SettingsSitesEntry? = null,
    backup: SettingsBackupEntry? = null,
    statsActions: StatsActions = StatsActions(),
    diagnostics: SettingsDiagnosticsEntry? = null,
    storageTools: (@Composable () -> Unit)? = null,
    /** Whether this build has series to check, and so the setting for how often. */
    seriesUpdates: Boolean = false,
    onClose: () -> Unit = {},
    closesFrom: SettingsPage = SettingsPage.HOME,
    /** Opens the Extensions page, which lives outside Settings; null in a build without extensions. */
    onOpenExtensions: (() -> Unit)? = null,
) {
    val openHome = { if (state.page == closesFrom) onClose() else onAction(SettingsAction.OpenPage(SettingsPage.HOME)) }
    val openPost = rememberPostOpener(statsActions.openPost)
    val choosingStatsFile = state.page == SettingsPage.STATS_COMPARE && state.statsComparison == null
    StatsFilePicker(choosingStatsFile, statsActions, onAction, openHome)
    BackHandler(enabled = state.page != SettingsPage.HOME, onBack = openHome)
    AnimatedContent(
        // Home stays behind the file picker, which covers it until a file is chosen.
        targetState = if (choosingStatsFile) SettingsPage.HOME else state.page,
        transitionSpec = { depthTransform(forward = targetState != SettingsPage.HOME) },
        label = "Settings page",
    ) { page ->
        when (page) {
            SettingsPage.HOME -> SettingsHome(state, statistics, onAction, storageSummary, sites, backup, diagnostics != null, seriesUpdates, onClose, onOpenExtensions)
            SettingsPage.SITES -> sites?.page?.invoke(
                openHome,
                BuiltInAccounts(
                    summary = { builtInAccountSummary(it, state.accounts) },
                    controls = { BuiltInAccountControls(it, state.accounts, onAction) },
                ),
            )
            SettingsPage.STORAGE -> StoragePage(state, onAction, storageTools, openHome)
            SettingsPage.BACKUP -> backup?.let { BackupPage(it, openHome) }
            SettingsPage.STATS -> StatsScreen(statistics, openPost, openHome)
            SettingsPage.STATS_SHARE -> StatsSharePreview(
                statistics.profiles, statistics.shareOverall(), state.activeProfile.profileId, statsActions, openPost, openHome,
            )
            SettingsPage.STATS_COMPARE -> state.statsComparison?.let { theirs ->
                val close = {
                    onAction(SettingsAction.SetStatsComparison(null))
                    if (closesFrom == SettingsPage.STATS_COMPARE) onClose()
                }
                BackHandler(onBack = close)
                StatsComparisonScreen(
                    allProfileCodices(statistics.profiles), statistics.shareOverall(), theirs, state.availableSources,
                    statsActions.previewPost, openPost, close,
                )
            }
            SettingsPage.DIAGNOSTICS -> diagnostics?.let { DiagnosticsPage(it, openHome) }
        }
    }
    ProfileDeleteDialog(state, onAction)
}

@Composable
private fun SettingsHome(
    state: SettingsUiState,
    statistics: StatisticsSummary,
    onAction: (SettingsAction) -> Unit,
    storageSummary: String?,
    sites: SettingsSitesEntry?,
    backup: SettingsBackupEntry?,
    showDiagnostics: Boolean,
    seriesUpdates: Boolean,
    onClose: () -> Unit,
    onOpenExtensions: (() -> Unit)?,
) {
    var profileMenu by rememberSaveable { mutableStateOf(false) }
    var showAddProfile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecommendationProfile?>(null) }
    val open = { page: SettingsPage -> onAction(SettingsAction.OpenPage(page)) }
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.Home.title, onBack = onClose)
        ProfileCard(state, onAction, onAdd = { showAddProfile = true }, onMenu = { profileMenu = true })
        SettingsGroup("Stats") {
            SettingsRow(SettingDestinations.Stats.title, statsSummary(statistics), Icons.Default.BarChart, onClick = {
                open(SettingsPage.STATS)
            })
            SettingsRowDivider()
            SettingsRow(SettingDestinations.ShareStats.title, icon = Icons.Default.Share, onClick = {
                open(SettingsPage.STATS_SHARE)
            })
            SettingsRowDivider()
            SettingsRow(SettingDestinations.CompareStats.title, "Open a stats file someone sent you", Icons.AutoMirrored.Filled.CompareArrows, onClick = {
                open(SettingsPage.STATS_COMPARE)
            })
        }
        SettingsHomeGroups(state, onAction, storageSummary, sites, backup, showDiagnostics, seriesUpdates, onOpenExtensions) { sheet -> onAction(SettingsAction.OpenPage(SettingsPage.HOME, sheet)) }
        if (state.showDeveloperScenarios) DeveloperScenarios(state, onAction)
    }
    if (showAddProfile) {
        NameEntryDialog(
            title = "Add Recommendation Profile",
            initialName = "",
            onDismiss = { showAddProfile = false },
            onSave = { name ->
                onAction(SettingsAction.AddProfile(name))
                showAddProfile = false
            },
        )
    }
    renameTarget?.let { profile ->
        NameEntryDialog(
            title = "Rename Recommendation Profile",
            initialName = profile.name,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                onAction(SettingsAction.RenameProfile(profile.profileId, name))
                renameTarget = null
            },
        )
    }
    if (profileMenu) ProfileMenuSheet(state, onAction, { renameTarget = state.activeProfile }) { profileMenu = false }
    state.sheet?.let { sheet ->
        ActionMenuSheet({ onAction(SettingsAction.OpenPage(SettingsPage.HOME)) }) {
            when (sheet) {
                SettingsSheet.DOWNLOADS -> DownloadPreferencesSection(state, onAction)
                SettingsSheet.CONTROLS -> PreferencesSection(state, onAction)
                // One choice is all this sheet holds, so choosing closes it.
                SettingsSheet.SERIES_UPDATES -> SeriesUpdatesSection(state) { action ->
                    onAction(action)
                    onAction(SettingsAction.OpenPage(SettingsPage.HOME))
                }
            }
        }
    }
}

/** Where content comes from: sites, extensions, links, and the check for new chapters of followed series. */
@Composable
private fun SettingsSourcesGroup(
    state: SettingsUiState,
    sites: SettingsSitesEntry?,
    seriesUpdates: Boolean,
    onOpenExtensions: (() -> Unit)?,
    onOpenSites: () -> Unit,
    onSheet: (SettingsSheet) -> Unit,
) {
    SettingsGroup("Sources") {
        if (sites != null) {
            SettingsRow(
                SettingDestinations.Sites.title, "${sites.summary} · ${sourceAccountsSummary(state.accounts)}", Icons.Default.Public,
                onClick = onOpenSites,
            )
            SettingsRowDivider()
        }
        if (onOpenExtensions != null) {
            SettingsRow("Extensions", icon = Icons.Default.Extension, onClick = onOpenExtensions)
            SettingsRowDivider()
        }
        OpenLinksRow()
        if (seriesUpdates) {
            SettingsRowDivider()
            SeriesUpdatesRow(state.settings.seriesUpdateInterval) { onSheet(SettingsSheet.SERIES_UPDATES) }
        }
    }
}

/**
 * How often followed series are checked. A check that cannot announce what it finds says so instead, and the row then
 * opens the app's notification settings, since the system stops showing its permission prompt once it was refused.
 */
@Composable
private fun SeriesUpdatesRow(interval: SeriesUpdateInterval, onChooseInterval: () -> Unit) {
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    val silenced = !notificationsOn && interval.hours != null
    SettingsRow(
        SettingChoices.SeriesUpdates.title, if (silenced) "Notifications off" else seriesUpdateIntervalLabel(interval), Icons.Default.Notifications,
        onClick = {
            if (silenced) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            } else onChooseInterval()
        },
    )
}

@Composable
private fun SettingsHomeGroups(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    storageSummary: String?,
    sites: SettingsSitesEntry?,
    backup: SettingsBackupEntry?,
    showDiagnostics: Boolean,
    seriesUpdates: Boolean,
    onOpenExtensions: (() -> Unit)?,
    onSheet: (SettingsSheet) -> Unit,
) {
    val open = { page: SettingsPage -> onAction(SettingsAction.OpenPage(page)) }
    SettingsSourcesGroup(state, sites, seriesUpdates, onOpenExtensions, { open(SettingsPage.SITES) }, onSheet)
    SettingsGroup("Viewing") {
        SettingsRow(SettingDestinations.Controls.title, "Search bar, seeking, swipes, rotation", Icons.Default.TouchApp, onClick = {
            onSheet(SettingsSheet.CONTROLS)
        })
    }
    SettingsGroup("Storage & app") {
        SettingsRow(
            SettingDestinations.Storage.title,
            storageSummary ?: cacheSummary(state.cacheSnapshot.thumbnailCount, state.cacheSnapshot.fullImageCount),
            Icons.Default.Storage,
            onClick = { open(SettingsPage.STORAGE) },
        )
        SettingsRowDivider()
        SettingsRow(SettingDestinations.Downloads.title, downloadsSummary(state), Icons.Default.Download, onClick = {
            onSheet(SettingsSheet.DOWNLOADS)
        })
        backup?.let {
            SettingsRowDivider()
            SettingsRow(
                SettingDestinations.Backup.title, it.summary?.text, Icons.Default.Backup,
                summaryIsError = it.summary?.failed == true,
                onClick = { open(SettingsPage.BACKUP) },
            )
        }
        SettingsRowDivider()
        AppLockRow()
        SettingsRowDivider()
        SettingsRow(
            SettingDestinations.WhatsNew.title,
            "Loading release history…".takeIf { state.changelogLoading },
            Icons.Default.NewReleases,
            enabled = !state.changelogLoading,
            onClick = { onAction(SettingsAction.OpenChangelog) },
        )
        if (showDiagnostics) {
            SettingsRowDivider()
            SettingsRow(SettingDestinations.Diagnostics.title, icon = Icons.Default.BugReport, onClick = { open(SettingsPage.DIAGNOSTICS) })
        }
    }
}

@Composable
private fun ProfileMenuSheet(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onRenameProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionMenuSheet(onDismiss) {
        ActionMenuRow(Icons.Default.Edit, "Rename ${state.activeProfile.name}") {
            onDismiss()
            onRenameProfile()
        }
        ActionMenuRow(
            Icons.Default.Delete, "Delete ${state.activeProfile.name}",
            enabled = state.settings.recommendationProfiles.size > 1,
            tint = MaterialTheme.colorScheme.error,
        ) {
            onDismiss()
            onAction(SettingsAction.RequestRemoveProfile(state.activeProfile.profileId))
        }
    }
}

/**
 * Opens the system file picker once each time [choosing] turns on (the Compare page without a file);
 * a readable stats file opens the comparison, anything else goes back through [onBack].
 */
@Composable
private fun StatsFilePicker(choosing: Boolean, statsActions: StatsActions, onAction: (SettingsAction) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    var launched by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        launched = false
        scope.launch {
            val file = uri?.let { statsActions.readFile(it.toString()) }
            if (file != null) onAction(SettingsAction.SetStatsComparison(file)) else currentOnBack()
        }
    }
    LaunchedEffect(choosing) {
        if (choosing && !launched) {
            launched = true
            picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
    }
}

@Composable
private fun ProfileCard(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onAdd: () -> Unit,
    onMenu: () -> Unit,
) {
    SettingsGroup(null) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Recommendation profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${state.activeProfile.name} · ${state.activeProfileLikesCount} liked",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Profile actions") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.settings.recommendationProfiles.forEach { profile ->
                    FilterChip(
                        selected = state.activeProfile.profileId == profile.profileId,
                        onClick = { onAction(SettingsAction.SetActiveProfile(profile.profileId)) },
                        label = { Text(profile.name) },
                    )
                }
                FilterChip(
                    selected = false, onClick = onAdd, label = { Text("New") },
                    leadingIcon = { Icon(Icons.Default.Add, "Add recommendation profile") },
                )
            }
        }
    }
}

@Composable
private fun StoragePage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    storageTools: (@Composable () -> Unit)?,
    onBack: () -> Unit,
) {
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.Storage.title, onBack, SETTINGS_PATH)
        SettingsGroup(null) {
            STORAGE_TOGGLES.forEachIndexed { index, toggle ->
                if (index > 0) SettingsRowDivider()
                SwitchRow(toggle, state.settings) { onAction(SettingsAction.SetToggle(toggle, it)) }
            }
        }
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (storageTools != null) {
                    storageTools()
                } else {
                    FallbackCacheControls(state, onAction)
                }
            }
        }
        if (state.recoveredStores.isNotEmpty()) {
            SettingsGroup("Recovered storage (${state.recoveredStores.size})") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recoveredStores.forEach { recovery ->
                        Text(recoveredStoreSummary(recovery), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupPage(backup: SettingsBackupEntry, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader(SettingDestinations.Backup.title, onBack, SETTINGS_PATH)
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { backup.controls() }
        }
    }
}

/** Only reached when the library transfer tools, which include cache controls, are absent. */
@Composable
private fun FallbackCacheControls(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Text("Thumbnails: ${state.cacheSnapshot.thumbnailCount}")
    Text("Full images: ${state.cacheSnapshot.fullImageCount}")
    ExpandableSection("Clear cache", initiallyExpanded = false) {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.ClearThumbnailCache) },
        ) { Text("Clear thumbnail cache") }
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(SettingsAction.ClearFullImageCache) },
            enabled = state.cacheSnapshot.fullImageCount > 0,
        ) { Text("Clear full image cache") }
    }
}

@Composable
private fun DeveloperScenarios(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    SettingsGroup("Developer scenarios") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScenarioPreset.entries.forEach { scenario ->
                FilterChip(
                    selected = state.settings.scenarioPreset == scenario,
                    onClick = { onAction(SettingsAction.SetScenarioPreset(scenario)) },
                    label = { Text(scenarioLabel(scenario)) },
                )
            }
        }
    }
}

@Composable
private fun ProfileDeleteDialog(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    val profile = state.profileDeleteTargetId?.let { targetId ->
        state.settings.recommendationProfiles.firstOrNull { it.profileId == targetId }
    } ?: return
    AlertDialog(
        onDismissRequest = { onAction(SettingsAction.DismissRemoveProfile) },
        title = { Text("Delete Profile?") },
        text = { Text("Delete profile \"${profile.name}\" with its likes and Codices? This cannot be undone.") },
        dismissButton = {
            TextButton(onClick = { onAction(SettingsAction.DismissRemoveProfile) }) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = { onAction(SettingsAction.ConfirmRemoveProfile) }) { Text("Delete") }
        },
    )
}

internal fun recoveredStoreSummary(
    recovery: CorruptionRecovery,
): String = buildString {
    append(recovery.logicalStore.ifBlank { recovery.logicalFile.ifBlank { "Local storage" } })
    append(" was reset after unreadable local data was preserved (")
    append(recovery.byteCount)
    append(" bytes")
    if (recovery.sha256.isNotBlank()) {
        append(", checksum ")
        append(recovery.sha256.take(8))
    }
    append(").")
}

internal fun sourceAccountsSummary(accounts: SettingsAccountUiState): String {
    val configuredCount = listOf(
        accounts.pixivConnected,
        accounts.gelbooruConfigured,
        accounts.rule34XxxConfigured,
    ).count { it }
    return "$configuredCount of 3 accounts"
}

internal fun cacheSummary(thumbnailCount: Int, fullImageCount: Int): String {
    val itemCount = thumbnailCount + fullImageCount
    return "$itemCount cached ${if (itemCount == 1) "item" else "items"}"
}

internal fun scenarioLabel(scenario: ScenarioPreset): String = when (scenario) {
    ScenarioPreset.NORMAL -> "Normal"
    ScenarioPreset.PARTIAL_FAILURE -> "Partial failure"
    ScenarioPreset.EMPTY_RESULTS -> "Empty results"
    ScenarioPreset.SLOW_NETWORK -> "Slow network"
}

internal fun statsSummary(statistics: StatisticsSummary): String =
    "${formatStatsDuration(statistics.totalForegroundMs)} in app · " +
        "${statistics.watchedPostCount} watched · ${statistics.savedPostCount} saved"

internal fun downloadsSummary(state: SettingsUiState): String =
    "${videoQualityLabel(state.settings.cache.downloadQuality)} quality"
