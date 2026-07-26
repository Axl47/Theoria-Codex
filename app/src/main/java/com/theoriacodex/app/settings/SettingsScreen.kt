package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.RecommendationProfile
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.domain.model.SourceKey

@Composable
fun SettingsScreen(
    settings: AppSettings,
    recommendationProfiles: List<RecommendationProfile>,
    activeProfileId: String,
    activeProfileName: String,
    likesCount: Int,
    forYouBlacklistEntries: List<ForYouBlacklistEntry>,
    availableSources: List<SourceKey>,
    cacheSnapshot: CacheSnapshot,
    showDeveloperScenarios: Boolean,
    pixivStatusLabel: String,
    pixivConnectEnabled: Boolean,
    onPixivConnect: () -> Unit,
    onPixivDisconnect: () -> Unit,
    gelbooruUserId: String,
    gelbooruApiKey: String,
    gelbooruStatusLabel: String,
    onGelbooruUserIdChange: (String) -> Unit,
    onGelbooruApiKeyChange: (String) -> Unit,
    onSaveGelbooruCredentials: () -> Unit,
    onClearGelbooruCredentials: () -> Unit,
    rule34XxxUserId: String,
    rule34XxxApiKey: String,
    rule34XxxStatusLabel: String,
    onRule34XxxUserIdChange: (String) -> Unit,
    onRule34XxxApiKeyChange: (String) -> Unit,
    onSaveRule34XxxCredentials: () -> Unit,
    onClearRule34XxxCredentials: () -> Unit,
    onSetEnabledSources: (Set<SourceKey>) -> Unit,
    onSetSourceWeights: (Map<SourceKey, Double>) -> Unit,
    onSetActiveProfile: (String) -> Unit,
    onAddProfile: (String) -> Unit,
    onRemoveProfile: (String) -> Unit,
    onClearLikesForActiveProfile: () -> Unit,
    onRemoveForYouBlacklistEntry: (SourceKey, List<String>) -> Unit,
    onSetCacheFullImageOnSave: (Boolean) -> Unit,
    onSetResolveUnknownAnimatedDurations: (Boolean) -> Unit,
    onSetScenarioPreset: (ScenarioPreset) -> Unit,
    onClearThumbnailCache: () -> Unit,
    onClearFullImageCache: () -> Unit,
    changelogLoading: Boolean,
    onOpenChangelog: () -> Unit,
) {
    var showClearCacheOptions by rememberSaveable { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var profileDeleteTarget by remember { mutableStateOf<RecommendationProfile?>(null) }
    var profilesExpanded by rememberSaveable { mutableStateOf(true) }
    var blacklistExpanded by rememberSaveable { mutableStateOf(true) }
    var unifiedModeExpanded by rememberSaveable { mutableStateOf(true) }
    var sourceAccountsExpanded by rememberSaveable { mutableStateOf(true) }
    var storageExpanded by rememberSaveable { mutableStateOf(true) }
    var updatesExpanded by rememberSaveable { mutableStateOf(true) }
    var developerScenariosExpanded by rememberSaveable { mutableStateOf(true) }

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
            expanded = profilesExpanded,
            onToggle = { profilesExpanded = !profilesExpanded },
        ) {
                recommendationProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = activeProfileId == profile.profileId,
                            onClick = { onSetActiveProfile(profile.profileId) },
                            label = { Text(profile.name) },
                        )
                        TextButton(
                            enabled = recommendationProfiles.size > 1,
                            onClick = {
                                profileDeleteTarget = profile
                            },
                        ) {
                            Text("Remove")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("New profile") },
                        singleLine = true,
                    )
                    Button(onClick = {
                        onAddProfile(newProfileName)
                        newProfileName = ""
                    }) {
                        Text("Add")
                    }
                }
                Text(
                    text = "Liked posts in active profile: $likesCount",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    enabled = likesCount > 0,
                    onClick = onClearLikesForActiveProfile,
                ) {
                    Text("Clear active profile likes")
                }
        }

        SettingsSection(
            title = "For You blacklist",
            expanded = blacklistExpanded,
            onToggle = { blacklistExpanded = !blacklistExpanded },
        ) {
                Text(
                    text = "Hidden tag sets for $activeProfileName",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (forYouBlacklistEntries.isEmpty()) {
                    Text(
                        text = "No blacklisted tags yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    forYouBlacklistEntries.forEach { entry ->
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
                                onClick = { onRemoveForYouBlacklistEntry(entry.source, entry.tags) },
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }
        }

        SettingsSection(
            title = "Unified mode",
            expanded = unifiedModeExpanded,
            onToggle = { unifiedModeExpanded = !unifiedModeExpanded },
        ) {
                availableSources.forEach { source ->
                    val isEnabled = source in settings.runtime.enabledSources
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(source.displayName())
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                val updated = settings.runtime.enabledSources
                                    .intersect(availableSources.toSet())
                                    .toMutableSet()
                                if (checked) {
                                    updated += source
                                } else {
                                    updated -= source
                                }
                                onSetEnabledSources(updated)
                            },
                        )
                    }
                    val weight = settings.runtime.sourceWeights[source]?.toFloat() ?: 0f
                    Slider(
                        value = weight,
                        onValueChange = { raw ->
                            val updated = settings.runtime.sourceWeights.toMutableMap()
                            updated[source] = raw.toDouble()
                            onSetSourceWeights(updated)
                        },
                        valueRange = 0f..1f,
                    )
                    Text("Weight: %.2f".format(weight), style = MaterialTheme.typography.bodySmall)
                }
        }

        SettingsSection(
            title = "Source Accounts",
            expanded = sourceAccountsExpanded,
            onToggle = { sourceAccountsExpanded = !sourceAccountsExpanded },
        ) {

                Text("Pixiv", style = MaterialTheme.typography.titleSmall)
                Text(pixivStatusLabel, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPixivConnect,
                        enabled = pixivConnectEnabled,
                    ) {
                        Text("Connect")
                    }
                    TextButton(onClick = onPixivDisconnect) {
                        Text("Disconnect")
                    }
                }

                Text("Gelbooru", style = MaterialTheme.typography.titleSmall)
                Text(gelbooruStatusLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = gelbooruUserId,
                    onValueChange = onGelbooruUserIdChange,
                    label = { Text("User ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = gelbooruApiKey,
                    onValueChange = onGelbooruApiKeyChange,
                    label = { Text("API Key") },
                    supportingText = {
                        Text("Paste key or &api_key=<key>&user_id=<id> to auto-fill.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveGelbooruCredentials) {
                        Text("Save")
                    }
                    TextButton(onClick = onClearGelbooruCredentials) {
                        Text("Clear")
                    }
                }

                Text("rule34.xxx", style = MaterialTheme.typography.titleSmall)
                Text(rule34XxxStatusLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = rule34XxxUserId,
                    onValueChange = onRule34XxxUserIdChange,
                    label = { Text("User ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = rule34XxxApiKey,
                    onValueChange = onRule34XxxApiKeyChange,
                    label = { Text("API Key") },
                    supportingText = {
                        Text("Paste key or &api_key=<key>&user_id=<id> to auto-fill.")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveRule34XxxCredentials) {
                        Text("Save")
                    }
                    TextButton(onClick = onClearRule34XxxCredentials) {
                        Text("Clear")
                    }
                }
        }

        SettingsSection(
            title = "Storage & caching",
            expanded = storageExpanded,
            onToggle = { storageExpanded = !storageExpanded },
            contentSpacing = 8.dp,
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cache full image on save")
                    Switch(
                        checked = settings.cache.cacheFullImageOnSave,
                        onCheckedChange = onSetCacheFullImageOnSave,
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
                        checked = settings.contentFilters.resolveUnknownAnimatedDurations,
                        onCheckedChange = onSetResolveUnknownAnimatedDurations,
                    )
                }
                Text("Thumbnails: ${cacheSnapshot.thumbnailCount}")
                Text("Full images: ${cacheSnapshot.fullImageCount}")
                Button(onClick = { showClearCacheOptions = !showClearCacheOptions }) {
                    Text(if (showClearCacheOptions) "Clear cache ▲" else "Clear cache ▼")
                }
                if (showClearCacheOptions) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onClearThumbnailCache()
                                showClearCacheOptions = false
                            },
                        ) {
                            Text("Clear thumbnail cache")
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onClearFullImageCache()
                                showClearCacheOptions = false
                            },
                            enabled = cacheSnapshot.fullImageCount > 0,
                        ) {
                            Text("Clear full image cache")
                        }
                    }
                }
        }

        SettingsSection(
            title = "Updates",
            expanded = updatesExpanded,
            onToggle = { updatesExpanded = !updatesExpanded },
            contentSpacing = 8.dp,
        ) {
                Text(
                    if (changelogLoading) {
                        "Loading release history..."
                    } else {
                        "View changelog history for available pre-releases."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpenChangelog,
                    enabled = !changelogLoading,
                ) {
                    Text("Open changelog")
                }
        }

        if (showDeveloperScenarios) {
            SettingsSection(
                title = "Developer scenarios",
                expanded = developerScenariosExpanded,
                onToggle = { developerScenariosExpanded = !developerScenariosExpanded },
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
                                selected = settings.scenarioPreset == scenario,
                                onClick = { onSetScenarioPreset(scenario) },
                                label = { Text(label) },
                            )
                        }
                    }
            }
        }

        val profileToDelete = profileDeleteTarget
        if (profileToDelete != null) {
            AlertDialog(
                onDismissRequest = { profileDeleteTarget = null },
                title = { Text("Delete Profile?") },
                text = {
                    Text(
                        "Delete profile \"${profileToDelete.name}\" and its likes/codex data? This cannot be undone.",
                    )
                },
                dismissButton = {
                    TextButton(onClick = { profileDeleteTarget = null }) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRemoveProfile(profileToDelete.profileId)
                            profileDeleteTarget = null
                        },
                    ) {
                        Text("Delete")
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
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
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
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
