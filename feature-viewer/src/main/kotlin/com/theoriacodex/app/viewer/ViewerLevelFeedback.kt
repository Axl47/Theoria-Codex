package com.theoriacodex.app.viewer

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
    Box(modifier) { ViewerLevelSurface(shown) { this.alpha = alpha } }
}

/** Each level appears on the edge its gesture starts from: brightness leading, volume trailing. */
@Composable
private fun BoxScope.ViewerLevelSurface(shown: ViewerLevelFeedback, layer: GraphicsLayerScope.() -> Unit) {
    val brightness = shown.control == ViewerLevelControl.Brightness
    Surface(
        modifier = Modifier.align(if (brightness) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = 12.dp).graphicsLayer(layer).semantics(mergeDescendants = true) {
                contentDescription = if (brightness) "Brightness" else "Media volume"
                stateDescription = shown.label
            },
        color = ViewerVeil.Capsule, contentColor = Color.White, shape = RoundedCornerShape(17.dp),
    ) {
        if (shown.available) ViewerLevelRail(shown) else ViewerLevelUnavailable(shown)
    }
}

@Composable
private fun ViewerLevelUnavailable(shown: ViewerLevelFeedback) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ViewerLevelIcon(shown.control, shown.fraction, Color.White, Modifier.size(18.dp))
        Text(shown.label, style = ViewerVeil.Time, maxLines = 1)
    }
}

/** The icon flips to black once the white fill rises behind it, and stays white below that. */
@Composable
private fun ViewerLevelRail(shown: ViewerLevelFeedback) {
    Column(
        Modifier.size(width = 34.dp, height = 216.dp).padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(shown.label, style = ViewerVeil.Time.copy(fontSize = 11.sp), maxLines = 1, softWrap = false)
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(shown.fraction.coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(Color.White))
            ViewerLevelIcon(
                shown.control, shown.fraction, if (shown.fraction >= LEVEL_ICON_COVERED_FRACTION) Color.Black else Color.White,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(18.dp),
            )
        }
    }
}

private const val LEVEL_ICON_COVERED_FRACTION = 0.14f

/** Drawn from the actual level: a filling sun, or a speaker with up to three bars and a mute slash. */
@Composable
private fun ViewerLevelIcon(control: ViewerLevelControl, fraction: Float, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.3.dp.toPx()
        if (control == ViewerLevelControl.Brightness) {
            val radius = size.minDimension * 0.23f
            repeat(8) { ray ->
                val angle = ray * PI / 4
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(tint, center + direction * (radius + stroke * 2),
                    center + direction * (size.minDimension * 0.47f), stroke, StrokeCap.Round)
            }
            drawCircle(tint, radius, style = Stroke(stroke))
            clipRect(left = center.x - radius, top = center.y + radius - radius * 2 * fraction,
                right = center.x + radius, bottom = center.y + radius) { drawCircle(tint, radius) }
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
            drawPath(speaker, tint, style = Stroke(stroke))
            if (fraction <= 0f) {
                drawLine(tint, Offset(size.width * 0.12f, size.height * 0.9f),
                    Offset(size.width * 0.86f, size.height * 0.1f), stroke, StrokeCap.Round)
            } else {
                val bars = if (fraction <= 1f / 3) 1 else if (fraction <= 2f / 3) 2 else 3
                repeat(bars) { bar ->
                    val radius = size.width * (0.18f + bar * 0.15f)
                    drawArc(tint, -50f, 100f, false,
                        topLeft = Offset(size.width * 0.4f - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
    }
}
