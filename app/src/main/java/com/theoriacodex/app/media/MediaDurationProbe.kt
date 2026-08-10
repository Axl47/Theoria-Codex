package com.theoriacodex.app.media

import android.media.MediaMetadataRetriever
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun probeRemoteVideoDurationMs(post: Post): Long? = withContext(Dispatchers.IO) {
    val ref = authoritativeDurationProbeRef(post) ?: return@withContext null
    val location = ref.url?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
    runCatchingPreservingCancellation {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(location, post.id.source.requestHeaders())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

internal fun authoritativeDurationProbeRef(post: Post): ImageRef? {
    val full = post.full ?: return null
    return full.takeIf(::isVideoMediaRef)
        ?: full.takeIf { ref ->
            mediaKind(ref) == PostMediaKind.UNKNOWN &&
                ref.url?.substringBefore('?')?.endsWith(".mp4", ignoreCase = true) == true
        }
}
