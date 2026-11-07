package com.theoriacodex.app.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.media.videoQualityLabel
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.ui.components.ActionMenuRow
import com.theoriacodex.app.ui.components.NameEntryDialog
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.app.ui.components.ActionMenuSheet
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.data.storage.CorruptionRecovery
import com.theoriacodex.domain.model.SourceKey

private enum class SettingsSheet { PROFILE, SOURCE_MIX, PLAYBACK, CLASSIC_CONTROLS }

/** Settings hub: each row shows its current value; short choices use sheets, larger tasks use pages. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    statistics: StatisticsSummary = StatisticsSummary(),
    searchSources: Set<SourceKey> = emptySet(),
    storageSummary: String? = null,
    storageTools: (@Composable () -> Unit)? = null,
) {
    val openHome = { onAction(SettingsAction.OpenPage(SettingsPage.HOME)) }
    BackHandler(enabled = state.page != SettingsPage.HOME, onBack = openHome)
    AnimatedContent(
        targetState = state.page,
        transitionSpec = { depthTransform(forward = targetState != SettingsPage.HOME) },
        label = "Settings page",
    ) { page ->
        when (page) {
            SettingsPage.HOME -> SettingsHome(state, statistics, onAction, searchSources, storageSummary)
            SettingsPage.ACCOUNTS -> AccountsPage(state, onAction, openHome)
            SettingsPage.HIDDEN_TAGS -> HiddenTagsPage(state, onAction, openHome)
            SettingsPage.STORAGE -> StoragePage(state, onAction, storageTools, openHome)
            SettingsPage.STATS -> StatsScreen(statistics, openHome)
        }
    }
    ProfileDeleteDialog(state, onAction)
}

@Composable
private fun SettingsHome(
    state: SettingsUiState,
    statistics: StatisticsSummary,
    onAction: (SettingsAction) -> Unit,
    searchSources: Set<SourceKey>,
    storageSummary: String?,
) {
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    var showAddProfile by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecommendationProfile?>(null) }
    val open = { page: SettingsPage -> onAction(SettingsAction.OpenPage(page)) }
    SettingsPageColumn {
        SettingsHeader("Settings")
        ProfileCard(state, onAction, onAdd = { showAddProfile = true }, onMenu = { sheet = SettingsSheet.PROFILE })
        SettingsGroup(null) {
            SettingsRow("Your stats", statsSummary(statistics), Icons.Default.BarChart, onClick = {
                open(SettingsPage.STATS)
            })
        }
        SettingsHomeGroups(state, onAction, searchSources, storageSummary, { sheet = it }, open)
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
    sheet?.let {
        SettingsHomeSheet(it, state, onAction, searchSources, { renameTarget = state.activeProfile }) { sheet = null }
    }
}

@Composable
private fun SettingsHomeGroups(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    searchSources: Set<SourceKey>,
    storageSummary: String?,
    onSheet: (SettingsSheet) -> Unit,
    open: (SettingsPage) -> Unit,
) {
    SettingsGroup("Recommendations") {
        SettingsRow("Hidden tags", hiddenTagsSummary(state), Icons.Default.VisibilityOff, onClick = {
            open(SettingsPage.HIDDEN_TAGS)
        })
        SettingsRowDivider()
        SettingsRow("Source mix", sourceMixSummary(state, searchSources), Icons.Default.Tune, onClick = {
            onSheet(SettingsSheet.SOURCE_MIX)
        })
    }
    SettingsGroup("Sources") {
        SettingsRow("Accounts", sourceAccountsSummary(state.accounts), Icons.Default.AccountCircle, onClick = {
            open(SettingsPage.ACCOUNTS)
        })
        SettingsRowDivider()
        OpenLinksRow()
    }
    SettingsGroup("Viewing") {
        SettingsRow("Playback & downloads", playbackSummary(state), Icons.Default.PlayCircle, onClick = {
            onSheet(SettingsSheet.PLAYBACK)
        })
        SettingsRowDivider()
        SettingsRow(
            "Invert scroll direction", "Reverse swipes within multi-media posts", Icons.Default.SwapVert,
            trailing = {
                Switch(state.settings.viewer.invertMultiImageScrollDirection, onCheckedChange = {
                    onAction(SettingsAction.SetInvertScrollDirection(it))
                })
            },
        )
        SettingsRowDivider()
        SettingsRow("Classic controls", "Old search bar, instant seek", Icons.Default.History, onClick = {
            onSheet(SettingsSheet.CLASSIC_CONTROLS)
        })
    }
    SettingsGroup("Storage & app") {
        SettingsRow(
            "Storage & caching",
            storageSummary ?: cacheSummary(state.cacheSnapshot.thumbnailCount, state.cacheSnapshot.fullImageCount),
            Icons.Default.Storage,
            onClick = { open(SettingsPage.STORAGE) },
        )
        SettingsRowDivider()
        SettingsRow(
            "What's new",
            if (state.changelogLoading) "Loading release history..." else "Changelog for available pre-releases",
            Icons.Default.NewReleases,
            enabled = !state.changelogLoading,
            onClick = { onAction(SettingsAction.OpenChangelog) },
        )
    }
}

@Composable
private fun SettingsHomeSheet(
    sheet: SettingsSheet,
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    searchSources: Set<SourceKey>,
    onRenameProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    ActionMenuSheet(onDismiss) {
        when (sheet) {
            SettingsSheet.PROFILE -> {
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
            SettingsSheet.SOURCE_MIX -> UnifiedSourceSettings(searchSources, state.settings.runtime) {
                onAction(SettingsAction.SetSourceWeights(searchSources, it))
            }
            SettingsSheet.PLAYBACK -> MediaPreferencesSection(state, onAction)
            SettingsSheet.CLASSIC_CONTROLS -> PreferencesSection(state, onAction)
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
private fun AccountsPage(state: SettingsUiState, onAction: (SettingsAction) -> Unit, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader("Accounts", onBack)
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pixiv", style = MaterialTheme.typography.titleSmall)
                Text(state.accounts.pixivStatusLabel, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAction(SettingsAction.ConnectPixiv) },
                        enabled = state.accounts.pixivConnectEnabled,
                    ) { Text("Connect") }
                    TextButton(onClick = { onAction(SettingsAction.DisconnectPixiv) }) { Text("Disconnect") }
                }
            }
        }
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CredentialAccountControls(
                    name = "Gelbooru",
                    statusLabel = state.accounts.gelbooruStatusLabel,
                    configured = state.accounts.gelbooruConfigured,
                    userIdInput = state.accounts.gelbooruUserIdInput,
                    apiKeyInput = state.accounts.gelbooruApiKeyInput,
                    enabled = state.accounts.mutationsEnabled,
                    onUserIdChange = { onAction(SettingsAction.SetGelbooruUserId(it)) },
                    onApiKeyChange = { onAction(SettingsAction.SetGelbooruApiKey(it)) },
                    onSave = { onAction(SettingsAction.SaveGelbooruCredentials) },
                    onDisconnect = { onAction(SettingsAction.ClearGelbooruCredentials) },
                )
            }
        }
        SettingsGroup(null) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CredentialAccountControls(
                    name = "rule34.xxx",
                    statusLabel = state.accounts.rule34XxxStatusLabel,
                    configured = state.accounts.rule34XxxConfigured,
                    userIdInput = state.accounts.rule34XxxUserIdInput,
                    apiKeyInput = state.accounts.rule34XxxApiKeyInput,
                    enabled = state.accounts.mutationsEnabled,
                    onUserIdChange = { onAction(SettingsAction.SetRule34XxxUserId(it)) },
                    onApiKeyChange = { onAction(SettingsAction.SetRule34XxxApiKey(it)) },
                    onSave = { onAction(SettingsAction.SaveRule34XxxCredentials) },
                    onDisconnect = { onAction(SettingsAction.ClearRule34XxxCredentials) },
                )
            }
        }
    }
}

@Composable
private fun HiddenTagsPage(state: SettingsUiState, onAction: (SettingsAction) -> Unit, onBack: () -> Unit) {
    SettingsPageColumn {
        SettingsHeader("Hidden tags", onBack)
        Text(
            "Hidden from For You on ${state.activeProfile.name}",
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.activeProfileBlacklist.isEmpty()) {
            Text("No hidden tags yet.", Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            SettingsGroup(null) {
                state.activeProfileBlacklist.forEachIndexed { index, entry ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        entry.tags.joinToString(" + "), entry.source.displayName(),
                        trailing = {
                            TextButton(onClick = {
                                onAction(SettingsAction.RemoveBlacklistEntry(entry.source, entry.tags))
                            }) { Text("Remove") }
                        },
                    )
                }
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
        SettingsHeader("Storage & caching", onBack)
        SettingsGroup(null) {
            SettingsRow("Keep media offline when saving", trailing = {
                Switch(state.settings.cache.cacheFullImageOnSave, onCheckedChange = {
                    onAction(SettingsAction.SetCacheFullImageOnSave(it))
                })
            })
            SettingsRowDivider()
            SettingsRow("Find animation durations in background", trailing = {
                Switch(state.settings.contentFilters.resolveUnknownAnimatedDurations, onCheckedChange = {
                    onAction(SettingsAction.SetResolveUnknownAnimatedDurations(it))
                })
            })
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
        if (state.legacyJsonRecoveries.isNotEmpty()) {
            SettingsGroup("Recovered legacy storage (${state.legacyJsonRecoveries.size})") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.legacyJsonRecoveries.forEach { recovery ->
                        Text(legacyRecoverySummary(recovery), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Only reached when the library transfer tools, which include cache controls, are absent. */
