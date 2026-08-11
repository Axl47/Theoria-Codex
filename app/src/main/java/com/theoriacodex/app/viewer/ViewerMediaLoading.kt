package com.theoriacodex.app.viewer

import android.content.Context
import android.graphics.Movie
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.request.ImageRequest
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.model.SourceKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ViewerPlaybackFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Suppress("DEPRECATION")
internal suspend fun loadFirstGifMovie(
    context: Context,
    sourceKey: SourceKey,
    locations: List<String>,
    attempt: Int,
): Movie? {
    locations.forEachIndexed { index, location ->
        val result = runCatchingPreservingCancellation {
            loadGifMovie(context, location, sourceKey.requestHeaders())
        }
        val outcome = result.getOrNull()
        if (outcome?.movie != null) return outcome.movie
        val reason = outcome?.failure ?: result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
        Log.w(
            GIF_LOG_TAG,
            "GIF movie load failed for source=$sourceKey attempt=$attempt " +
                "candidate=${index + 1}/${locations.size} at ${gifLocationLabel(location)}: $reason",
            result.exceptionOrNull(),
        )
    }
    return null
}

@Suppress("DEPRECATION")
private suspend fun loadGifMovie(
    context: Context,
    location: String,
    headers: Map<String, String>,
): GifMovieLoadResult = withContext(Dispatchers.IO) {
    val bytesResult = loadGifBytes(context, location, headers)
    val bytes = bytesResult.bytes
        ?: return@withContext GifMovieLoadResult(failure = bytesResult.failure ?: "media unavailable")
    val decoded = Movie.decodeByteArray(bytes, 0, bytes.size)
    GifMovieLoadResult(
        movie = decoded,
        failure = if (decoded == null) "Movie decoder rejected ${bytes.size} bytes" else null,
    )
}

private fun loadGifBytes(
    context: Context,
    location: String,
    headers: Map<String, String>,
): GifBytesLoadResult = when {
    location.startsWith("http://", ignoreCase = true) ||
        location.startsWith("https://", ignoreCase = true) -> loadRemoteGifBytes(location, headers)
    location.startsWith("content://", ignoreCase = true) -> {
        val bytes = context.contentResolver.openInputStream(location.toUri())?.use(InputStream::readBoundedGifBytes)
        GifBytesLoadResult(bytes = bytes, failure = if (bytes == null) "content unavailable" else null)
    }
    else -> loadLocalGifBytes(location)
}

private fun loadRemoteGifBytes(location: String, headers: Map<String, String>): GifBytesLoadResult {
    val connection = URL(location).openConnection() as? HttpURLConnection
        ?: return GifBytesLoadResult(failure = "unsupported connection")
    return try {
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val statusCode = connection.responseCode
        if (statusCode in 200..299) {
            GifBytesLoadResult(bytes = connection.inputStream.use(InputStream::readBoundedGifBytes))
        } else {
            GifBytesLoadResult(failure = "HTTP $statusCode")
        }
    } finally {
        connection.disconnect()
    }
}

private fun loadLocalGifBytes(location: String): GifBytesLoadResult {
    val file = File(location)
    return if (file.exists()) {
        GifBytesLoadResult(bytes = file.inputStream().use(InputStream::readBoundedGifBytes))
    } else {
        GifBytesLoadResult(failure = "local file unavailable")
    }
}

private fun InputStream.readBoundedGifBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(GIF_READ_CHUNK_BYTES)
    var totalBytes = 0L
    while (true) {
        val read = read(chunk)
        if (read == -1) return output.toByteArray()
        totalBytes += read
        if (totalBytes > MAX_GIF_BYTES) {
            throw IOException("GIF exceeded the $MAX_GIF_BYTES-byte playback limit")
        }
        output.write(chunk, 0, read)
    }
}

internal fun gifLocationLabel(location: String): String {
    val uri = location.toUri()
    return if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "${uri.scheme}://${uri.host ?: "unknown-host"}"
    } else {
        uri.scheme ?: "local-file"
    }
}

@Suppress("DEPRECATION")
private data class GifMovieLoadResult(val movie: Movie? = null, val failure: String? = null)

private data class GifBytesLoadResult(val bytes: ByteArray? = null, val failure: String? = null)

internal fun buildViewerImageRequest(
    context: Context,
    url: String,
    sourceKey: SourceKey,
    staticAnimatedWebPFrame: Boolean = false,
): ImageRequest = MediaRequestFactory.imageRequest(
    context = context,
    url = url,
    sourceKey = sourceKey,
    crossfade = true,
    staticAnimatedWebPFrame = staticAnimatedWebPFrame,
)

internal const val GIF_FALLBACK_DURATION_MS = 1000L
internal const val GIF_MOVIE_LOAD_ATTEMPTS = 2
internal const val GIF_MOVIE_RETRY_DELAY_MS = 750L
internal const val GIF_LOG_TAG = "TheoriaGifPlayer"
private const val GIF_READ_CHUNK_BYTES = 8 * 1024
private const val MAX_GIF_BYTES = 64L * 1024L * 1024L
