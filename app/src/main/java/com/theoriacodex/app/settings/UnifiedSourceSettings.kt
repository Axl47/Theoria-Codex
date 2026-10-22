package com.theoriacodex.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.source.displayName
import com.theoriacodex.data.repository.SourceRuntimeSettings
import com.theoriacodex.data.repository.SourceWeightSettings
import com.theoriacodex.domain.model.SourceKey
import kotlin.math.roundToInt

/** Search owns membership; Settings only edits the current group's relative weights. */
@Composable
internal fun UnifiedSourceSettings(
    sources: Set<SourceKey>,
    runtime: SourceRuntimeSettings,
    onWeightsChanged: (Map<SourceKey, Double>) -> Unit,
) {
    val enabled = sources.size > 1
    val weights = SourceWeightSettings.weightsFor(runtime, sources)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Unified Mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "Activate Unified by holding down sources in Search.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!enabled) {
                Text("Select multiple sources to adjust their weights.", style = MaterialTheme.typography.bodySmall)
            } else {
                sources.sortedBy { it.ordinal }.forEach { source ->
                    val weight = weights.getValue(source).toFloat()
                    var draftWeight by remember(sources, source, weight) { mutableFloatStateOf(weight) }
                    Text(source.displayName())
                    Slider(
                        modifier = Modifier.semantics { contentDescription = "${source.displayName()} weight" },
                        value = draftWeight,
                        onValueChange = { draftWeight = (it * 20).roundToInt() / 20f },
                        onValueChangeFinished = {
                            onWeightsChanged(weights + (source to draftWeight.toDouble()))
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                    )
                    Text("Weight: %.2f".format(draftWeight), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
