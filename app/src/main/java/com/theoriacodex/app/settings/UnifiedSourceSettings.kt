package com.theoriacodex.app.settings

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

/** Search owns membership; Settings only edits the current group's relative weights. */
@Composable
internal fun UnifiedSourceSettings(
    sources: Set<SourceKey>,
    runtime: SourceRuntimeSettings,
    expanded: Boolean = true,
    onToggle: () -> Unit = {},
    onWeightsChanged: (Map<SourceKey, Double>) -> Unit,
) {
    // Keep the entire slider group responsive while repository writes complete asynchronously.
    var weights by remember(sources) { mutableStateOf(SourceWeightSettings.weightsFor(runtime, sources)) }
    SettingsSection(title = "Unified Mode", expanded = expanded, onToggle = onToggle) {
        Text(
            "Activate Unified by holding down sources in Search.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (sources.size > 1) {
            sources.sortedBy { it.ordinal }.forEach { source ->
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
                Text("Weight: %.2f".format(weight), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
