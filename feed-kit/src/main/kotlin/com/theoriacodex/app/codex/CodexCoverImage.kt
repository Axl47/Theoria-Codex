package com.theoriacodex.app.codex

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory

@Composable
fun CodexCoverImage(
    candidates: List<CodexCoverCandidate>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit,
) {
    var candidateIndex by remember(candidates) { mutableIntStateOf(0) }
    val candidate = candidates.getOrNull(candidateIndex)
    if (candidate == null) {
        fallback()
        return
    }

    val context = LocalContext.current
    val model = remember(context, candidate) { candidate.toImageModel(context) }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onError = {
            if (candidateIndex <= candidates.lastIndex) {
                candidateIndex += 1
            }
        },
    )
}

fun CodexCoverCandidate.toImageModel(context: Context): ImageRequest = when (this) {
    is CodexCoverCandidate.LocalFile -> ImageRequest.Builder(context).data(file)
        .memoryCacheKey("codex-cover:${file.absolutePath}:${file.lastModified()}:${file.length()}:$cacheRevision").build()
    is CodexCoverCandidate.RemoteImage -> MediaRequestFactory.imageRequest(
        context = context,
        url = url,
        sourceKey = source,
        crossfade = false,
        staticAnimatedWebPFrame = true,
    )
}
