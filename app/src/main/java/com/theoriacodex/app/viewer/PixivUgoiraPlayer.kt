package com.theoriacodex.app.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import androidx.compose.runtime.mutableLongStateOf
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
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class PixivUgoiraClient(
    private val credentialsProvider: SourceCredentialsProvider,
    private val httpClient: SourceHttpClient,
    private val authApi: PixivAuthApi = PixivAuthApi(httpClient),
    private val gson: Gson = Gson(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val cacheLock = Any()
    private val playbackCache = LinkedHashMap<String, UgoiraPlayback>(
        UGOIRA_PLAYBACK_CACHE_SIZE,
        0.75f,
        true,
    )

    fun cached(postId: String): UgoiraPlayback? = synchronized(cacheLock) {
        playbackCache[postId]
    }

    suspend fun load(postId: String): Result<UgoiraPlayback> {
        cached(postId)?.let { return Result.success(it) }
        return runCatching {
            loadOrThrow(postId).also { playback ->
                cachePlayback(postId, playback)
            }
        }
    }

    suspend fun exportToMp4(
        context: Context,
        postId: String,
        title: String?,
    ): Result<Uri> {
        return runCatching { exportToMp4OrThrow(context, postId, title) }
    }

    private suspend fun loadOrThrow(postId: String): UgoiraPlayback = withContext(Dispatchers.IO) {
        val currentTokens = activeTokens()
        val (metadata, tokensAfterMetadata) = fetchMetadataWithRetry(
            postId = postId,
            initialTokens = currentTokens,
        )
        val zipBytes = downloadZipWithRetry(
            url = metadata.zipUrl,
            initialTokens = tokensAfterMetadata,
        )

        val playback = UgoiraPlayback(
            frames = decodeFrames(
                zipBytes = zipBytes,
                specs = metadata.frames,
            )
        )
        playback
    }

    private suspend fun fetchMetadataWithRetry(
        postId: String,
        initialTokens: PixivAuthTokens,
    ): Pair<ParsedMetadata, PixivAuthTokens> {
        var tokens = initialTokens
        var didRefreshAfterAuthFailure = false

        repeat(UGOIRA_NETWORK_MAX_ATTEMPTS) { attempt ->
            val metadataResponse = fetchMetadata(postId, tokens.accessToken)
            when {
                metadataResponse.statusCode in 200..299 -> {
                    return parseMetadata(metadataResponse.body) to tokens
                }

                metadataResponse.statusCode == 401 || metadataResponse.statusCode == 403 -> {
                    if (didRefreshAfterAuthFailure) {
                        throw IOException("Pixiv ugoira metadata request failed (${metadataResponse.statusCode})")
                    }
                    tokens = refreshTokens(tokens.refreshToken)
                    didRefreshAfterAuthFailure = true
                }

                isRetryableUgoiraStatus(metadataResponse.statusCode) && attempt < UGOIRA_NETWORK_MAX_ATTEMPTS - 1 -> {
                    delay(ugoiraRetryDelayMs(attempt))
                }

                else -> {
                    throw IOException("Pixiv ugoira metadata request failed (${metadataResponse.statusCode})")
                }
            }
        }

        throw IOException("Pixiv ugoira metadata request failed (retry limit reached)")
    }

    private suspend fun downloadZipWithRetry(
        url: String,
        initialTokens: PixivAuthTokens,
    ): ByteArray {
        var tokens = initialTokens
        var didRefreshAfterAuthFailure = false

        repeat(UGOIRA_NETWORK_MAX_ATTEMPTS) { attempt ->
            val zipResponse = downloadZip(url = url, accessToken = tokens.accessToken)
            when {
                zipResponse.statusCode in 200..299 -> return zipResponse.body

                zipResponse.statusCode == 401 || zipResponse.statusCode == 403 -> {
                    if (didRefreshAfterAuthFailure) {
                        throw IOException("Pixiv ugoira zip request failed (${zipResponse.statusCode})")
                    }
                    tokens = refreshTokens(tokens.refreshToken)
                    didRefreshAfterAuthFailure = true
                }

                isRetryableUgoiraStatus(zipResponse.statusCode) && attempt < UGOIRA_NETWORK_MAX_ATTEMPTS - 1 -> {
                    delay(ugoiraRetryDelayMs(attempt))
                }

                else -> throw IOException("Pixiv ugoira zip request failed (${zipResponse.statusCode})")
            }
        }

        throw IOException("Pixiv ugoira zip request failed (retry limit reached)")
    }

    private suspend fun exportToMp4OrThrow(
        context: Context,
        postId: String,
        title: String?,
    ): Uri = withContext(Dispatchers.IO) {
        val playback = cached(postId)
            ?: loadOrThrow(postId).also { loaded ->
                cachePlayback(postId, loaded)
            }
        val fileName = buildMp4FileName(postId = postId, title = title)

        val mediaStoreResult = runCatching {
            saveToMediaStore(context = context, playback = playback, fileName = fileName)
        }.getOrNull()
        if (mediaStoreResult != null) {
            return@withContext mediaStoreResult
        }

        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "TheoriaCodex",
        )
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val destination = File(directory, fileName)

        ParcelFileDescriptor.open(
            destination,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY,
        ).use { parcelDescriptor ->
            val fileDescriptor = parcelDescriptor?.fileDescriptor
                ?: throw IOException("Could not open video destination")
            encodePlaybackToMp4(playback = playback, outputFileDescriptor = fileDescriptor)
        }

        Uri.fromFile(destination)
    }

    private fun saveToMediaStore(
        context: Context,
        playback: UgoiraPlayback,
        fileName: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TheoriaCodex")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val destinationUri = resolver.insert(collection, values)
            ?: throw IOException("Could not create MediaStore entry for ugoira export")

        try {
            resolver.openFileDescriptor(destinationUri, "w")?.use { parcelDescriptor ->
                encodePlaybackToMp4(
                    playback = playback,
                    outputFileDescriptor = parcelDescriptor.fileDescriptor,
                )
            } ?: throw IOException("Could not open MediaStore file descriptor")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(destinationUri, publish, null, null)
            }
            return destinationUri
        } catch (error: Throwable) {
            resolver.delete(destinationUri, null, null)
            throw error
        }
    }

    private fun encodePlaybackToMp4(
        playback: UgoiraPlayback,
        outputFileDescriptor: FileDescriptor,
    ) {
        val firstBitmap = playback.frames.firstOrNull()?.bitmap
            ?: throw IOException("Pixiv ugoira has no frames")

        val width = normalizedVideoDimension(firstBitmap.width)
        val height = normalizedVideoDimension(firstBitmap.height)
        val frameDurationUs = 1_000_000L / UGOIRA_EXPORT_FPS
        val bitRate = (width * height * UGOIRA_BITRATE_PER_PIXEL)
            .coerceIn(UGOIRA_MIN_BITRATE, UGOIRA_MAX_BITRATE)

        val mediaFormat = MediaFormat.createVideoFormat(UGOIRA_MP4_MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, UGOIRA_EXPORT_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val codec = MediaCodec.createEncoderByType(UGOIRA_MP4_MIME_TYPE)
        val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var trackIndex = -1

        try {
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            var presentationTimeUs = 0L
            playback.frames.forEach { frame ->
                val sizedBitmap = if (frame.bitmap.width != width || frame.bitmap.height != height) {
                    Bitmap.createScaledBitmap(frame.bitmap, width, height, true)
                } else {
                    frame.bitmap
                }
                try {
                    val repeats = delayToFrameRepeat(frame.delayMs)
                    repeat(repeats) {
                        val inputIndex = awaitInputBuffer(codec)
                        val inputImage = codec.getInputImage(inputIndex)
                            ?: throw IOException("Video encoder did not return input image")
                        inputImage.use {
                            writeBitmapToImage(
                                bitmap = sizedBitmap,
                                image = it,
                                width = width,
                                height = height,
                            )
                        }
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            width * height * 3 / 2,
                            presentationTimeUs,
                            0,
                        )

                        drainEncoder(
                            codec = codec,
                            bufferInfo = bufferInfo,
                            onFormatChanged = { outputFormat ->
                                if (!muxerStarted) {
                                    trackIndex = muxer.addTrack(outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                            },
                            onEncodedSample = { encodedData, info ->
                                if (!muxerStarted || trackIndex < 0) return@drainEncoder
                                if (info.size <= 0) return@drainEncoder
                                encodedData.position(info.offset)
                                encodedData.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, encodedData, info)
                            },
                        )

                        presentationTimeUs += frameDurationUs
                    }
                } finally {
                    if (sizedBitmap !== frame.bitmap) {
                        sizedBitmap.recycle()
                    }
                }
            }

            val eosIndex = awaitInputBuffer(codec)
            codec.queueInputBuffer(
                eosIndex,
                0,
                0,
                presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )

            var reachedEnd = false
            while (!reachedEnd) {
                reachedEnd = drainEncoder(
                    codec = codec,
                    bufferInfo = bufferInfo,
                    onFormatChanged = { outputFormat ->
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    },
                    onEncodedSample = { encodedData, info ->
                        if (!muxerStarted || trackIndex < 0) return@drainEncoder
                        if (info.size <= 0) return@drainEncoder
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encodedData, info)
                    },
                )
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()

            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            muxer.release()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        onFormatChanged: (MediaFormat) -> Unit,
        onEncodedSample: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ): Boolean {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, UGOIRA_CODEC_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChanged(codec.outputFormat)
                }
                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                        ?: throw IOException("Video encoder returned null output buffer")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        onEncodedSample(encodedData, bufferInfo)
                    }
                    val reachedEnd = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (reachedEnd) {
                        return true
                    }
                }
            }
        }
    }

    private fun writeBitmapToImage(
        bitmap: Bitmap,
        image: Image,
        width: Int,
        height: Int,
    ) {
        if (image.planes.size < 3) {
            throw IOException("Video encoder input image is missing YUV planes")
        }

        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (y in 0 until height) {
            val yRowOffset = y * yPlane.rowStride
            val uvRow = y / 2
            val uvRowOffsetU = uvRow * uPlane.rowStride
            val uvRowOffsetV = uvRow * vPlane.rowStride

            for (x in 0 until width) {
                val color = argb[(y * width) + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yBuffer.put(
                    yRowOffset + (x * yPlane.pixelStride),
                    yValue.coerceIn(0, 255).toByte(),
                )

                if (y % 2 == 0 && x % 2 == 0) {
                    val uvColumn = x / 2
                    uBuffer.put(
                        uvRowOffsetU + (uvColumn * uPlane.pixelStride),
                        uValue.coerceIn(0, 255).toByte(),
                    )
                    vBuffer.put(
                        uvRowOffsetV + (uvColumn * vPlane.pixelStride),
                        vValue.coerceIn(0, 255).toByte(),
                    )
                }
            }
        }
    }

    private fun awaitInputBuffer(codec: MediaCodec): Int {
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(UGOIRA_CODEC_TIMEOUT_US)
            if (inputIndex >= 0) {
                return inputIndex
            }
        }
    }

    private fun normalizedVideoDimension(input: Int): Int {
        val candidate = if (input % 2 == 0) input else input - 1
        return max(candidate, 2)
    }

    private fun delayToFrameRepeat(delayMs: Int): Int {
        val normalizedDelay = delayMs.coerceAtLeast(UGOIRA_MIN_DELAY_MS)
        return ((normalizedDelay.toFloat() * UGOIRA_EXPORT_FPS.toFloat()) / 1000f)
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun buildMp4FileName(postId: String, title: String?): String {
        val normalizedTitle = title
            ?.sanitizeFileComponent()
            ?.takeIf { it.isNotBlank() }
            ?: "pixiv_$postId"
        return "${normalizedTitle}_$postId.mp4"
    }

    private fun String.sanitizeFileComponent(): String {
        val cleaned = trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return cleaned.ifBlank { "pixiv" }
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

    private fun cachePlayback(postId: String, playback: UgoiraPlayback) {
        synchronized(cacheLock) {
            playbackCache[postId] = playback
            while (playbackCache.size > UGOIRA_PLAYBACK_CACHE_SIZE) {
                val eldestKey = playbackCache.entries.firstOrNull()?.key ?: break
                playbackCache.remove(eldestKey)
            }
        }
    }

    private fun isRetryableUgoiraStatus(statusCode: Int): Boolean {
        return statusCode == 429 || statusCode in 500..599
    }

    private fun ugoiraRetryDelayMs(attempt: Int): Long {
        val cappedAttempt = attempt.coerceAtLeast(0).coerceAtMost(6)
        return UGOIRA_RETRY_BASE_DELAY_MS * (1L shl cappedAttempt)
    }
}

@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
    showProgressBar: Boolean = false,
) {
    var playback by remember(postId, client) { mutableStateOf(client.cached(postId)) }
    var errorMessage by remember(postId) { mutableStateOf<String?>(null) }
    var frameIndex by remember(postId) { mutableIntStateOf(0) }
    var elapsedInLoopMs by remember(postId) { mutableLongStateOf(0L) }
    var isScrubbing by remember(postId) { mutableStateOf(false) }

    LaunchedEffect(postId, client) {
        frameIndex = 0
        elapsedInLoopMs = 0L
        isScrubbing = false
        errorMessage = null
        playback = client.cached(postId)
        if (playback != null) return@LaunchedEffect
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
            modifier = modifier,
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

    val totalDurationMs = remember(activePlayback) {
        activePlayback.frames.sumOf { it.delayMs.coerceAtLeast(16) }.coerceAtLeast(1)
    }
    val maxSeekablePositionMs = remember(totalDurationMs) {
        (totalDurationMs - 1).coerceAtLeast(0).toLong()
    }

    fun seekToPosition(targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, maxSeekablePositionMs)
        elapsedInLoopMs = clamped

        var accumulated = 0L
        var resolvedIndex = 0
        for (index in activePlayback.frames.indices) {
            val frameDuration = activePlayback.frames[index].delayMs.toLong().coerceAtLeast(16L)
            val next = accumulated + frameDuration
            if (clamped < next || index == activePlayback.frames.lastIndex) {
                resolvedIndex = index
                break
            }
            accumulated = next
        }
        frameIndex = resolvedIndex
    }

    LaunchedEffect(activePlayback, frameIndex, isScrubbing) {
        if (isScrubbing) return@LaunchedEffect
        val delayMs = activePlayback.frames[frameIndex].delayMs.toLong().coerceAtLeast(16L)
        delay(delayMs)
        val nextIndex = (frameIndex + 1) % activePlayback.frames.size
        frameIndex = nextIndex
        elapsedInLoopMs = if (nextIndex == 0) {
            0L
        } else {
            (elapsedInLoopMs + delayMs).coerceAtMost(totalDurationMs.toLong())
        }
    }

    val frame = activePlayback.frames[frameIndex]
    if (!showProgressBar) {
        Image(
            bitmap = frame.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    Box(modifier = modifier) {
        Image(
            bitmap = frame.bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize(),
            contentScale = contentScale,
        )
        MediaTimelineBar(
            positionMs = elapsedInLoopMs,
            durationMs = totalDurationMs.toLong(),
            onSeekStarted = {
                isScrubbing = true
            },
            onSeekChanged = { target ->
                seekToPosition(target)
            },
            onSeekFinished = { target ->
                seekToPosition(target)
                isScrubbing = false
            },
            modifier = Modifier
                .align(Alignment.BottomCenter),
        )
    }
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
private const val UGOIRA_MP4_MIME_TYPE = "video/avc"
private const val UGOIRA_EXPORT_FPS = 30
private const val UGOIRA_CODEC_TIMEOUT_US = 10_000L
private const val UGOIRA_MIN_DELAY_MS = 16
private const val UGOIRA_BITRATE_PER_PIXEL = 6
private const val UGOIRA_MIN_BITRATE = 900_000
private const val UGOIRA_MAX_BITRATE = 12_000_000
private const val UGOIRA_PLAYBACK_CACHE_SIZE = 8
private const val UGOIRA_NETWORK_MAX_ATTEMPTS = 6
private const val UGOIRA_RETRY_BASE_DELAY_MS = 350L
