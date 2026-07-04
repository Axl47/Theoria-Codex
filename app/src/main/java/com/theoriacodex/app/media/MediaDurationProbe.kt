package com.theoriacodex.app.media

import android.media.MediaMetadataRetriever
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun probeRemoteVideoDurationMs(post: Post): Long? = withContext(Dispatchers.IO) {
    val ref = bestDurationProbeRef(post) ?: return@withContext null
    val location = ref.url?.trim()?.takeIf(String::isNotBlank) ?: return@withContext null
    runCatching {
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

private fun bestDurationProbeRef(post: Post): ImageRef? {
    val refs = buildList {
        post.full?.let { add(it) }
        addAll(post.media)
    }
    return refs.firstOrNull(::isVideoMediaRef)
        ?: refs.firstOrNull { ref -> mediaKind(ref) == PostMediaKind.UNKNOWN && ref.url?.endsWith(".mp4", ignoreCase = true) == true }
}
