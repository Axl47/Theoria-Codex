package com.theoriacodex.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A liked heart's color, wherever a like button shows. */
internal val LikedHeartColor = Color(0xFFFF6B81)

/** Tap feedback for every like button: a haptic tick each tap, and a springy pop when liking. */
@Stable
internal class LikeFeedback(private val haptics: HapticFeedback, private val scope: CoroutineScope) {
    private val animatedScale = Animatable(1f)

    /** The heart's current scale; read it in the draw phase (see [likePop]). */
    val scale: Float get() = animatedScale.value

    /** Call from the tap, not from state, so scrolling or swiping onto an already liked post stays still. */
    fun tapped(liked: Boolean) {
        haptics.performHapticFeedback(if (liked) TheoriaHaptics.ToggleOff else TheoriaHaptics.ToggleOn)
        if (!liked) scope.launch {
            animatedScale.snapTo(POP_START_SCALE)
            animatedScale.animateTo(1f, TheoriaMotion.pop)
        }
    }
}

/** Scales the heart by [feedback]'s pop in the draw phase, so the animation never recomposes its card. */
internal fun Modifier.likePop(feedback: LikeFeedback): Modifier = graphicsLayer {
    scaleX = feedback.scale
    scaleY = feedback.scale
}

@Composable
internal fun rememberLikeFeedback(): LikeFeedback {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember(haptics, scope) { LikeFeedback(haptics, scope) }
}

private const val POP_START_SCALE = 0.6f
