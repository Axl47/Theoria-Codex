package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.statistics.CodexUsageStatistic
import com.theoriacodex.app.statistics.SourceStatistic
import com.theoriacodex.app.statistics.StatisticsSummary
import com.theoriacodex.app.statistics.TagStatistic
import com.theoriacodex.app.statistics.formatStatisticsDuration
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.storage.CorruptionRecovery

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    var showAddProfileDialog by remember { mutableStateOf(false) }
    val recommendationProfilesExpanded =
        state.sectionExpansion[SettingsSectionKey.RECOMMENDATION_PROFILES]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SettingsSection(
            title = "Recommendation Profiles",
            summary = "${state.activeProfile.name} · ${state.activeProfileLikesCount} liked",
            expanded = recommendationProfilesExpanded,
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.RECOMMENDATION_PROFILES,
                        !recommendationProfilesExpanded,
                    )
                )
            },
            headerAction = {
                if (recommendationProfilesExpanded) {
                    IconButton(onClick = { showAddProfileDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add recommendation profile",
                        )
                    }
                }
            },
        ) {
                state.settings.recommendationProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.activeProfile.profileId == profile.profileId,
                            onClick = { onAction(SettingsAction.SetActiveProfile(profile.profileId)) },
                            label = {
                                Text(
                                    if (state.activeProfile.profileId == profile.profileId) {
                                        "${profile.name} · ${state.activeProfileLikesCount} liked"
                                    } else {
                                        profile.name
                                    },
                                )
                            },
                        )
                        IconButton(
                            enabled = state.settings.recommendationProfiles.size > 1,
                            onClick = { onAction(SettingsAction.RequestRemoveProfile(profile.profileId)) },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete ${profile.name} profile",
                            )
                        }
                    }
                }
        }

        SettingsSection(
            title = "Unified Mode",
            summary = "${state.settings.runtime.enabledSources.intersect(state.availableSources.toSet()).size} of ${state.availableSources.size} sources enabled",
            expanded = state.sectionExpansion[SettingsSectionKey.UNIFIED_MODE],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.UNIFIED_MODE,
                        !state.sectionExpansion[SettingsSectionKey.UNIFIED_MODE],
                    )
                )
            },
        ) {
            state.availableSources.forEach { source ->
                val isEnabled = source in state.settings.runtime.enabledSources
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(source.displayName())
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            val updated = state.settings.runtime.enabledSources
                                .intersect(state.availableSources.toSet())
                                .toMutableSet()
                            if (checked) {
                                updated += source
                            } else {
                                updated -= source
                            }
                            onAction(SettingsAction.SetEnabledSources(updated))
                        },
                    )
                }
                if (isEnabled) {
                    val weight = state.settings.runtime.sourceWeights[source]?.toFloat() ?: 0f
                    Slider(
                        value = weight,
                        onValueChange = { raw ->
                            val updated = state.settings.runtime.sourceWeights.toMutableMap()
                            updated[source] = raw.toDouble()
                            onAction(SettingsAction.SetSourceWeights(updated))
                        },
                        valueRange = 0f..1f,
                    )
                    Text("Weight: %.2f".format(weight), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SettingsSection(
            title = "For You Blacklist",
            summary = "${state.activeProfileBlacklist.size} hidden ${if (state.activeProfileBlacklist.size == 1) "set" else "sets"} · ${state.activeProfile.name}",
            expanded = state.sectionExpansion[SettingsSectionKey.FOR_YOU_BLACKLIST],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.FOR_YOU_BLACKLIST,
                        !state.sectionExpansion[SettingsSectionKey.FOR_YOU_BLACKLIST],
                    )
                )
            },
        ) {
                Text(
                    text = "Hidden tag sets for ${state.activeProfile.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.activeProfileBlacklist.isEmpty()) {
                    Text(
                        text = "No blacklisted tags yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    state.activeProfileBlacklist.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.source.displayName()}: ${entry.tags.joinToString(" + ")}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(
                                onClick = {
                                    onAction(SettingsAction.RemoveBlacklistEntry(entry.source, entry.tags))
                                },
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }
        }

        SettingsSection(
            title = "Stats",
            summary = statsSummary(state.statistics),
            expanded = state.sectionExpansion[SettingsSectionKey.STATS],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.STATS,
                        !state.sectionExpansion[SettingsSectionKey.STATS],
                    )
                )
            },
        ) {
            StatisticsContent(statistics = state.statistics)
        }

        SettingsSection(
            title = "Source Accounts",
            summary = sourceAccountsSummary(state.accounts),
            expanded = state.sectionExpansion[SettingsSectionKey.SOURCE_ACCOUNTS],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.SOURCE_ACCOUNTS,
                        !state.sectionExpansion[SettingsSectionKey.SOURCE_ACCOUNTS],
                    )
                )
            },
        ) {

                Text("Pixiv", style = MaterialTheme.typography.titleSmall)
                Text(state.accounts.pixivStatusLabel, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAction(SettingsAction.ConnectPixiv) },
                        enabled = state.accounts.pixivConnectEnabled,
                    ) {
                        Text("Connect")
                    }
                    TextButton(onClick = { onAction(SettingsAction.DisconnectPixiv) }) {
                        Text("Disconnect")
                    }
                }

                Text("Gelbooru", style = MaterialTheme.typography.titleSmall)
                Text(state.accounts.gelbooruStatusLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.accounts.gelbooruUserIdInput,
                    onValueChange = { onAction(SettingsAction.SetGelbooruUserId(it)) },
                    label = { Text("User ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.accounts.gelbooruApiKeyInput,
                    onValueChange = { onAction(SettingsAction.SetGelbooruApiKey(it)) },
                    label = { Text("Replacement API Key") },
                    supportingText = {
                        Text(
                            "Leave blank to keep the configured key. " +
                                "Paste a key or credential query to replace it.",
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(SettingsAction.SaveGelbooruCredentials) }) {
                        Text("Save")
                    }
                    TextButton(onClick = { onAction(SettingsAction.ClearGelbooruCredentials) }) {
                        Text("Clear")
                    }
                }

                Text("rule34.xxx", style = MaterialTheme.typography.titleSmall)
                Text(state.accounts.rule34XxxStatusLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.accounts.rule34XxxUserIdInput,
                    onValueChange = { onAction(SettingsAction.SetRule34XxxUserId(it)) },
                    label = { Text("User ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.accounts.rule34XxxApiKeyInput,
                    onValueChange = { onAction(SettingsAction.SetRule34XxxApiKey(it)) },
                    label = { Text("Replacement API Key") },
                    supportingText = {
                        Text(
                            "Leave blank to keep the configured key. " +
                                "Paste a key or credential query to replace it.",
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(SettingsAction.SaveRule34XxxCredentials) }) {
                        Text("Save")
                    }
                    TextButton(onClick = { onAction(SettingsAction.ClearRule34XxxCredentials) }) {
                        Text("Clear")
                    }
                }
        }

        SettingsSection(
            title = "Updates",
            expanded = state.sectionExpansion[SettingsSectionKey.UPDATES],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.UPDATES,
                        !state.sectionExpansion[SettingsSectionKey.UPDATES],
                    )
                )
            },
            contentSpacing = 8.dp,
        ) {
            Text(
                if (state.changelogLoading) {
                    "Loading release history..."
                } else {
                    "View changelog history for available pre-releases."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { onAction(SettingsAction.OpenChangelog) },
                enabled = !state.changelogLoading,
            ) {
                Text("Open changelog")
            }
        }

        SettingsSection(
            title = "Storage & Caching",
            summary = cacheSummary(state.cacheSnapshot.thumbnailCount, state.cacheSnapshot.fullImageCount),
            expanded = state.sectionExpansion[SettingsSectionKey.STORAGE_AND_CACHING],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.STORAGE_AND_CACHING,
                        !state.sectionExpansion[SettingsSectionKey.STORAGE_AND_CACHING],
                    )
                )
            },
            contentSpacing = 8.dp,
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cache full image on save")
                    Switch(
                        checked = state.settings.cache.cacheFullImageOnSave,
                        onCheckedChange = { onAction(SettingsAction.SetCacheFullImageOnSave(it)) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Resolve unknown animation durations",
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.settings.contentFilters.resolveUnknownAnimatedDurations,
                        onCheckedChange = {
                            onAction(SettingsAction.SetResolveUnknownAnimatedDurations(it))
                        },
                    )
                }
                Text("Thumbnails: ${state.cacheSnapshot.thumbnailCount}")
                Text("Full images: ${state.cacheSnapshot.fullImageCount}")
                if (state.legacyJsonRecoveries.isNotEmpty()) {
                    Text(
                        text = "Recovered legacy storage (${state.legacyJsonRecoveries.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.legacyJsonRecoveries.forEach { recovery ->
                        Text(
                            text = legacyRecoverySummary(recovery),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(onClick = { onAction(SettingsAction.ToggleClearCacheOptions) }) {
                    Text(if (state.showClearCacheOptions) "Clear cache ▲" else "Clear cache ▼")
                }
                if (state.showClearCacheOptions) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAction(SettingsAction.ClearThumbnailCache) },
                        ) {
                            Text("Clear thumbnail cache")
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAction(SettingsAction.ClearFullImageCache) },
                            enabled = state.cacheSnapshot.fullImageCount > 0,
                        ) {
                            Text("Clear full image cache")
                        }
                    }
                }
        }

        if (state.showDeveloperScenarios) {
            SettingsSection(
                title = "Developer scenarios",
                summary = scenarioLabel(state.settings.scenarioPreset),
                expanded = state.sectionExpansion[SettingsSectionKey.DEVELOPER_SCENARIOS],
                onToggle = {
                    onAction(
                        SettingsAction.SetSectionExpanded(
                            SettingsSectionKey.DEVELOPER_SCENARIOS,
                            !state.sectionExpansion[SettingsSectionKey.DEVELOPER_SCENARIOS],
                        )
                    )
                },
                contentSpacing = 8.dp,
            ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScenarioPreset.entries.forEach { scenario ->
                            val label = when (scenario) {
                                ScenarioPreset.NORMAL -> "Normal"
                                ScenarioPreset.PARTIAL_FAILURE -> "Partial Failure"
                                ScenarioPreset.EMPTY_RESULTS -> "Empty"
                                ScenarioPreset.SLOW_NETWORK -> "Slow"
                            }
                            FilterChip(
                                selected = state.settings.scenarioPreset == scenario,
                                onClick = { onAction(SettingsAction.SetScenarioPreset(scenario)) },
                                label = { Text(label) },
                            )
                        }
                    }
            }
        }

        val profileToDelete = state.profileDeleteTargetId?.let { targetId ->
            state.settings.recommendationProfiles.firstOrNull { it.profileId == targetId }
        }
        if (profileToDelete != null) {
            AlertDialog(
                onDismissRequest = { onAction(SettingsAction.DismissRemoveProfile) },
                title = { Text("Delete Profile?") },
                text = {
                    Text(
                        "Delete profile \"${profileToDelete.name}\" and its likes/codex data? This cannot be undone.",
                    )
                },
                dismissButton = {
                    TextButton(onClick = { onAction(SettingsAction.DismissRemoveProfile) }) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAction(SettingsAction.ConfirmRemoveProfile)
                        },
                    ) {
                        Text("Delete")
                    }
                },
            )
        }

        if (showAddProfileDialog) {
            RecommendationProfileNameDialog(
                onDismiss = { showAddProfileDialog = false },
                onSave = { name ->
                    onAction(SettingsAction.AddProfile(name))
                    showAddProfileDialog = false
                },
            )
        }
    }
}

