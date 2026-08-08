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
import com.theoriacodex.app.ui.components.expandableControlSemantics
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.data.storage.CorruptionRecovery

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    var showAddProfileDialog by remember { mutableStateOf(false) }

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
            expanded = state.sectionExpansion[SettingsSectionKey.RECOMMENDATION_PROFILES],
            onToggle = {
                onAction(
                    SettingsAction.SetSectionExpanded(
                        SettingsSectionKey.RECOMMENDATION_PROFILES,
                        !state.sectionExpansion[SettingsSectionKey.RECOMMENDATION_PROFILES],
                    )
                )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showAddProfileDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add recommendation profile",
                        )
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

@Composable
internal fun SettingsSection(
    title: String,
    summary: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    contentSpacing: Dp = 10.dp,
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
