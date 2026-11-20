package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.theoriacodex.app.ui.motion.TheoriaMotion

/** Viewer controls fade rather than popping over the media they share the screen with. */
@Composable
fun ViewerChromeVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TheoriaMotion.SHORT_MS)),
        exit = fadeOut(tween(TheoriaMotion.SHORT_MS)),
    ) { content() }
}
