package com.theoriacodex.app.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Equal-spaced stops select the supported rates, including the non-uniform 0.2x stop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackRateSlider(
    rate: ViewerPlaybackRate,
    onRateSelected: (ViewerPlaybackRate) -> Unit,
) {
    val rates = ViewerPlaybackRate.entries
    val selectedIndex = rates.indexOf(rate)
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.width(280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Speed: ${rate.menuLabel}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { value -> onRateSelected(rates[value.roundToInt().coerceIn(rates.indices)]) },
            valueRange = 0f..rates.lastIndex.toFloat(),
            steps = rates.size - 2,
            modifier = Modifier.semantics {
                contentDescription = "Playback speed"
                stateDescription = rate.contentDescription
            },
            track = { sliderState ->
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    val fraction = sliderState.value / rates.lastIndex
                    drawLine(inactiveColor, Offset(0f, center.y), Offset(size.width, center.y), 4.dp.toPx())
                    drawLine(activeColor, Offset(0f, center.y), Offset(size.width * fraction, center.y), 4.dp.toPx())
                    rates.indices.forEach { index ->
                        val x = size.width * index / rates.lastIndex
                        drawLine(
                            if (index <= sliderState.value) activeColor else inactiveColor,
                            Offset(x, center.y - 6.dp.toPx()), Offset(x, center.y + 6.dp.toPx()), 2.dp.toPx(),
                        )
                    }
                }
            },
        )
    }
}
