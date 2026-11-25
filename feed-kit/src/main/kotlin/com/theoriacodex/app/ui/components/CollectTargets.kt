package com.theoriacodex.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.codex.CodexCoverCandidate
import com.theoriacodex.app.codex.CodexCoverImage
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.ui.theme.TextOverMediaShadow
import kotlin.math.roundToInt

sealed interface CollectTargetVisual {
    data object Busy : CollectTargetVisual
    data class Icon(val icon: ImageVector) : CollectTargetVisual
    data class Cover(val candidates: List<CodexCoverCandidate>, val fallback: ImageVector) : CollectTargetVisual
}

@Immutable
data class CollectTarget<out T>(val key: String, val label: String, val visual: CollectTargetVisual, val payload: T)

sealed interface CollectTargetsLayout {
    data class EdgeColumn(val atEnd: Boolean = true) : CollectTargetsLayout

    data class EdgeRow(val atTop: Boolean) : CollectTargetsLayout

    data class Rising(val anchor: Offset) : CollectTargetsLayout
}

@Stable
class CollectTargetsState<T> {
    private val bounds = mutableStateMapOf<String, Rect>()

    var hovered: CollectTarget<T>? by mutableStateOf(null)
        internal set

    fun targetAt(targets: List<CollectTarget<T>>, pointer: Offset, slop: Float = 0f): CollectTarget<T>? =
        targets.firstOrNull { target -> bounds[target.key]?.inflate(slop)?.contains(pointer) == true }

    internal fun place(key: String, rect: Rect) {
        bounds[key] = rect
    }
}

@Immutable
data class ArmedColors(val container: Color, val content: Color)

@Composable
fun armedColors(armed: Boolean): ArmedColors {
    val colors = MaterialTheme.colorScheme
    return if (armed) ArmedColors(colors.primaryContainer, colors.onPrimaryContainer) else ArmedColors(colors.surfaceContainerHigh, colors.onSurface)
}

@Composable
fun <T> rememberCollectTargetsState(): CollectTargetsState<T> = remember { CollectTargetsState() }

@Composable
fun <T> CollectTargets(
    targets: List<CollectTarget<T>>,
    pointer: Offset?,
    layout: CollectTargetsLayout,
    modifier: Modifier = Modifier,
    state: CollectTargetsState<T> = rememberCollectTargetsState(),
) {
    val slop = with(LocalDensity.current) { HIT_SLOP.toPx() }
    val hovered = pointer?.let { state.targetAt(targets, it, slop) }
    SideEffect { state.hovered = hovered }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(hovered?.key) { if (hovered != null) haptics.performHapticFeedback(TheoriaHaptics.Threshold) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val stack: @Composable (Modifier, List<CollectTarget<T>>) -> Unit = { placement, shown ->
            Column(placement, Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                shown.forEach { target -> CollectTargetBubble(target, target.key == hovered?.key, state) }
            }
        }
        when (layout) {
            is CollectTargetsLayout.EdgeColumn -> stack(
                Modifier.align(if (layout.atEnd) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 12.dp),
                targets,
            )
            is CollectTargetsLayout.EdgeRow -> {
                val labelWidth = minOf(LABEL_WIDTH, (maxWidth - ROW_GAP * (targets.size - 1)) / targets.size.coerceAtLeast(1))
                Row(
                    Modifier.align(if (layout.atTop) Alignment.TopCenter else Alignment.BottomCenter).padding(vertical = 12.dp),
                    Arrangement.spacedBy(ROW_GAP),
                    Alignment.Top,
                ) {
                    targets.forEach { target -> CollectTargetBubble(target, target.key == hovered?.key, state, labelWidth) }
                }
            }
            is CollectTargetsLayout.Rising -> stack(Modifier.risingFrom(layout.anchor - origin), targets.asReversed())
        }
    }
}

private fun Modifier.risingFrom(anchor: Offset): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val gap = RISING_GAP.roundToPx()
        placeable.place((anchor.x - placeable.width / 2f).roundToInt(), (anchor.y - placeable.height - gap).roundToInt())
    }
}

@Composable
private fun <T> CollectTargetBubble(
    target: CollectTarget<T>,
    hovered: Boolean,
    state: CollectTargetsState<T>,
    labelWidth: Dp = LABEL_WIDTH,
) {
    val scale by animateFloatAsState(if (hovered) HOVERED_SCALE else 1f, label = "collect-target-scale")
    val fill = armedColors(hovered)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = fill.container,
            contentColor = fill.content,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(TARGET_SIZE)
                .testTag("Collect target ${target.key}")
                .onGloballyPositioned { state.place(target.key, it.boundsInWindow()) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (hovered) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
        ) {
            Box(Modifier.padding(if (hovered) ARMED_INSET else 0.dp), contentAlignment = Alignment.Center) {
                when (val visual = target.visual) {
                    CollectTargetVisual.Busy -> CircularProgressIndicator(Modifier.size(TARGET_SIZE / 2), strokeWidth = 3.dp)
                    is CollectTargetVisual.Icon -> Icon(visual.icon, contentDescription = null)
                    is CollectTargetVisual.Cover -> CodexCoverImage(visual.candidates, null, Modifier.fillMaxSize().clip(CircleShape)) {
                        Icon(visual.fallback, contentDescription = null)
                    }
                }
            }
        }
        Text(
            target.label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(shadow = TextOverMediaShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = labelWidth).padding(top = 4.dp),
        )
    }
}

private val TARGET_SIZE = 56.dp
private val LABEL_WIDTH = TARGET_SIZE + 16.dp
private val ROW_GAP = 12.dp
private val HIT_SLOP = 12.dp
private val ARMED_INSET = 6.dp
private val RISING_GAP = 12.dp
private const val HOVERED_SCALE = 1.2f
