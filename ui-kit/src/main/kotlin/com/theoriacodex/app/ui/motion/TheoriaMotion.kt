package com.theoriacodex.app.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset

object TheoriaMotion {
    const val SHORT_MS = 120

    const val MEDIUM_MS = 220

    const val SCREEN_MS = 320

    const val PAGE_COMMIT_FRACTION = 0.16f

    val settle: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 550f, visibilityThreshold = 1f)

    val settleFraction: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 550f, visibilityThreshold = 0.001f)

    val recoil: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 500f, visibilityThreshold = 1f)

    val pop: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 600f, visibilityThreshold = 0.001f)
}

fun depthTransform(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(TheoriaMotion.MEDIUM_MS)
    val fade = tween<Float>(TheoriaMotion.MEDIUM_MS)
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(slide) { direction * it / 8 } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 8 } + fadeOut(fade))
}

object TheoriaHaptics {
    val Step = HapticFeedbackType.SegmentFrequentTick

    val Threshold = HapticFeedbackType.GestureThresholdActivate

    val Limit = HapticFeedbackType.SegmentTick

    val Hold = HapticFeedbackType.LongPress

    val ToggleOn = HapticFeedbackType.ToggleOn
    val ToggleOff = HapticFeedbackType.ToggleOff
}
