package com.theoriacodex.app.viewer

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
    val direction = feedback.direction
    val label = remember(feedback.totalDeltaMs) {
        formatSeekJumpFeedback(feedback.totalDeltaMs)
    }

    LaunchedEffect(feedback.serial) {
        alpha.snapTo(0f)
        scale.snapTo(0.92f)
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 90),
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140),
            )
        }
        delay(750L)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 180),
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
        SeekFeedbackLabel(label, Modifier.align(
            if (direction == SeekJumpDirection.Backward) Alignment.CenterStart else Alignment.CenterEnd,
        ))
    }
}

@Composable
private fun SeekFeedbackLabel(label: String, modifier: Modifier) {
    Box(modifier.fillMaxHeight().width(112.dp), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun SeekFeedbackBackdrop(direction: SeekJumpDirection, modifier: Modifier) {
    Canvas(modifier) {
        val horizontalRadius = (maxOf(size.height / 2f, size.width / 2f) * 0.30f)
            .coerceAtLeast(96.dp.toPx())
        val centerX = if (direction == SeekJumpDirection.Backward) 0f else size.width
        drawOval(
            color = Color.Black.copy(alpha = 0.50f),
            topLeft = Offset(centerX - horizontalRadius, 0f),
            size = Size(horizontalRadius * 2f, size.height),
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
