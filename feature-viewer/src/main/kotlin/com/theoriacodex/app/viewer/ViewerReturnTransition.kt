package com.theoriacodex.app.viewer

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.theoriacodex.app.ui.motion.TheoriaMotion
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

internal const val VIEWER_RETURN_DURATION_MS = TheoriaMotion.SCREEN_MS
internal const val VIEWER_RETURN_HOLD_MS = 700
private const val VIEWER_RETURN_TARGET_WAIT_MS = 300L

@Composable
fun ViewerReturnLifetime(scope: AnimatedVisibilityScope, holdsExit: () -> Boolean) {
    scope.transition.animateFloat(
        transitionSpec = {
            tween(
                when {
                    targetState != EnterExitState.PostExit -> VIEWER_RETURN_DURATION_MS
                    holdsExit() -> VIEWER_RETURN_HOLD_MS
                    else -> 0
                },
            )
        }, label = "Viewer media transition lifetime",
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }
}

@Composable
fun ViewerCoveredLifecycle(covered: Boolean, content: @Composable () -> Unit) {
    val host = LocalLifecycleOwner.current
    val owner = remember(host) { CoveredLifecycleOwner(host) }
    DisposableEffect(owner) {
        host.lifecycle.addObserver(owner.follow)
        onDispose { host.lifecycle.removeObserver(owner.follow) }
    }
    SideEffect { owner.covered = covered }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

private class CoveredLifecycleOwner(private val host: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    val follow = LifecycleEventObserver { _, _ -> sync() }

    var covered = false
        set(value) {
            field = value
            sync()
        }

    init {
        sync()
    }

    private fun sync() {
        val hostState = host.lifecycle.currentState
        if (hostState == Lifecycle.State.INITIALIZED || registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = if (covered && hostState != Lifecycle.State.DESTROYED) minOf(hostState, Lifecycle.State.CREATED) else hostState
    }
}

private const val DISMISS_DRAG_RANGE_FRACTION = 0.5f
private const val DISMISS_COMMIT_PROGRESS = 0.25f
private const val DISMISS_MIN_SCALE = 0.7f
private const val DISMISS_BACK_RANGE_FRACTION = 0.5f

internal fun viewerDismissCommits(progress: Float, velocityY: Float, flingVelocity: Float): Boolean = when {
    velocityY <= -flingVelocity -> false
    velocityY >= flingVelocity -> true
    else -> progress >= DISMISS_COMMIT_PROGRESS
}

internal class ViewerDismissDrag {
    var progress by mutableFloatStateOf(0f)
        private set
    var translationX by mutableFloatStateOf(0f)
        private set
    var translationY by mutableFloatStateOf(0f)
        private set
    val active by derivedStateOf { progress > 0f }
    val scale: Float get() = 1f - (1f - DISMISS_MIN_SCALE) * progress
    val pastCommit: Boolean get() = progress >= DISMISS_COMMIT_PROGRESS

    fun followPull(x: Float, y: Float, height: Float) {
        val pull = y.coerceAtLeast(0f)
        progress = (pull / (height * DISMISS_DRAG_RANGE_FRACTION).coerceAtLeast(1f)).coerceIn(0f, 1f)
        translationX = x
        translationY = pull
    }

    fun followBack(backProgress: Float, fromLeftEdge: Boolean, backDistance: Float) {
        progress = (backProgress * DISMISS_BACK_RANGE_FRACTION).coerceIn(0f, 1f)
        translationX = backProgress * backDistance * if (fromLeftEdge) 1f else -1f
        translationY = 0f
    }

    suspend fun recoil() {
        val startProgress = progress
        val startX = translationX
        val startY = translationY
        animate(1f, 0f, animationSpec = TheoriaMotion.settleFraction) { remaining, _ ->
            progress = startProgress * remaining
            translationX = startX * remaining
            translationY = startY * remaining
        }
    }
}

internal fun Modifier.viewerDismissDrag(drag: ViewerDismissDrag?, returnProgress: () -> Float): Modifier {
    if (drag == null) return this
    return graphicsLayer {
        val remaining = 1f - returnProgress().coerceIn(0f, 1f)
        val poseScale = 1f + (drag.scale - 1f) * remaining
        scaleX = poseScale
        scaleY = poseScale
        translationX = drag.translationX * remaining
        translationY = drag.translationY * remaining
    }
}

@Composable
internal fun rememberViewerReturnProgress(): State<Float> {
    val transition = LocalViewerReturnTransition.current
    val request = transition?.request
    val progress = remember { Animatable(if (transition?.openingBounds != null) 1f else 0f) }
    LaunchedEffect(request) {
        if (request == null) {
            if (transition?.openingBounds != null) {
                progress.animateTo(0f, tween(VIEWER_RETURN_DURATION_MS))
                transition.finishOpening()
            }
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        withTimeoutOrNull(VIEWER_RETURN_TARGET_WAIT_MS) {
            snapshotFlow { transition.targetBounds }.filterNotNull().first()
        }
        progress.animateTo(1f, tween(if (transition.targetBounds == null) TheoriaMotion.MEDIUM_MS else VIEWER_RETURN_DURATION_MS))
        transition.finishLanding()
    }
    return progress.asState()
}

private class ViewerLandingTarget {
    private var chosen = false
    private var target: Rect? = null

    fun resolve(moving: Boolean, current: Rect?): Rect? {
        if (!moving) return current
        if (!chosen) {
            chosen = true
            target = current
        }
        return target
    }
}

@Composable
internal fun Modifier.viewerReturnMedia(progress: () -> Float, aspectRatio: Float?): Modifier {
    val transition = LocalViewerReturnTransition.current ?: return this
    if (transition.request == null && transition.openingBounds == null) return this
    var windowBounds by remember { mutableStateOf(Rect.Zero) }
    val closing = transition.request != null
    val landing = remember(transition.request) { ViewerLandingTarget() }
    val frame = remember { ViewerReturnFrame() }
    fun geometry(current: Float) = frame.geometry(
        windowBounds,
        landing.resolve(current > 0f, if (closing) transition.targetBounds else transition.openingBounds),
        aspectRatio,
        current,
    )
    return onGloballyPositioned {
        if (windowBounds.isEmpty || progress() == 0f) windowBounds = it.boundsInWindow(clipBounds = false)
    }
        .drawWithContent {
            val current = progress()
            val corners = if (closing) transition.targetCorners else transition.openingCorners
            drawViewerReturnFrame(current, closing, geometry(current).crop, corners, frame.clip)
        }
        .graphicsLayer {
            val moved = geometry(progress())
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            scaleX = moved.scale
            scaleY = moved.scale
            translationX = moved.translationX
            translationY = moved.translationY
        }
}

private class ViewerReturnFrame {
    val clip = Path()
    private var progress = Float.NaN
    private var source = Rect.Zero
    private var target: Rect? = null
    private var aspectRatio: Float? = null
    private var cached: ViewerReturnGeometry? = null

    fun geometry(source: Rect, target: Rect?, aspectRatio: Float?, progress: Float): ViewerReturnGeometry {
        val hit = cached
        if (hit != null && progress == this.progress && source == this.source && target == this.target &&
            aspectRatio == this.aspectRatio
        ) return hit
        return viewerReturnGeometry(source, target, aspectRatio, progress).also {
            cached = it
            this.progress = progress
            this.source = source
            this.target = target
            this.aspectRatio = aspectRatio
        }
    }
}

private fun ContentDrawScope.drawViewerReturnFrame(
    progress: Float, closing: Boolean, crop: Rect, corners: ViewerReturnCorners, clip: Path,
) {
    if (closing && progress >= 1f) return
    if (progress <= 0f) {
        drawContent()
        return
    }
    clip.rewind()
    clip.addRoundRect(corners.roundRect(crop, progress))
    clipPath(clip) { this@drawViewerReturnFrame.drawContent() }
}

internal data class ViewerReturnGeometry(val crop: Rect, val scale: Float, val translationX: Float, val translationY: Float)

internal fun viewerReturnGeometry(source: Rect, target: Rect?, aspectRatio: Float?, progress: Float): ViewerReturnGeometry {
    val local = Rect(0f, 0f, source.width, source.height)
    if (source.isEmpty || progress <= 0f) return ViewerReturnGeometry(local, 1f, 0f, 0f)
    if (target == null) return ViewerReturnGeometry(local, 1f, 0f, source.height * progress.coerceIn(0f, 1f))
    val destination = target.translate(-source.topLeft)
    val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: (source.width / source.height)
    val fittedWidth = minOf(source.width, source.height * ratio)
    val fittedHeight = fittedWidth / ratio
    val scale = max(destination.width / fittedWidth, destination.height / fittedHeight)
    val fraction = progress.coerceIn(0f, 1f)
    return ViewerReturnGeometry(
        crop = lerp(local, destination, fraction),
        scale = 1f + (scale - 1f) * fraction,
        translationX = (destination.center.x - local.center.x) * fraction,
        translationY = (destination.center.y - local.center.y) * fraction,
    )
}
