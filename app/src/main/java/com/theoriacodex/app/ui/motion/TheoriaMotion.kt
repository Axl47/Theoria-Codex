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

/** The app's motion vocabulary: pick by what the motion answers, so screens stay consistent. */
internal object TheoriaMotion {
    /** Small state feedback: icons, labels, overlays. */
    const val SHORT_MS = 120

    /** Parts of a screen appearing, leaving, or changing. */
    const val MEDIUM_MS = 220

    /** A whole screen or media surface moving. */
    const val SCREEN_MS = 320

    /** Finishes a finger's motion in pixels, keeping its velocity without overshooting the target. */
    val settle: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 550f, visibilityThreshold = 1f)

    /** The same landing for values between zero and one. */
    val settleFraction: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 550f, visibilityThreshold = 0.001f)

    /** Returns something to rest after a cancelled or resisted drag, in pixels. */
    val recoil: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 500f, visibilityThreshold = 1f)

    /** A small celebratory overshoot for confirming an action, in scale units. */
    val pop: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 600f, visibilityThreshold = 0.001f)
}

/** Moving one level deeper or back within a screen: a short slide in the travel direction plus a fade. */
internal fun depthTransform(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(TheoriaMotion.MEDIUM_MS)
    val fade = tween<Float>(TheoriaMotion.MEDIUM_MS)
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(slide) { direction * it / 8 } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { -direction * it / 8 } + fadeOut(fade))
}

/** What each vibration means, so a gesture picks a meaning rather than a platform constant. */
internal object TheoriaHaptics {
    /** A dragged item crossed one position. */
    val Step = HapticFeedbackType.SegmentFrequentTick

    /** Releasing now would complete the gesture. */
    val Threshold = HapticFeedbackType.GestureThresholdActivate

    /** A gesture reached an end and can go no further. */
    val Limit = HapticFeedbackType.SegmentTick

    /** A hold took effect. */
    val Hold = HapticFeedbackType.LongPress

    val ToggleOn = HapticFeedbackType.ToggleOn
    val ToggleOff = HapticFeedbackType.ToggleOff
}
