package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.domain.model.SourceKey

@Composable
fun SettingsScreen(
    settings: AppSettings,
    availableSources: List<SourceKey>,
    cacheSnapshot: CacheSnapshot,
    showDeveloperScenarios: Boolean,
    pixivStatusLabel: String,
    onPixivConnect: () -> Unit,
    onPixivDisconnect: () -> Unit,
    gelbooruUserId: String,
    gelbooruApiKey: String,
    gelbooruStatusLabel: String,
    onGelbooruUserIdChange: (String) -> Unit,
    onGelbooruApiKeyChange: (String) -> Unit,
    onSaveGelbooruCredentials: () -> Unit,
    onClearGelbooruCredentials: () -> Unit,
    onSetEnabledSources: (Set<SourceKey>) -> Unit,
    onSetSourceWeights: (Map<SourceKey, Double>) -> Unit,
    onSetCacheFullImageOnSave: (Boolean) -> Unit,
    onSetScenarioPreset: (ScenarioPreset) -> Unit,
    onClearThumbnailCache: () -> Unit,
    onClearFullImageCache: () -> Unit,
    changelogLoading: Boolean,
    onOpenChangelog: () -> Unit,
) {
    var showClearCacheOptions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(source.name)
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
                    Button(onClick = onPixivConnect) {
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
    }
}
