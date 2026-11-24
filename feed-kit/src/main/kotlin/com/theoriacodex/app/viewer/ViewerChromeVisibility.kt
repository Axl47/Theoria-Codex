package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.ui.motion.TheoriaMotion

@Composable
fun ViewerChromeVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = ExitTransition.None,
    ) { content() }
}
