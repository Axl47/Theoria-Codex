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

val LikedHeartColor = Color(0xFFFF6B81)

@Stable
class LikeFeedback(private val haptics: HapticFeedback, private val scope: CoroutineScope) {
    private val animatedScale = Animatable(1f)

    val scale: Float get() = animatedScale.value

    fun tapped(liked: Boolean) {
        haptics.performHapticFeedback(if (liked) TheoriaHaptics.ToggleOff else TheoriaHaptics.ToggleOn)
        if (!liked) scope.launch {
            animatedScale.snapTo(POP_START_SCALE)
            animatedScale.animateTo(1f, TheoriaMotion.pop)
        }
    }
}

fun Modifier.likePop(feedback: LikeFeedback): Modifier = graphicsLayer {
    scaleX = feedback.scale
    scaleY = feedback.scale
}

@Composable
fun rememberLikeFeedback(): LikeFeedback {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return remember(haptics, scope) { LikeFeedback(haptics, scope) }
}

private const val POP_START_SCALE = 0.6f
