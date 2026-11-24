package com.theoriacodex.app.settings

import com.theoriacodex.data.repository.SettingDestinations
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.model.SourceKey

@Composable
fun UnifiedSourceSettings(
    sources: Set<SourceKey>,
    runtime: SourceRuntimeSettings,
    onWeightsChanged: (Map<SourceKey, Double>) -> Unit,
) {
    var weights by remember(sources) { mutableStateOf(SourceWeightSettings.weightsFor(runtime, sources)) }
    Text(SettingDestinations.SourceMix.title, style = MaterialTheme.typography.titleMedium)
    if (sources.size < 2) {
        Text("Hold a source in the picker to add it.", style = MaterialTheme.typography.bodySmall)
    } else {
        sources.sorted().forEach { source ->
            val weight = weights.getValue(source).toFloat()
            Text(source.displayName())
            Slider(
                modifier = Modifier.semantics { contentDescription = "${source.displayName()} weight" },
                value = weight,
                onValueChange = { value ->
                    val updated = SourceWeightSettings.redistribute(weights, source, value.toDouble())
                    if (updated != weights) {
                        weights = updated
                        onWeightsChanged(updated)
                    }
                },
                valueRange = 0f..1f,
                steps = 19,
            )
            Text("${(weight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}