@Composable
private fun FallbackCacheControls(state: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Text("Thumbnails: ${state.cacheSnapshot.thumbnailCount}")
    Text("Full images: ${state.cacheSnapshot.fullImageCount}")
    Button(onClick = { onAction(SettingsAction.ToggleClearCacheOptions) }) {
        Text(if (state.showClearCacheOptions) "Clear cache ▲" else "Clear cache ▼")
    }
    if (state.showClearCacheOptions) {
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
        text = { Text("Delete profile \"${profile.name}\" and its likes/codex data? This cannot be undone.") },
        dismissButton = {
            TextButton(onClick = { onAction(SettingsAction.DismissRemoveProfile) }) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = { onAction(SettingsAction.ConfirmRemoveProfile) }) { Text("Delete") }
        },
    )
}

internal fun legacyRecoverySummary(
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
    return "$configuredCount of 3 configured"
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

internal fun hiddenTagsSummary(state: SettingsUiState): String {
    val count = state.activeProfileBlacklist.size
    return "$count ${if (count == 1) "set" else "sets"} on ${state.activeProfile.name}"
}

internal fun sourceMixSummary(state: SettingsUiState, sources: Set<SourceKey>): String {
    if (sources.size < 2) return "Hold sources in Search to combine them"
    val weights = SourceWeightSettings.weightsFor(state.settings.runtime, sources)
    return sources.sortedBy { it.ordinal }.joinToString(" · ") { source ->
        "${source.displayName()} ${(weights.getValue(source) * 100).toInt()}%"
    }
}

internal fun playbackSummary(state: SettingsUiState): String =
    "Video ${videoQualityLabel(state.settings.viewer.videoQuality)} · " +
        "Downloads ${videoQualityLabel(state.settings.cache.downloadQuality)}"
