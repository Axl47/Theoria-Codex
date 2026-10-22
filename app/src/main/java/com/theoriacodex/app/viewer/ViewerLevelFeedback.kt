package com.theoriacodex.app.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ViewerLevelFeedbackOverlay(controls: ViewerLevelControls, modifier: Modifier = Modifier) {
    val feedback = controls.feedback
    var lastFeedback by remember { mutableStateOf<ViewerLevelFeedback?>(null) }
    LaunchedEffect(feedback) { if (feedback != null) lastFeedback = feedback }
    LaunchedEffect(feedback, controls.dragging) {
        if (feedback != null && !controls.dragging) {
            delay(1_000L)
            controls.clearFeedback()
        }
    }
    val alpha by animateFloatAsState(if (feedback != null) 1f else 0f, label = "Viewer level feedback")
    val shown = feedback ?: lastFeedback ?: return
    if (alpha <= 0f && feedback == null) return
    Surface(
        modifier = modifier.graphicsLayer { this.alpha = alpha }.semantics(mergeDescendants = true) {
            contentDescription = if (shown.control == ViewerLevelControl.Brightness) "Brightness" else "Media volume"
            stateDescription = shown.label
        },
        color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White, shape = CircleShape,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerLevelIcon(shown.control, shown.fraction)
            if (shown.available) {
                LinearProgressIndicator(
                    progress = { shown.fraction }, modifier = Modifier.width(144.dp).height(4.dp),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.3f), drawStopIndicator = {},
                )
            }
            Text(shown.label, Modifier.widthIn(min = 36.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

/** Drawn from the actual level: a filling sun, or a speaker with up to three bars and a mute slash. */
@Composable
private fun ViewerLevelIcon(control: ViewerLevelControl, fraction: Float) {
    Canvas(Modifier.size(26.dp)) {
        val stroke = 1.8.dp.toPx()
        if (control == ViewerLevelControl.Brightness) {
            val radius = size.minDimension * 0.23f
            repeat(8) { ray ->
                val angle = ray * PI / 4
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(Color.White, center + direction * (radius + stroke * 2),
                    center + direction * (size.minDimension * 0.47f), stroke, StrokeCap.Round)
            }
            drawCircle(Color.White, radius, style = Stroke(stroke))
            clipRect(left = center.x - radius, top = center.y + radius - radius * 2 * fraction,
                right = center.x + radius, bottom = center.y + radius) { drawCircle(Color.White, radius) }
        } else {
            val speaker = Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.38f)
                lineTo(size.width * 0.25f, size.height * 0.38f)
                lineTo(size.width * 0.45f, size.height * 0.2f)
                lineTo(size.width * 0.45f, size.height * 0.8f)
                lineTo(size.width * 0.25f, size.height * 0.62f)
                lineTo(size.width * 0.08f, size.height * 0.62f)
                close()
            }
            drawPath(speaker, Color.White, style = Stroke(stroke))
            if (fraction <= 0f) {
                drawLine(Color.White, Offset(size.width * 0.12f, size.height * 0.9f),
                    Offset(size.width * 0.86f, size.height * 0.1f), stroke, StrokeCap.Round)
            } else {
                val bars = if (fraction <= 1f / 3) 1 else if (fraction <= 2f / 3) 2 else 3
                repeat(bars) { bar ->
                    val radius = size.width * (0.18f + bar * 0.15f)
                    drawArc(Color.White, -50f, 100f, false,
                        topLeft = Offset(size.width * 0.4f - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
    }
}
