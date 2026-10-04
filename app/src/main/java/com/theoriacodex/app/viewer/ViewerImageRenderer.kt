package com.theoriacodex.app.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.media.isAnimatedImageMediaRef
import com.theoriacodex.app.media.isHttpNotFound
import com.theoriacodex.app.media.MediaDeliveryCandidate
import com.theoriacodex.app.media.MediaDeliveryActivation
import com.theoriacodex.app.media.viewerMediaDeliveryPlan
import com.theoriacodex.app.source.displayName
import com.theoriacodex.app.viewer.state.ViewerMediaKey
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.SourceKey

/** Process-local hints scoped to one open Viewer, shared by its image pages. */
internal class ViewerImageSession {
    val loadedLocations = mutableSetOf<String>()
    val recoveryRequests = mutableSetOf<String>()
}

/** Owns image candidates, quality upgrades, and the shared static/WebP recovery path. */
@Composable
internal fun ViewerImageRenderer(
    post: Post,
    media: ImageRef,
    key: ViewerMediaKey,
    modifier: Modifier,
    mediaModifier: Modifier,
    controls: ViewerRendererControls,
    events: ViewerRendererEvents,
    imageSession: ViewerImageSession,
    onRequestMediaRecovery: ((Post, ImageRef) -> Unit)?,
) {
    val context = LocalContext.current
    val generation = controls.loadGeneration
    val candidates = remember(post, media, generation) { viewerMediaDeliveryPlan(post, media).candidates }
    val state = remember(key, generation) { ViewerImageState() }
    val candidate = candidates.getOrNull(state.index)
    val location = candidate?.location
    val model = remember(context, location, post.id.source, generation) {
        location?.let { buildViewerImageRequest(context, it, post.id.source) }
    }
    LaunchedEffect(candidates, generation) { state.index = 0 }
    ViewerImageUpgrade(candidates, state, post.id.source, imageSession)
    val onReady = { state.ready(location, imageSession, events) }
    val onError = { error: Throwable -> state.failed(error, post, key, candidate, candidates.size, imageSession, onRequestMediaRecovery, events) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!state.visible) ViewerLoadingPreview(post, media, mediaModifier)
        when {
            post.id.source == SourceKey.HITOMI && isAnimatedImageMediaRef(media) && location != null ->
                ViewerAnimatedWebPPlayer(
                    sourceKey = post.id.source, location = location,
                    contentDescription = post.title ?: post.id.sourcePostId,
                    modifier = Modifier.fillMaxSize(), mediaModifier = mediaModifier,
                    controls = controls, events = events,
                    onLoading = { state.loading = true }, onSuccess = onReady, onError = onError,
                )
            model != null -> {
                AsyncImage(
                    model = model, contentDescription = post.title ?: post.id.sourcePostId,
                    modifier = Modifier.fillMaxSize().then(mediaModifier), contentScale = ContentScale.Fit,
                    onLoading = { state.loading = true }, onSuccess = { onReady() }, onError = { onError(it.result.throwable) },
                )
                if (state.loading && !state.visible) CircularProgressIndicator()
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(post.id.source.displayName(), style = MaterialTheme.typography.titleLarge)
                Text(post.id.sourcePostId, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                post.canonicalTags.firstOrNull()?.let { AssistChip(onClick = {}, label = { Text("#$it") }) }
            }
        }
    }
}

private class ViewerImageState {
    var index by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var visible by mutableStateOf(false)

    fun ready(location: String?, session: ViewerImageSession, events: ViewerRendererEvents) {
        loading = false
        visible = true
        location?.let { session.loadedLocations += it }
        events.onReady()
    }

    fun failed(
        error: Throwable, post: Post, key: ViewerMediaKey, candidate: MediaDeliveryCandidate?, count: Int,
        session: ViewerImageSession, onRecovery: ((Post, ImageRef) -> Unit)?, events: ViewerRendererEvents,
    ) {
        val recoveryKey = "${post.id.source.name}:${post.id.sourcePostId}:${key.mediaIndex}:${candidate?.location}"
        if (candidate != null && onRecovery != null && isHttpNotFound(error) && session.recoveryRequests.add(recoveryKey)) {
            onRecovery(post, candidate.ref.copy(url = candidate.location))
        }
        loading = false
        if (!visible && index < count - 1) index += 1
        else if (!visible) events.onError(error.message ?: "Could not load image")
    }
}

@Composable
private fun ViewerImageUpgrade(candidates: List<MediaDeliveryCandidate>, state: ViewerImageState, source: SourceKey, session: ViewerImageSession) {
    val context = LocalContext.current
    LaunchedEffect(candidates, state.index, state.visible, source) {
        if (!state.visible) return@LaunchedEffect
        val next = candidates.getOrNull(state.index + 1) ?: return@LaunchedEffect
        if (next.activation != MediaDeliveryActivation.QUALITY_UPGRADE) return@LaunchedEffect
        val prepared = next.location in session.loadedLocations || runCatchingPreservingCancellation {
            context.imageLoader.execute(buildViewerImageRequest(context, next.location, source)) is SuccessResult
        }.getOrDefault(false)
        if (prepared) { session.loadedLocations += next.location; state.index += 1 }
    }
}
