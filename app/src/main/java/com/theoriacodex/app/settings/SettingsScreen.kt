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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.data.repository.AppSettings
import com.theoriacodex.data.repository.CacheSnapshot
import com.theoriacodex.data.repository.ScenarioPreset
import com.theoriacodex.domain.model.SourceKey

@Composable
fun SettingsScreen(
    settings: AppSettings,
    cacheSnapshot: CacheSnapshot,
    onSetEnabledSources: (Set<SourceKey>) -> Unit,
    onSetSourceWeights: (Map<SourceKey, Double>) -> Unit,
    onSetCacheFullImageOnSave: (Boolean) -> Unit,
    onSetScenarioPreset: (ScenarioPreset) -> Unit,
    onClearThumbnailCache: () -> Unit,
    onClearFullImageCache: () -> Unit,
) {
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("General", style = MaterialTheme.typography.titleMedium)
                Text("Apply button required", style = MaterialTheme.typography.bodyMedium)
                Text("Portrait-only mode in MVP", style = MaterialTheme.typography.bodySmall)
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
                SourceKey.entries.forEach { source ->
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
                                val updated = settings.runtime.enabledSources.toMutableSet()
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onClearThumbnailCache) {
                        Text("Clear thumbnail cache")
                    }
                    TextButton(
                        onClick = onClearFullImageCache,
                        enabled = cacheSnapshot.fullImageCount > 0,
                    ) {
                        Text("Clear full-image cache")
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
