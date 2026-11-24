package com.theoriacodex.app.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.theoriacodex.app.ui.motion.TheoriaMotion

@Composable
fun ViewerChromeVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val firstComposition = remember { booleanArrayOf(true) }
    SideEffect { firstComposition[0] = false }
    if (!visible) return
    val alpha = remember { Animatable(if (firstComposition[0]) 1f else 0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(TheoriaMotion.SHORT_MS)) }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}
