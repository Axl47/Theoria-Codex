package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ForYouBlacklistEntry
import com.theoriacodex.data.repository.ProviderHealthSnapshot
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
    providerHealth: Map<SourceKey, ProviderHealthSnapshot> = emptyMap(),
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
    var showClearCacheOptions by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var profileDeleteTarget by remember { mutableStateOf<RecommendationProfile?>(null) }

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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Recommendation Profiles", style = MaterialTheme.typography.titleMedium)
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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("For You blacklist", style = MaterialTheme.typography.titleMedium)
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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Unified mode", style = MaterialTheme.typography.titleMedium)
                availableSources.forEach { source ->
                    val isEnabled = source in settings.runtime.enabledSources
                    val health = providerHealth[source]
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
                    Text(
                        text = formatProviderHealthLine(health),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Source Accounts", style = MaterialTheme.typography.titleMedium)

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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Storage & caching", style = MaterialTheme.typography.titleMedium)
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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Updates", style = MaterialTheme.typography.titleMedium)
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
        }

        if (showDeveloperScenarios) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Developer scenarios", style = MaterialTheme.typography.titleMedium)
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

private fun formatProviderHealthLine(snapshot: ProviderHealthSnapshot?): String {
    if (snapshot == null) return "Not checked"
    val latency = snapshot.latencyMs?.let { "${it}ms" }
    val detail = listOfNotNull(latency, snapshot.failureReason, snapshot.message)
        .take(2)
        .joinToString(" - ")
    return if (detail.isBlank()) {
        snapshot.status.name
    } else {
        "${snapshot.status.name} - $detail"
    }
}