@Composable
private fun RecommendationProfileNameDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    fun saveIfValid() {
        val trimmed = value.trim()
        if (trimmed.isNotBlank()) onSave(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Recommendation Profile") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = { value = it.replace("\n", " ") },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { saveIfValid() }),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = { saveIfValid() }) { Text("Save") }
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
        accounts.gelbooruStatusLabel == "Configured",
        accounts.rule34XxxStatusLabel == "Configured",
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

internal fun statsSummary(statistics: StatisticsSummary): String {
    return "${statistics.watchedPostCount} watched · ${statistics.savedPostCount} saved · " +
        formatStatisticsDuration(statistics.totalForegroundMs)
}

@Composable
private fun StatisticsContent(
    statistics: StatisticsSummary,
) {
    AppStatisticsGroup(statistics)
    PostStatisticsGroup(statistics)
    SearchStatisticsGroup(statistics)
    TagStatisticsGroup(statistics)
    CodexStatisticsGroup(statistics)
}

@Composable
private fun AppStatisticsGroup(statistics: StatisticsSummary) {
    StatisticsGroupTitle("App Stats")
    StatisticValueRow("Times App has been Opened", statistics.appOpenCount.toString())
    StatisticValueRow("Time Spent in App", formatStatisticsDuration(statistics.totalForegroundMs))
    StatisticValueRow("Time Spent Browsing", formatStatisticsDuration(statistics.browsingMs), nested = true)
    StatisticValueRow("Time Spent Watching", formatStatisticsDuration(statistics.watchingMs), nested = true)
    StatisticValueRow("Time Spent in Codex", formatStatisticsDuration(statistics.codexMs), nested = true)
}

@Composable
private fun PostStatisticsGroup(statistics: StatisticsSummary) {
    StatisticsGroupTitle("Post Stats")
    StatisticValueRow("Posts Watched", statistics.watchedPostCount.toString())
    SourceBreakdown(
        rows = statistics.watchedSources,
        denominator = statistics.watchedPostCount,
        emptyMessage = "No watched-source data yet.",
    )
    StatisticValueRow("Posts Liked/Saved", statistics.savedPostCount.toString())
    SourceBreakdown(
        rows = statistics.savedSources,
        denominator = statistics.savedPostCount,
        emptyMessage = "No saved-source data yet.",
    )
    StatisticValueRow("Posts Saved from For You", statistics.forYouSaveCount.toString())
    StatisticValueRow("Posts Shared", statistics.postUrlCopyCount.toString())
}

@Composable
private fun SearchStatisticsGroup(statistics: StatisticsSummary) {
    StatisticsGroupTitle("Search Stats")
    StatisticValueRow("Searches Done", statistics.searchCount.toString())
    SourceBreakdown(
        rows = statistics.searchSources,
        denominator = statistics.searchCount,
        emptyMessage = "No search-source data yet.",
    )
    StatisticValueRow("For You Searches Done", statistics.forYouSearchCount.toString())
}

@Composable
private fun TagStatisticsGroup(statistics: StatisticsSummary) {
    StatisticsGroupTitle("Tag Stats")
    Text("Top 5 Tags Watched", style = MaterialTheme.typography.bodyMedium)
    TagBreakdown(statistics.topWatchedTags, "No watched tags yet.")
    Text("Top 5 Tags Saved", style = MaterialTheme.typography.bodyMedium)
    TagBreakdown(statistics.topSavedTags, "No saved tags yet.")
}

@Composable
private fun CodexStatisticsGroup(statistics: StatisticsSummary) {
    StatisticsGroupTitle("Codex Stats")
    CodexUsageStat("Most Used Codex", statistics.mostUsedCodex)
    CodexUsageStat("Least Used Codex", statistics.leastUsedCodex)
    Text("Top Sources in Codex", style = MaterialTheme.typography.bodyMedium)
    SourceBreakdown(
        rows = statistics.topCodexSources,
        denominator = statistics.savedPostCount,
        emptyMessage = "No saved-source data yet.",
        showDenominator = true,
    )
}

@Composable
private fun CodexUsageStat(title: String, row: CodexUsageStatistic?) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    StatisticValueRow(
        label = row?.name ?: "None yet",
        value = row?.entryCount?.let(::codexEntryCountLabel).orEmpty(),
        nested = true,
    )
}

internal fun codexEntryCountLabel(entryCount: Long): String {
    return "Entered $entryCount ${if (entryCount == 1L) "Time" else "Times"}"
}

@Composable
private fun StatisticsGroupTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun StatisticValueRow(
    label: String,
    value: String,
    nested: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (nested) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            style = if (nested) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SourceBreakdown(
    rows: List<SourceStatistic>,
    denominator: Long,
    emptyMessage: String,
    showDenominator: Boolean = false,
) {
    if (rows.isEmpty()) {
        Text(
            text = emptyMessage,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    rows.forEach { row ->
        val amount = if (showDenominator) {
            "${row.percentage}% (${row.count} / $denominator)"
        } else {
            "${row.percentage}% (${row.count})"
        }
        StatisticValueRow(row.source.displayName(), amount, nested = true)
    }
}

@Composable
private fun TagBreakdown(rows: List<TagStatistic>, emptyMessage: String) {
    if (rows.isEmpty()) {
        Text(
            text = emptyMessage,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    rows.forEach { row ->
        StatisticValueRow(
            label = "${row.key.tag} · ${row.key.source.displayName()}",
            value = "${row.percentage}% (${row.count} posts)",
            nested = true,
        )
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    summary: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    contentSpacing: Dp = 10.dp,
    headerAction: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .expandableControlSemantics(
                        expanded = expanded,
                        description = if (expanded) "Expanded" else "Collapsed",
                        onExpandedChange = { onToggle() },
                    )
                    .clickable(
                        role = Role.Button,
                        onClick = onToggle,
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsSectionHeaderText(
                    title = title,
                    summary = summary,
                    expanded = expanded,
                    modifier = Modifier.weight(1f),
                )
                headerAction?.invoke()
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeaderText(
    title: String,
    summary: String?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (!expanded && !summary.isNullOrBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
