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
import androidx.core.graphics.scale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import com.theoriacodex.sources.pixiv.PixivTokenCoordinator
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class PixivUgoiraClient internal constructor(
    credentialsProvider: SourceCredentialsProvider,
    httpClient: SourceHttpClient,
    authApi: PixivAuthApi = PixivAuthApi(httpClient),
    private val tokenCoordinator: PixivTokenCoordinator = PixivTokenCoordinator(
        credentialsProvider = credentialsProvider,
        authApi = authApi,
    ),
    private val archiveDirectory: File = File(
        System.getProperty("java.io.tmpdir").orEmpty(),
        "theoria_codex/pixiv/ugoira",
    ),
    private val gson: Gson = Gson(),
    private val metadataFetcher: ((String, String) -> TextResponse)? = null,
    private val zipDownloader: ((String, String, File) -> BinaryResponse)? = null,
) {
    private val cacheLock = Any()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackCache = LinkedHashMap<UgoiraLoadKey, UgoiraPlayback>(
        4,
        0.75f,
        true,
    )
    private val loadFlights = mutableMapOf<UgoiraLoadKey, SharedUgoiraLoad>()
    private val archiveCache = LinkedHashMap<String, UgoiraArchive>(16, 0.75f, true)
    private val archiveFlights = mutableMapOf<String, Deferred<UgoiraArchive>>()
    private var decodedCacheBytes = 0L

    fun cached(
        postId: String,
        sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    ): UgoiraPlayback? = synchronized(cacheLock) {
        playbackCache[UgoiraLoadKey(postId, sizeBucket)]
    }

    suspend fun load(
        postId: String,
        sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    ): Result<UgoiraPlayback> {
        cached(postId, sizeBucket)?.let { return Result.success(it) }
        val key = UgoiraLoadKey(postId, sizeBucket)
        val shared = synchronized(cacheLock) {
            loadFlights[key]?.also { flight -> flight.consumers += 1 } ?: run {
                val deferred = loadScope.async {
                    loadOrThrow(postId, sizeBucket).also { playback -> cachePlayback(key, playback) }
                }
                SharedUgoiraLoad(deferred = deferred, consumers = 1).also { flight ->
                    loadFlights[key] = flight
                    deferred.invokeOnCompletion {
                        synchronized(cacheLock) {
                            if (loadFlights[key] === flight) loadFlights.remove(key)
                        }
                    }
                }
            }
        }
        return try {
            runCatchingPreservingCancellation { shared.deferred.await() }
        } finally {
            synchronized(cacheLock) {
                shared.consumers -= 1
                if (shared.consumers == 0 && !shared.deferred.isCompleted) shared.deferred.cancel()
            }
        }
    }

    suspend fun exportToMp4(
        context: Context,
        postId: String,
        title: String?,
    ): Result<Uri> {
        return runCatchingPreservingCancellation {
            exportToMp4OrThrow(context, postId, title)
        }
    }

    private suspend fun loadOrThrow(
        postId: String,
        sizeBucket: UgoiraSizeBucket,
    ): UgoiraPlayback = withContext(Dispatchers.IO) {
        val archive = archiveFor(postId)
        UgoiraPlayback(
            frames = decodeFrames(
                archive = archive.file,
                specs = archive.metadata.frames,
                maxDimension = sizeBucket.maxDimension,
            ),
        )
    }

    private suspend fun archiveFor(postId: String): UgoiraArchive {
        synchronized(cacheLock) { archiveCache[postId] }?.let { return it }
        val deferred = synchronized(cacheLock) {
            archiveFlights[postId] ?: loadScope.async { loadArchiveOrThrow(postId) }.also { flight ->
                archiveFlights[postId] = flight
                flight.invokeOnCompletion {
                    synchronized(cacheLock) {
                        if (archiveFlights[postId] === flight) archiveFlights.remove(postId)
                    }
                }
            }
        }
        return deferred.await().also { archive ->
            synchronized(cacheLock) {
                archiveCache[postId] = archive
                while (archiveCache.size > UGOIRA_ARCHIVE_MEMORY_ENTRY_LIMIT) {
                    archiveCache.remove(archiveCache.entries.first().key)
                }
            }
        }
    }

    private suspend fun loadArchiveOrThrow(postId: String): UgoiraArchive {
        val currentTokens = tokenCoordinator.activeTokens()
        val (metadata, tokensAfterMetadata) = fetchMetadataWithRetry(postId, currentTokens)
        if (!archiveDirectory.exists() && !archiveDirectory.mkdirs()) {
            throw IOException("Could not create Pixiv ugoira cache directory")
        }
        val archive = File(archiveDirectory, "${postId.safeCacheComponent()}.zip")
        if (archive.isFile) {
            val valid = runCatching { validateUgoiraArchive(archive, metadata.frames) }.isSuccess
            if (valid) {
                archive.setLastModified(System.currentTimeMillis())
                return UgoiraArchive(metadata = metadata, file = archive)
            }
            archive.delete()
        }
        downloadZipWithRetry(
            url = metadata.zipUrl,
            initialTokens = tokensAfterMetadata,
            destination = archive,
        )
        validateUgoiraArchive(archive, metadata.frames)
        pruneArchiveDirectory(protected = archive)
        return UgoiraArchive(metadata = metadata, file = archive)
    }

    private suspend fun fetchMetadataWithRetry(
        postId: String,
        initialTokens: PixivAuthTokens,
    ): Pair<ParsedMetadata, PixivAuthTokens> {
        var tokens = initialTokens
        var didRefreshAfterAuthFailure = false

        repeat(UGOIRA_NETWORK_MAX_ATTEMPTS) { attempt ->
            val metadataResponse = try {
                runInterruptible(Dispatchers.IO) {
                    metadataFetcher?.invoke(postId, tokens.accessToken)
                        ?: fetchMetadata(postId, tokens.accessToken)
                }
            } catch (error: IOException) {
                if (attempt >= UGOIRA_NETWORK_MAX_ATTEMPTS - 1) throw error
                delay(ugoiraRetryDelayMs(attempt))
                return@repeat
            }
            when {
                metadataResponse.statusCode in 200..299 -> {
                    return parseMetadata(metadataResponse.body) to tokens
                }

                metadataResponse.statusCode == 401 || metadataResponse.statusCode == 403 -> {
                    if (didRefreshAfterAuthFailure) {
                        throw IOException("Pixiv ugoira metadata request failed (${metadataResponse.statusCode})")
                    }
                    tokens = tokenCoordinator.refreshAfterAuthFailure(tokens)
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
        destination: File,
    ) {
        var tokens = initialTokens
        var didRefreshAfterAuthFailure = false

        repeat(UGOIRA_NETWORK_MAX_ATTEMPTS) { attempt ->
            val zipResponse = try {
                runInterruptible(Dispatchers.IO) {
                    zipDownloader?.invoke(url, tokens.accessToken, destination)
                        ?: downloadZip(url, tokens.accessToken, destination)
                }
            } catch (error: IOException) {
                if (attempt >= UGOIRA_NETWORK_MAX_ATTEMPTS - 1) throw error
                delay(ugoiraRetryDelayMs(attempt))
                return@repeat
            }
            when {
                zipResponse.statusCode in 200..299 -> return

                zipResponse.statusCode == 401 || zipResponse.statusCode == 403 -> {
                    if (didRefreshAfterAuthFailure) {
                        throw IOException("Pixiv ugoira zip request failed (${zipResponse.statusCode})")
                    }
                    tokens = tokenCoordinator.refreshAfterAuthFailure(tokens)
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
        val playback = load(postId, UgoiraSizeBucket.EXPORT).getOrThrow()
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
                    frame.bitmap.scale(width, height, filter = true)
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

    private fun fetchMetadata(postId: String, accessToken: String): TextResponse {
        val url = "${PIXIV_API_BASE}/v1/ugoira/metadata?illust_id=$postId"
        val connection = openConnection(url, accessToken)
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            TextResponse(statusCode = status, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadZip(
        url: String,
        accessToken: String,
        destination: File,
    ): BinaryResponse {
        val connection = openConnection(url, accessToken)
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        return try {
            val status = connection.responseCode
            if (status in 200..299) {
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(UGOIRA_DOWNLOAD_BUFFER_BYTES)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > UGOIRA_MAX_COMPRESSED_BYTES) {
                                throw IOException("Pixiv ugoira archive exceeds compressed-byte limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not publish Pixiv ugoira archive")
                }
            } else {
                connection.errorStream?.close()
            }
            BinaryResponse(statusCode = status)
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
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
        archive: File,
        specs: List<UgoiraFrameSpec>,
        maxDimension: Int,
    ): List<UgoiraFrame> {
        validateUgoiraArchive(archive, specs)
        val frames = mutableListOf<UgoiraFrame>()
        var decodedBytes = 0L
        try {
            ZipFile(archive).use { zip ->
                specs.forEach { spec ->
                    val entry = zip.getEntry(spec.fileName)
                        ?: throw IOException("Pixiv ugoira frame is missing")
                    val frameBytes = zip.getInputStream(entry).use { it.readBytes() }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, bounds)
                    validateFrameDimensions(bounds.outWidth, bounds.outHeight)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = ugoiraSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size, options)
                        ?: throw IOException("Pixiv ugoira frame could not be decoded")
                    decodedBytes += bitmap.allocationByteCount.toLong()
                    if (decodedBytes > UGOIRA_MAX_DECODED_PLAYBACK_BYTES) {
                        bitmap.recycle()
                        throw IOException("Pixiv ugoira decoded frames exceed memory limit")
                    }
                    frames += UgoiraFrame(bitmap = bitmap, delayMs = spec.delayMs)
                }
            }
        } catch (error: Throwable) {
            frames.forEach { frame -> frame.bitmap.recycle() }
            throw error
        }
        if (frames.isEmpty()) {
            throw IOException("Pixiv ugoira zip could not be decoded")
        }
        return frames
    }

    private fun cachePlayback(key: UgoiraLoadKey, playback: UgoiraPlayback) {
        synchronized(cacheLock) {
            val weight = playback.allocationBytes
            if (weight > UGOIRA_DECODED_CACHE_MAX_BYTES) return
            playbackCache.put(key, playback)?.let { previous ->
                decodedCacheBytes -= previous.allocationBytes
            }
            decodedCacheBytes += weight
            while (decodedCacheBytes > UGOIRA_DECODED_CACHE_MAX_BYTES) {
                val eldestKey = playbackCache.entries.firstOrNull()?.key ?: break
                playbackCache.remove(eldestKey)?.let { removed ->
                    decodedCacheBytes -= removed.allocationBytes
                }
            }
        }
    }

    internal fun cachedDecodedBytes(): Long = synchronized(cacheLock) { decodedCacheBytes }

    private fun isRetryableUgoiraStatus(statusCode: Int): Boolean {
        return statusCode == 429 || statusCode in 500..599
    }

    private fun ugoiraRetryDelayMs(attempt: Int): Long {
        val cappedAttempt = attempt.coerceAtLeast(0).coerceAtMost(6)
        return UGOIRA_RETRY_BASE_DELAY_MS * (1L shl cappedAttempt)
    }

    private fun String.safeCacheComponent(): String {
        return replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "ugoira" }
    }

    private fun pruneArchiveDirectory(protected: File) {
        val archives = archiveDirectory.listFiles { file -> file.isFile && file.extension == "zip" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        archives.forEach { archive ->
            if (archive == protected || retainedBytes + archive.length() <= UGOIRA_ARCHIVE_CACHE_MAX_BYTES) {
                retainedBytes += archive.length()
            } else {
                archive.delete()
            }
        }
    }

    private fun validateFrameDimensions(width: Int, height: Int) {
        val pixels = width.toLong() * height.toLong()
        if (
            width <= 0 || height <= 0 ||
            width > UGOIRA_MAX_FRAME_DIMENSION || height > UGOIRA_MAX_FRAME_DIMENSION ||
            pixels > UGOIRA_MAX_FRAME_PIXELS
        ) {
            throw IOException("Pixiv ugoira frame dimensions exceed supported limits")
        }
    }

    private fun ugoiraSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (maxDimension == Int.MAX_VALUE) return 1
        var sampleSize = 1
        while (max(width, height) / sampleSize > maxDimension && sampleSize < 16) {
            sampleSize *= 2
        }
        return sampleSize
    }
}

@Composable
fun PixivUgoiraPlayer(
    postId: String,
    client: PixivUgoiraClient,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
    sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    showProgressBar: Boolean = false,
    isActive: Boolean = true,
    isPlaying: Boolean? = null,
    seekJumpSerial: Int = 0,
    seekJumpDeltaMs: Long = 0L,
    playbackRate: Float = 1f,
    restartRequest: Long = 0L,
    loadGeneration: Long = 0L,
    onTimelineInteractionActiveChanged: (Boolean) -> Unit = {},
    onTogglePlayback: (() -> Unit)? = null,
    onProgressChanged: (Long, Long?) -> Unit = { _, _ -> },
    onDurationKnown: (Long) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var playback by remember(postId, client, sizeBucket, loadGeneration) {
        mutableStateOf(client.cached(postId, sizeBucket))
    }
    var errorMessage by remember(postId, loadGeneration) { mutableStateOf<String?>(null) }
    var frameIndex by remember(postId, loadGeneration) { mutableIntStateOf(0) }
    var elapsedInLoopMs by remember(postId, loadGeneration) { mutableLongStateOf(0L) }
    var isScrubbing by remember(postId, loadGeneration) { mutableStateOf(false) }
    var playbackPaused by remember(postId, loadGeneration) { mutableStateOf(false) }
    val effectivePlaybackRate = playbackRate.coerceAtLeast(0.1f)
    val effectivePlaybackPaused = isPlaying?.not() ?: playbackPaused

    LaunchedEffect(postId, client, sizeBucket, isActive, loadGeneration) {
        frameIndex = 0
        elapsedInLoopMs = 0L
        isScrubbing = false
        playbackPaused = false
        errorMessage = null
        playback = client.cached(postId, sizeBucket)
        if (playback != null || !isActive) return@LaunchedEffect
        val result = client.load(postId, sizeBucket)
        result.onSuccess { loaded ->
            playback = loaded
        }.onFailure { error ->
            errorMessage = error.message ?: "Could not load animation"
            onError(errorMessage.orEmpty())
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
    LaunchedEffect(postId, totalDurationMs) {
        onDurationKnown(totalDurationMs.toLong())
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

    LaunchedEffect(restartRequest) {
        if (restartRequest > 0L) seekToPosition(0L)
    }

    LaunchedEffect(seekJumpSerial, seekJumpDeltaMs, maxSeekablePositionMs, isScrubbing, isActive) {
        if (seekJumpSerial <= 0 || seekJumpDeltaMs == 0L || isScrubbing || !isActive) {
            return@LaunchedEffect
        }
        seekToPosition(elapsedInLoopMs + seekJumpDeltaMs)
    }

    LaunchedEffect(activePlayback, frameIndex, isScrubbing, effectivePlaybackPaused, isActive, effectivePlaybackRate) {
        if (isScrubbing || effectivePlaybackPaused || !isActive) return@LaunchedEffect
        val delayMs = activePlayback.frames[frameIndex].delayMs.toLong().coerceAtLeast(16L)
        val scaledDelayMs = (delayMs / effectivePlaybackRate).toLong().coerceAtLeast(1L)
        delay(scaledDelayMs)
        val nextIndex = (frameIndex + 1) % activePlayback.frames.size
        frameIndex = nextIndex
        elapsedInLoopMs = if (nextIndex == 0) {
            0L
        } else {
            (elapsedInLoopMs + delayMs).coerceAtMost(totalDurationMs.toLong())
        }
        onProgressChanged(elapsedInLoopMs, totalDurationMs.toLong())
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
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelinePlaybackButton(
                isPaused = effectivePlaybackPaused,
                onToggle = {
                    if (onTogglePlayback != null) onTogglePlayback() else playbackPaused = !playbackPaused
                    onTimelineInteractionActiveChanged(true)
                    onTimelineInteractionActiveChanged(false)
                },
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
                onInteractionActiveChanged = onTimelineInteractionActiveChanged,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

data class UgoiraPlayback(
    val frames: List<UgoiraFrame>,
) {
    val allocationBytes: Long = frames.sumOf { frame -> frame.bitmap.allocationByteCount.toLong() }
}

data class UgoiraFrame(
    val bitmap: Bitmap,
    val delayMs: Int,
)

private data class ParsedMetadata(
    val zipUrl: String,
    val frames: List<UgoiraFrameSpec>,
)

internal data class TextResponse(
    val statusCode: Int,
    val body: String,
)

internal data class BinaryResponse(
    val statusCode: Int,
)

private data class UgoiraArchive(
    val metadata: ParsedMetadata,
    val file: File,
)

private data class UgoiraLoadKey(
    val postId: String,
    val sizeBucket: UgoiraSizeBucket,
)

private data class SharedUgoiraLoad(
    val deferred: Deferred<UgoiraPlayback>,
    var consumers: Int,
)

enum class UgoiraSizeBucket(
    internal val maxDimension: Int,
) {
    CARD(720),
    VIEWER(2_048),
    EXPORT(Int.MAX_VALUE),
}

private const val PIXIV_API_BASE = "https://app-api.pixiv.net"
private const val UGOIRA_MP4_MIME_TYPE = "video/avc"
private const val UGOIRA_EXPORT_FPS = 30
private const val UGOIRA_CODEC_TIMEOUT_US = 10_000L
private const val UGOIRA_MIN_DELAY_MS = 16
private const val UGOIRA_BITRATE_PER_PIXEL = 6
private const val UGOIRA_MIN_BITRATE = 900_000
private const val UGOIRA_MAX_BITRATE = 12_000_000
private const val UGOIRA_NETWORK_MAX_ATTEMPTS = 6
private const val UGOIRA_RETRY_BASE_DELAY_MS = 350L
private const val UGOIRA_DOWNLOAD_BUFFER_BYTES = 64 * 1024
internal const val UGOIRA_DECODED_CACHE_MAX_BYTES = 96L * 1024L * 1024L
internal const val UGOIRA_MAX_DECODED_PLAYBACK_BYTES = 192L * 1024L * 1024L
internal const val UGOIRA_MAX_FRAME_DIMENSION = 8_192
internal const val UGOIRA_MAX_FRAME_PIXELS = 40_000_000L
internal const val UGOIRA_ARCHIVE_CACHE_MAX_BYTES = 512L * 1024L * 1024L
private const val UGOIRA_ARCHIVE_MEMORY_ENTRY_LIMIT = 32
