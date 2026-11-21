package com.theoriacodex.app.lift

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.lerp
import com.theoriacodex.app.search.SearchResultCard
import com.theoriacodex.app.viewer.PixivUgoiraClient

/** The shell's Pixiv animation client, so a lifted ugoira card plays as its grid card does. */
val LocalLiftUgoiraClient = staticCompositionLocalOf<PixivUgoiraClient?> { null }

/**
 * The card's copy under the finger. While lifted it follows [LiftSession.pointer] in layout only; once
 * released it shrinks into the target it was dropped on, or returns to its card, as [land] runs from 0 to 1.
 */
@Composable
internal fun LiftedCopy(session: LiftSession, lift: LiftedPost, settling: LiftPhase.Settling?, land: Animatable<Float, *>) {
    val bounds = lift.cardBounds
    val size = with(LocalDensity.current) { DpSize(bounds.width.toDp(), bounds.height.toDp()) }
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .offset { copyTopLeft(lift, settling?.at ?: session.pointer, settling, land.value).round() }
            .size(size)
            .graphicsLayer {
                val dropped = settling?.action != null
                val progress = if (settling == null) 0f else land.value
                val scale = lerp(LIFTED_SCALE, if (dropped) DROPPED_SCALE else 1f, progress)
                scaleX = scale
                scaleY = scale
                alpha = if (dropped) 1f - progress else 1f
                shadowElevation = LIFTED_ELEVATION.toPx()
                this.shape = shape
                clip = true
            }
            // A picture of the card, not a second one: TalkBack and tests keep finding the original only.
            .clearAndSetSemantics {},
    ) { LiftedCard(lift, bounds.width / bounds.height) }
}

/** The grid's own card, so an animated or video post keeps playing under the finger. */
@Composable
internal fun LiftedCard(lift: LiftedPost, aspectRatio: Float) {
    SearchResultCard(
        post = lift.post,
        pixivUgoiraClient = LocalLiftUgoiraClient.current,
        showTitle = false,
        mediaAspectRatio = aspectRatio,
        liked = lift.like?.liked == true,
        onClick = {},
    )
}

/** Where the copy's top-left sits: under the finger while lifted, then easing to where it lands. */
private fun copyTopLeft(lift: LiftedPost, pointer: Offset, settling: LiftPhase.Settling?, progress: Float): Offset {
    val bounds = lift.cardBounds
    val held = bounds.topLeft + (pointer - lift.origin)
    if (settling == null) return held
    val landing = if (settling.action != null) settling.at - bounds.size.center else bounds.topLeft
    return lerp(held, landing, progress)
}

private const val LIFTED_SCALE = 1.05f
private const val DROPPED_SCALE = 0.15f
private val LIFTED_ELEVATION = 16.dp
