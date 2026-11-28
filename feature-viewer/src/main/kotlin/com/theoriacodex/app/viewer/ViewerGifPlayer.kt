@file:Suppress("DEPRECATION")

package com.theoriacodex.app.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.graphics.Movie
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import coil.compose.AsyncImage
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.delay
import coil.request.repeatCount
import coil.request.onAnimationEnd

@Composable
internal fun ViewerGifPlayer(
    sourceKey: SourceKey,
    locations: List<String>,
    modifier: Modifier = Modifier,
    mediaModifier: Modifier = Modifier,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
) {
    val state = remember(locations, controls.loadGeneration) { ViewerGifState(locations) }
    ViewerGifLoadEffect(state, sourceKey, locations, controls.isActive)
    val movie = state.movie
    if (movie == null) ViewerGifFallback(state, sourceKey, modifier, mediaModifier, controls, events)
    else ViewerGifMovie(movie, modifier, mediaModifier, controls, events)
}

private class ViewerGifState(locations: List<String>) {
    var movie by mutableStateOf<Movie?>(null)
    var loading by mutableStateOf(true)
    var fallbackLocations by mutableStateOf(locations)
    var fallbackIndex by mutableIntStateOf(0)
    var failed by mutableStateOf(false)
}

@Composable
private fun ViewerGifLoadEffect(state: ViewerGifState, source: SourceKey, locations: List<String>, current: Boolean) {
    val context = LocalContext.current
    val isCurrent by rememberUpdatedState(current)
    LaunchedEffect(current, source, locations) { if (current) markCurrentViewerGifs(context, source, locations) }
    LaunchedEffect(state, source) {
        var movie: Movie? = null
        for (attempt in 1..GIF_MOVIE_LOAD_ATTEMPTS) {
            movie = loadFirstGifMovie(context, source, locations, attempt, isCurrent)
            if (movie != null) break
            if (attempt < GIF_MOVIE_LOAD_ATTEMPTS) delay(GIF_MOVIE_RETRY_DELAY_MS)
        }
        state.fallbackLocations = locations.map { cachedViewerGifLocation(context, source, it) }
        state.movie = movie
        state.loading = false
    }
}

@Composable
private fun ViewerGifFallback(
    state: ViewerGifState, source: SourceKey, modifier: Modifier, mediaModifier: Modifier,
    controls: ViewerRendererControls, events: ViewerRendererEvents,
) {
    val location = state.fallbackLocations.getOrNull(state.fallbackIndex)
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            state.loading -> {
                CircularProgressIndicator()
                ViewerPendingTimeline(controls, events, Modifier.align(Alignment.BottomCenter))
            }
            !state.failed && location != null -> ViewerGifFallbackImage(state, source, location, mediaModifier, controls, events)
            else -> Text("Could not play GIF", color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun ViewerGifFallbackImage(
    state: ViewerGifState, source: SourceKey, location: String, modifier: Modifier,
    controls: ViewerRendererControls, events: ViewerRendererEvents,
) {
    val context = LocalContext.current
    var drawable by remember(location, controls.restartRequest) { mutableStateOf<android.graphics.drawable.Animatable?>(null) }
    val shown = drawable
    ViewerPlayWhileStarted(shown, controls.isActive && controls.isPlaying, { shown?.start() }, { shown?.stop() })
    AsyncImage(
        model = MediaRequestFactory.imageRequest(context, location, source, crossfade = false).newBuilder(context)
            .repeatCount(if (controls.loopPlayback) -1 else 0)
            .onAnimationEnd { if (!controls.loopPlayback) events.onPlaybackCompleted() }
            .setParameter("viewerRestart", controls.restartRequest).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize().then(modifier), contentScale = ContentScale.Fit,
        onSuccess = { drawable = it.result.drawable as? android.graphics.drawable.Animatable; events.onReady() },
        onError = {
            Log.w(GIF_LOG_TAG, "Coil GIF fallback failed for source=$source candidate=${state.fallbackIndex + 1}/${state.fallbackLocations.size} at ${gifLocationLabel(location)}", it.result.throwable)
            if (state.fallbackIndex < state.fallbackLocations.lastIndex) state.fallbackIndex += 1
            else { state.failed = true; events.onError("Could not play GIF") }
        },
    )
}

@Composable
private fun ViewerGifMovie(
    movie: Movie, modifier: Modifier, mediaModifier: Modifier,
    controls: ViewerRendererControls, events: ViewerRendererEvents,
) {
    val duration = remember(movie) { movie.duration().takeIf { it > 0 }?.toLong() ?: GIF_FALLBACK_DURATION_MS }
    val frames = rememberAnimatedFrames(movie, durationMs = duration, controls = controls, events = events)
    AnimatedFramesPlayer(
        frames, controls, events, viewerAspectRatio(movie.width(), movie.height()), modifier,
        preview = { target -> ViewerGifCanvas(movie, { target.coerceAtMost(duration - 1L) }, Modifier.fillMaxSize()) },
    ) {
        ViewerGifCanvas(movie, { frames.positionMs }, Modifier.fillMaxSize().then(mediaModifier))
    }
}

@Composable
private fun ViewerGifCanvas(movie: Movie, positionMs: () -> Long, modifier: Modifier) {
    val renderer = remember(movie) { ViewerGifFrameRenderer.of(movie) }
    val latestPosition by rememberUpdatedState(positionMs)
    DisposableEffect(renderer) { onDispose(renderer::close) }
    LaunchedEffect(renderer) { snapshotFlow { latestPosition() }.collect { renderer.request(it.toInt()) } }
    Canvas(modifier) {
        val frame = renderer.frame ?: return@Canvas
        val scale = minOf(size.width / frame.width, size.height / frame.height)
        val drawn = IntSize((frame.width * scale).roundToInt(), (frame.height * scale).roundToInt())
        drawImage(
            frame,
            dstOffset = IntOffset((size.width.roundToInt() - drawn.width) / 2, (size.height.roundToInt() - drawn.height) / 2),
            dstSize = drawn,
        )
    }
}
