package com.theoriacodex.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class PixivUgoiraClient(
    private val credentialsProvider: SourceCredentialsProvider,
    private val httpClient: SourceHttpClient,
    private val authApi: PixivAuthApi = PixivAuthApi(httpClient),
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun load(postId: String): Result<UgoiraPlayback> {
        return runCatching { loadOrThrow(postId) }
    }

    private suspend fun loadOrThrow(postId: String): UgoiraPlayback = withContext(Dispatchers.IO) {
        val currentTokens = activeTokens()
        val metadataResponse = fetchMetadata(postId, currentTokens.accessToken)
        val metadata = when {
            metadataResponse.statusCode in 200..299 -> parseMetadata(metadataResponse.body)
            metadataResponse.statusCode == 401 || metadataResponse.statusCode == 403 -> {
                val refreshed = refreshTokens(currentTokens.refreshToken)
                val retry = fetchMetadata(postId, refreshed.accessToken)
                if (retry.statusCode !in 200..299) {
                    throw IOException("Pixiv ugoira metadata request failed (${retry.statusCode})")
                }
                parseMetadata(retry.body)
            }
            else -> throw IOException("Pixiv ugoira metadata request failed (${metadataResponse.statusCode})")
        }

        val zipResponse = downloadZip(
            url = metadata.zipUrl,
            accessToken = currentTokens.accessToken,
        )
        val zipBytes = when {
            zipResponse.statusCode in 200..299 -> zipResponse.body
            zipResponse.statusCode == 401 || zipResponse.statusCode == 403 -> {
                val refreshed = refreshTokens(currentTokens.refreshToken)
                val retry = downloadZip(
                    url = metadata.zipUrl,
                    accessToken = refreshed.accessToken,
                )
                if (retry.statusCode !in 200..299) {
                    throw IOException("Pixiv ugoira zip request failed (${retry.statusCode})")
                }
                retry.body
            }
            else -> throw IOException("Pixiv ugoira zip request failed (${zipResponse.statusCode})")
        }

        UgoiraPlayback(
            frames = decodeFrames(
                zipBytes = zipBytes,
                specs = metadata.frames,
            )
        )
    }

    private suspend fun activeTokens(): PixivAuthTokens {
        val current = credentialsProvider.getPixivTokens()
            ?: throw IllegalStateException("Pixiv credentials are not configured")
        return if (clock() + 60_000L < current.expiresAtEpochMs) {
            current
        } else {
            refreshTokens(current.refreshToken)
        }
    }

    private suspend fun refreshTokens(refreshToken: String): PixivAuthTokens {
        val refreshed = authApi.refresh(refreshToken)
        credentialsProvider.savePixivTokens(refreshed)
        return refreshed
    }

    private fun fetchMetadata(postId: String, accessToken: String): TextResponse {
        val url = "${PIXIV_API_BASE}/v1/ugoira/metadata?illust_id=$postId"
        val connection = openConnection(url, accessToken)
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return TextResponse(statusCode = status, body = body)
    }

    private fun downloadZip(url: String, accessToken: String): BinaryResponse {
        val connection = openConnection(url, accessToken)
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        return BinaryResponse(statusCode = status, body = body)
    }

    private fun openConnection(url: String, accessToken: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Referer", "https://www.pixiv.net/")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
    }

    private fun parseMetadata(body: String): ParsedMetadata {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
            ?: throw IOException("Pixiv ugoira metadata was not valid JSON")
        val metadata = root.getAsJsonObject("ugoira_metadata")
            ?: throw IOException("Pixiv ugoira metadata payload missing")
        val zipUrls = metadata.getAsJsonObject("zip_urls")
            ?: throw IOException("Pixiv ugoira zip_urls missing")
        val zipUrl = zipUrls.get("medium")?.asString
            ?: zipUrls.entrySet().firstOrNull()?.value?.asString
            ?: throw IOException("Pixiv ugoira zip URL missing")
        val frameSpecs = metadata.getAsJsonArray("frames")
            ?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val file = obj.get("file")?.asString?.trim().orEmpty()
                if (file.isBlank()) return@mapNotNull null
                val delayMs = obj.get("delay")?.asInt ?: 50
                UgoiraFrameSpec(
                    fileName = file,
                    delayMs = delayMs.coerceAtLeast(16),
                )
            }
            .orEmpty()
        if (frameSpecs.isEmpty()) {
            throw IOException("Pixiv ugoira frames are empty")
        }
        return ParsedMetadata(zipUrl = zipUrl, frames = frameSpecs)
    }

    private fun decodeFrames(
        zipBytes: ByteArray,
        specs: List<UgoiraFrameSpec>,
    ): List<UgoiraFrame> {
        val bytesByName = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val key = entry.name.substringAfterLast('/')
                    bytesByName[key] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        val frames = specs.mapNotNull { spec ->
            val frameBytes = bytesByName[spec.fileName] ?: return@mapNotNull null
            val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                ?: return@mapNotNull null
            UgoiraFrame(bitmap = bitmap, delayMs = spec.delayMs)
        }
        if (frames.isEmpty()) {
            throw IOException("Pixiv ugoira zip could not be decoded")
        }
        return frames
    }
}

@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    modifier: Modifier = Modifier,
    contentDescription: String?,
) {
    var playback by remember(postId) { mutableStateOf<UgoiraPlayback?>(null) }
    var errorMessage by remember(postId) { mutableStateOf<String?>(null) }
    var frameIndex by remember(postId) { mutableIntStateOf(0) }

    LaunchedEffect(postId, client) {
        frameIndex = 0
        playback = null
        errorMessage = null
        val result = client.load(postId)
        result.onSuccess { loaded ->
            playback = loaded
        }.onFailure { error ->
            errorMessage = error.message ?: "Could not load animation"
        }
    }

    val activePlayback = playback
    if (activePlayback == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (errorMessage == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        return
    }

    LaunchedEffect(activePlayback, frameIndex) {
        val delayMs = activePlayback.frames[frameIndex].delayMs.toLong().coerceAtLeast(16L)
        delay(delayMs)
        frameIndex = (frameIndex + 1) % activePlayback.frames.size
    }

    val frame = activePlayback.frames[frameIndex]
    Image(
        bitmap = frame.bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

data class UgoiraPlayback(
    val frames: List<UgoiraFrame>,
)

data class UgoiraFrame(
    val bitmap: Bitmap,
    val delayMs: Int,
)

private data class ParsedMetadata(
    val zipUrl: String,
    val frames: List<UgoiraFrameSpec>,
)

private data class UgoiraFrameSpec(
    val fileName: String,
    val delayMs: Int,
)

private data class TextResponse(
    val statusCode: Int,
    val body: String,
)

private data class BinaryResponse(
    val statusCode: Int,
    val body: ByteArray,
)

private const val PIXIV_API_BASE = "https://app-api.pixiv.net"
