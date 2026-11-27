package com.theoriacodex.app.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.SubtitleAdjustment
import com.theoriacodex.domain.model.SubtitleTuning
import kotlinx.coroutines.delay
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.ViewerSubtitleTuningPanel(
    tuning: SubtitleTuning,
    onSize: (SubtitleAdjustment) -> Unit,
    onDelay: (SubtitleAdjustment) -> Unit,
    onDismiss: () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler(onBack = onDismiss)
    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            .testTag(VIEWER_SUBTITLE_TUNING_TAG),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TuningStepper(
                label = "Size",
                value = "${tuning.sizePercent}%",
                lower = StepButton("Smaller subtitles", tuning.sizePercent > SubtitleTuning.MIN_SIZE_PERCENT),
                higher = StepButton("Larger subtitles", tuning.sizePercent < SubtitleTuning.MAX_SIZE_PERCENT),
                onAdjust = onSize,
            )
            TuningStepper(
                label = "Delay",
                value = formatSubtitleDelay(tuning.delayMs),
                lower = StepButton("Show subtitles earlier", tuning.delayMs > -SubtitleTuning.MAX_DELAY_MS),
                higher = StepButton("Show subtitles later", tuning.delayMs < SubtitleTuning.MAX_DELAY_MS),
                onAdjust = onDelay,
            )
            if (!tuning.isDefault) {
                IconButton(onClick = { onSize(SubtitleAdjustment.Reset); onDelay(SubtitleAdjustment.Reset) }) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset subtitle size and delay")
                }
            }
        }
    }
}

private class StepButton(val description: String, val enabled: Boolean)

@Composable
private fun TuningStepper(
    label: String,
    value: String,
    lower: StepButton,
    higher: StepButton,
    onAdjust: (SubtitleAdjustment) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.widthIn(min = 44.dp), style = MaterialTheme.typography.labelLarge)
        RepeatingStepButton(Icons.Default.Remove, lower) { onAdjust(SubtitleAdjustment.Step(-1)) }
        Text(
            text = value,
            modifier = Modifier.widthIn(min = 56.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        RepeatingStepButton(Icons.Default.Add, higher) { onAdjust(SubtitleAdjustment.Step(1)) }
    }
}

@Composable
private fun RepeatingStepButton(icon: ImageVector, button: StepButton, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val latestStep by rememberUpdatedState(onStep)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, button.enabled) {
        if (!pressed || !button.enabled) {
            repeated = false
            return@LaunchedEffect
        }
        repeated = false
        delay(STEP_REPEAT_START_MS)
        repeated = true
        while (true) {
            latestStep()
            delay(STEP_REPEAT_INTERVAL_MS)
        }
    }
    FilledTonalIconButton(
        onClick = { if (repeated) repeated = false else latestStep() },
        enabled = button.enabled,
        interactionSource = interaction,
    ) {
        Icon(icon, contentDescription = button.description, modifier = Modifier.size(20.dp))
    }
}

internal fun formatSubtitleDelay(delayMs: Long): String {
    val sign = when {
        delayMs > 0L -> "+"
        delayMs < 0L -> "−"
        else -> ""
    }
    val tenths = abs(delayMs) / MS_PER_TENTH
    return "$sign${tenths / TENTHS}.${tenths % TENTHS} s"
}

internal fun formatSubtitleTuning(tuning: SubtitleTuning): String = "${tuning.sizePercent}% · ${formatSubtitleDelay(tuning.delayMs)}"

internal const val VIEWER_SUBTITLE_TUNING_TAG = "viewer-subtitle-tuning"
private const val PANEL_ALPHA = 0.9f
private const val TENTHS = 10L
private const val MS_PER_TENTH = 100L
private const val STEP_REPEAT_START_MS = 400L
private const val STEP_REPEAT_INTERVAL_MS = 80L
