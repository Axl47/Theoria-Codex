package com.theoriacodex.app.search

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.theoriacodex.app.ui.motion.TheoriaHaptics
import com.theoriacodex.app.ui.navigation.LiftController
import com.theoriacodex.app.ui.navigation.LiftLike
import com.theoriacodex.app.ui.navigation.LiftRelease
import com.theoriacodex.app.ui.navigation.LocalLiftController
import com.theoriacodex.domain.model.Post

/** One card's lift in progress through [controller]: where the card is, and the finger's last window position. */
private class CardLift(private val controller: LiftController) {
    var coordinates: LayoutCoordinates? = null
    var lifting = false
        private set
    private var pointer = Offset.Zero

    private fun toWindow(local: Offset): Offset? = coordinates?.takeIf { it.isAttached }?.localToWindow(local)

    /**
     * Lifts [post] from [bounds] at the long-press timeout; true when the card should open its menu instead. A
     * long press while another lift is active (a second finger) does nothing.
     */
    fun start(local: Offset, post: Post, bounds: Rect?, like: LiftLike?): Boolean {
        if (controller.lifting) return false
        val window = toWindow(local)
        lifting = window != null && bounds != null && controller.start(post, bounds, window, like = like)
        if (lifting) pointer = requireNotNull(window)
        return !lifting
    }

    fun move(local: Offset) {
        if (!lifting) return
        toWindow(local)?.let { window ->
            pointer = window
            controller.move(window)
        }
    }

    /** Releases the lift; true when the release asks for the post menu. */
    fun end(): Boolean {
        if (!lifting) return false
        lifting = false
        return controller.end(pointer) == LiftRelease.OpenMenu
    }

    fun cancel() {
        if (!lifting) return
        lifting = false
        controller.cancel()
    }
}

/**
 * A feed card's long-press, as a modifier placed after its clickable (so a lift's release never also clicks it).
 * At the long-press timeout the card's post is lifted through [LocalLiftController] from [mediaBounds] and follows
 * the finger until release; the drag is consumed, so the grid, shelf and pager do not scroll under it. A release
 * the controller reads as [LiftRelease.OpenMenu], or a controller that does not lift, calls [onLongPress] as a
 * long-click did. Accessibility services keep a long-click action that opens the menu directly. A null
 * [onLongPress] leaves the card without long-press.
 */
@Composable
internal fun rememberCardLiftModifier(
    post: Post,
    mediaBounds: () -> Rect?,
    liked: Boolean,
    onToggleLike: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
): Modifier {
    if (onLongPress == null) return Modifier
    val controller = LocalLiftController.current
    val haptics = LocalHapticFeedback.current
    val currentPost by rememberUpdatedState(post)
    val currentBounds by rememberUpdatedState(mediaBounds)
    val currentLike by rememberUpdatedState(onToggleLike?.let { LiftLike(liked, it) })
    val currentLongPress by rememberUpdatedState(onLongPress)
    val lift = remember(post.id, controller) { CardLift(controller) }
    // A feed update can dispose the card mid-lift; the overlay must not keep showing it.
    DisposableEffect(lift) { onDispose(lift::cancel) }
    return Modifier
        .onPlaced { lift.coordinates = it }
        .semantics { onLongClick { currentLongPress(); true } }
        .pointerInput(lift) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    haptics.performHapticFeedback(TheoriaHaptics.Hold)
                    if (lift.start(local, currentPost, currentBounds(), currentLike)) currentLongPress()
                },
                onDrag = { change, _ -> lift.move(change.position) },
                onDragEnd = { if (lift.end()) currentLongPress() },
                onDragCancel = lift::cancel,
            )
        }
}
