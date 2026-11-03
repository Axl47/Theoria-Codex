package com.theoriacodex.app.viewer

import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.motion.TheoriaMotion
import com.theoriacodex.app.viewer.state.ViewerMediaError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun FittedSeekJumpFeedbackOverlay(
    feedback: SeekJumpFeedback?,
    aspectRatio: Float?,
    onExpired: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return

    FittedViewerMediaOverlay(aspectRatio, modifier) {
        SeekJumpFeedbackOverlay(feedback, onExpired, Modifier.fillMaxSize().clipToBounds())
    }
}

/** Overlay coordinates follow the fitted media rather than the full screen's letterboxing. */
@Composable
internal fun FittedViewerMediaOverlay(
    aspectRatio: Float?,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fitted = if (aspectRatio == null) Modifier.fillMaxSize()
        else if (maxHeight > 0.dp && maxWidth / maxHeight > aspectRatio) {
            Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        } else Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        Box(fitted, content = content)
    }
}

@Composable
private fun SeekJumpFeedbackOverlay(
    feedback: SeekJumpFeedback?,
    onExpired: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (feedback == null) return

    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.92f) }
    val cascade = remember { Animatable(0f) }
    val direction = feedback.direction
    val label = remember(feedback.totalDeltaMs) {
        formatSeekJumpFeedback(feedback.totalDeltaMs)
    }

    LaunchedEffect(feedback.serial) {
        alpha.snapTo(0f)
        scale.snapTo(0.92f)
        cascade.snapTo(0f)
        launch { cascade.animateTo(1f, tween(TheoriaMotion.SCREEN_MS)) }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(TheoriaMotion.SHORT_MS),
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(TheoriaMotion.SHORT_MS),
            )
        }
        delay(750L)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(TheoriaMotion.MEDIUM_MS),
        )
        onExpired(feedback.serial)
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
        },
    ) {
        SeekFeedbackBackdrop(direction, Modifier.matchParentSize())
        SeekFeedbackLabel(label, direction, { cascade.value }, Modifier.align(
            if (direction == SeekJumpDirection.Backward) Alignment.CenterStart else Alignment.CenterEnd,
        ))
    }
}

@Composable
private fun SeekFeedbackLabel(label: String, direction: SeekJumpDirection, cascade: () -> Float, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().width(132.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        SeekFeedbackChevrons(direction, cascade)
        Text(
            text = label,
            color = Color.White,
            style = ViewerVeil.LargeReadout,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Three chevrons light up in the direction of travel, ending brightest at the leading one. */
@Composable
private fun SeekFeedbackChevrons(direction: SeekJumpDirection, cascade: () -> Float) {
    Canvas(Modifier.size(width = 54.dp, height = 28.dp)) {
        val forward = direction == SeekJumpDirection.Forward
        val step = size.width / 3f
        val halfWidth = step * 0.3f
        val halfHeight = size.height * 0.3f
        repeat(3) { index ->
            val fade = (cascade() * 3f - index).coerceIn(0f, 1f) * (0.5f + index * 0.25f)
            val x = if (forward) step * (index + 0.5f) else size.width - step * (index + 0.5f)
            val tip = if (forward) halfWidth else -halfWidth
            val chevron = Path().apply {
                moveTo(x - tip, center.y - halfHeight)
                lineTo(x + tip, center.y)
                lineTo(x - tip, center.y + halfHeight)
            }
            drawPath(chevron, Color.White.copy(alpha = fade), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun SeekFeedbackBackdrop(direction: SeekJumpDirection, modifier: Modifier) {
    Canvas(modifier) {
        val washWidth = size.width * 0.46f
        val backward = direction == SeekJumpDirection.Backward
        val stops = arrayOf(
            0f to Color.Black.copy(alpha = 0.58f), 0.35f to Color.Black.copy(alpha = 0.4f), 0.65f to Color.Black.copy(alpha = 0.16f),
            0.88f to Color.Black.copy(alpha = 0.03f), 1f to Color.Transparent,
        )
        drawRect(
            brush = if (backward) {
                Brush.horizontalGradient(*stops, endX = washWidth)
            } else {
                Brush.horizontalGradient(*stops, startX = size.width, endX = size.width - washWidth)
            },
            topLeft = Offset(if (backward) 0f else size.width - washWidth, 0f),
            size = Size(washWidth, size.height),
        )
    }
}

@Composable
internal fun ViewerMediaErrorOverlay(
    error: ViewerMediaError,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            if (error is ViewerMediaError.Recoverable) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
