@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.SourceKey
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A floating thumbnail is presentation only; the track below owns the drag gesture. */
@Composable
internal fun ViewerScrubPreview(positionMs: Long, progress: Float, content: @Composable () -> Unit) {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(progress, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                (anchorBounds.left + anchorBounds.width * progress - popupContentSize.width / 2f).roundToInt()
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0),
            )
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        val shape = RoundedCornerShape(6.dp)
        Column(Modifier.size(144.dp, 108.dp).clip(shape).background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.6f), shape).testTag("viewer-scrub-preview"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
            Text(formatTimelineTime(positionMs), color = Color.White, modifier = Modifier.height(24.dp))
        }
    }
}

/** One extractor per drag, one pending frame, and only the newest target may publish. */
@Composable
internal fun ViewerVideoScrubPreview(location: String, source: SourceKey, positionMs: Long) {
    val context = LocalContext.current
    val target by rememberUpdatedState(positionMs)
    var frame by remember(location, source) { mutableStateOf<Pair<Long, Bitmap>?>(null) }
    var failed by remember(location, source) { mutableStateOf(false) }
    LaunchedEffect(location, source) {
        try {
            extractPreviewFrames(context, location, source, { target }) { requested, bitmap ->
                frame = requested to bitmap
            }
        } catch (_: TimeoutCancellationException) {
            failed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val current = frame?.takeIf { it.first == positionMs }?.second
    when {
        current != null -> Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        failed -> Text("Preview unavailable", color = Color.White)
        else -> CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
    }
}

private suspend fun extractPreviewFrames(
    context: Context, location: String, source: SourceKey,
    target: () -> Long, publish: (Long, Bitmap) -> Unit,
) {
    val request = context.videoPlaybackInfrastructure().bind(location, source.requestHeaders())
    FrameExtractor.Builder(context, request.mediaItem)
        .setMediaSourceFactory(request.mediaSourceFactory)
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .setEffects(listOf(Presentation.createForWidthAndHeight(144, 84, Presentation.LAYOUT_SCALE_TO_FIT)))
        .build().use { extractor ->
            snapshotFlow { target() }.conflate().collect { requested ->
                delay(80L)
                if (requested != target()) return@collect
                val bitmap = withTimeout(6_000L) { extractor.getFrame(requested).awaitPreviewFrame().bitmap }
                if (requested == target()) publish(requested, bitmap)
            }
        }
}

private suspend fun <T> ListenableFuture<T>.awaitPreviewFrame(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try {
            continuation.resume(get())
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }, Executor { it.run() })
}
