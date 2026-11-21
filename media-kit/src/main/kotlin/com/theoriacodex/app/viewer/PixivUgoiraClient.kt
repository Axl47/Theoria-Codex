package com.theoriacodex.app.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.decodeEachOrSkip
import com.theoriacodex.domain.coroutines.SharedFlights
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.OffMainWork
import com.theoriacodex.sources.credentials.PixivAuthTokens
import com.theoriacodex.sources.credentials.SourceCredentialsProvider
import com.theoriacodex.sources.http.SourceHttpClient
import com.theoriacodex.sources.pixiv.PixivAuthApi
import com.theoriacodex.sources.pixiv.PixivTokenCoordinator
import com.theoriacodex.sources.pixiv.PixivUgoiraMetadataCache
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

class PixivUgoiraClient constructor(
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
    private val metadataFetcher: ((String, String) -> TextResponse)? = null,
    private val zipDownloader: ((String, String, File) -> BinaryResponse)? = null,
    private val decode: UgoiraFrameDecoder = ::decodeUgoiraFrames,
    private val operationTimeoutMs: Long = 30_000L,
    private val offlineArchiveLookup: suspend (String) -> File? = { null },
    private val metadataCache: PixivUgoiraMetadataCache = PixivUgoiraMetadataCache(),
    downloadClient: OkHttpClient = OkHttpClient(),
) {
    private val transport = PixivUgoiraTransport(downloadClient)
    private val cacheLock = Any()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackCache = LinkedHashMap<UgoiraLoadKey, UgoiraPlayback>(
        4,
        0.75f,
        true,
    )
    private val loadFlights = mutableMapOf<UgoiraLoadKey, SharedUgoiraLoad>()
    private val archiveCache = LinkedHashMap<String, UgoiraArchive>(16, 0.75f, true)
    private val archiveFlights = SharedFlights<String, UgoiraArchive>()

    /** Callers waiting on each post's archive flight; clearing and pruning leave those archives alone. */
    private val archiveWaiters = mutableMapOf<String, Int>()
    private val archiveSlots = Semaphore(3)
    private var decodedCacheBytes = 0L
    private var offlineCopyConsumers = 0

    fun cached(
        postId: String,
        sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    ): UgoiraPlayback? = synchronized(cacheLock) {
        playbackCache[UgoiraLoadKey(postId, sizeBucket)]
    }

    suspend fun load(
        postId: String,
        sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    ): Result<UgoiraPlayback> = observeLoad(postId, sizeBucket).first { result ->
        result.isFailure || result.getOrThrow().isComplete
    }

    /**
     * Downloads and validates a post's archive into the bounded disk store ahead of playback, so playback only
     * decodes. It joins an archive download already in flight (and playback joins this one); returns whether the
     * archive is ready.
     */
    suspend fun warmArchive(postId: String): Boolean =
        runCatchingPreservingCancellation { archiveFor(postId) }.isSuccess

    /** Share partial frames and completion; the final departing consumer cancels its work. */
    fun observeLoad(
        postId: String,
        sizeBucket: UgoiraSizeBucket = UgoiraSizeBucket.VIEWER,
    ): Flow<Result<UgoiraPlayback>> = flow {
        cached(postId, sizeBucket)?.let { emit(Result.success(it)); return@flow }
        val key = UgoiraLoadKey(postId, sizeBucket)
        val shared = synchronized(cacheLock) {
            loadFlights[key]?.also { it.consumers += 1 } ?: createLoad(key)
        }
        shared.deferred.start()
        try {
            shared.updates.filterNotNull().first { result ->
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                emit(result)
                result.isFailure || result.getOrThrow().isComplete
            }
        } finally {
            synchronized(cacheLock) {
                shared.consumers -= 1
                if (shared.consumers == 0) {
                    if (loadFlights[key] === shared) loadFlights.remove(key)
                    shared.deferred.cancel()
                }
            }
        }
    }

    private fun createLoad(key: UgoiraLoadKey): SharedUgoiraLoad {
        val updates = MutableStateFlow<Result<UgoiraPlayback>?>(null)
        val deferred = loadScope.async(start = CoroutineStart.LAZY) {
            try {
                val playback = withTimeoutOrNull(operationTimeoutMs) {
                    cached(key.postId, key.sizeBucket) ?: loadOrThrow(key.postId, key.sizeBucket) { partial ->
                        if (!partial.isComplete) updates.value = Result.success(partial)
                    }
                } ?: throw IOException("Pixiv animation loading timed out")
                currentCoroutineContext().ensureActive()
                cachePlayback(key, playback)
                updates.value = Result.success(playback)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updates.value = Result.failure(error)
            }
        }
        deferred.invokeOnCompletion { error ->
            if (error != null) updates.value = Result.failure(error)
        }
        return SharedUgoiraLoad(deferred, updates, consumers = 1).also { loadFlights[key] = it }
    }

    suspend fun exportToMp4(
        context: Context,
        postId: String,
        title: String?,
        marker: String? = null,
    ): Result<Uri> {
        return runCatchingPreservingCancellation {
            exportToMp4OrThrow(context, postId, title, marker)
        }
    }

    /** Preserve the validated original archive and timing together for network-free playback. */
    suspend fun copyArchiveForOffline(postId: String, destination: File) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) { offlineCopyConsumers++ }
        try {
            val archive = archiveFor(postId)
            archive.file.copyTo(destination, overwrite = true)
            writeUgoiraFrameMetadata(destination, archive.metadata.frames)
            check(readUgoiraFrameMetadata(destination) != null) { "Could not save animation timing" }
        } finally {
            synchronized(cacheLock) { offlineCopyConsumers-- }
        }
    }

    /** Clearing cached archives must not remove an archive while a playback/export consumer is using it. */
    suspend fun clearDisposableCache(): Boolean = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            if (loadFlights.isNotEmpty() || archiveWaiters.isNotEmpty() || offlineCopyConsumers > 0) return@synchronized false
            playbackCache.clear()
            decodedCacheBytes = 0L
            archiveCache.clear()
            archiveDirectory.listFiles().orEmpty().forEach(File::delete)
            true
        }
    }

    /** Drops decoded frames under memory pressure; disk archives stay, so playback only decodes again. */
    fun releaseMemoryCaches() = synchronized(cacheLock) {
        playbackCache.clear()
        decodedCacheBytes = 0L
        archiveCache.clear()
    }

    suspend fun disposableCacheBytes(): Long = withContext(Dispatchers.IO) {
        archiveDirectory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
    }

    private suspend fun loadOrThrow(
        postId: String,
        sizeBucket: UgoiraSizeBucket,
        publish: suspend (UgoiraPlayback) -> Unit,
    ): UgoiraPlayback = withContext(Dispatchers.IO) {
        val archive = archiveFor(postId)
        try {
            decode(archive.file, archive.metadata.frames, sizeBucket, publish)
        } catch (error: IOException) {
            forgetCachedArchive(postId, archive.file)
            throw error
        }
    }

    /**
     * A cached archive that fails to decode loses its validated sidecar, so the next load revalidates
     * and re-downloads it. Offline archives are user data and are never touched.
     */
    private fun forgetCachedArchive(postId: String, file: File) {
        if (file.parentFile != archiveDirectory) return
        synchronized(cacheLock) { archiveCache.remove(postId) }
        ugoiraMetadataFile(file).delete()
    }

    private suspend fun archiveFor(postId: String): UgoiraArchive {
        synchronized(cacheLock) {
            archiveCache[postId]?.takeIf { it.file.isFile }?.let { return it }
            archiveWaiters[postId] = (archiveWaiters[postId] ?: 0) + 1
        }
        try {
            val archive = archiveFlights.share(postId) {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(operationTimeoutMs) {
                        archiveSlots.withPermit { loadArchiveOrThrow(postId) }
                    } ?: throw IOException("Pixiv animation download timed out")
                }
            }
            synchronized(cacheLock) {
                archiveCache[postId] = archive
                while (archiveCache.size > UGOIRA_ARCHIVE_MEMORY_ENTRY_LIMIT) {
                    archiveCache.remove(archiveCache.entries.first().key)
                }
            }
            return archive
        } finally {
            synchronized(cacheLock) {
                val remaining = (archiveWaiters[postId] ?: 1) - 1
                if (remaining > 0) archiveWaiters[postId] = remaining else archiveWaiters.remove(postId)
            }
        }
    }

    private suspend fun loadArchiveOrThrow(postId: String): UgoiraArchive {
        offlineArchiveLookup(postId)?.let { offline ->
            val frames = readUgoiraFrameMetadata(offline)
            if (frames != null && isValidArchive(offline, frames)) {
                return UgoiraArchive(ParsedMetadata(zipUrl = "", frames = frames), offline)
            }
        }
        if (!archiveDirectory.isDirectory && !archiveDirectory.mkdirs() && !archiveDirectory.isDirectory) {
            throw IOException("Could not create Pixiv ugoira cache directory")
        }
        val archive = File(archiveDirectory, "${postId.safeCacheComponent()}.zip")
        val diskFrames = readUgoiraFrameMetadata(archive)
        // The sidecar is written only after full validation; decoding still enforces per-frame limits.
        if (diskFrames != null && isStructurallyValidArchive(archive, diskFrames)) {
            archive.setLastModified(System.currentTimeMillis())
            writeUgoiraFrameMetadata(archive, diskFrames)
            return UgoiraArchive(ParsedMetadata(zipUrl = "", frames = diskFrames), archive)
        }
        val currentTokens = tokenCoordinator.activeTokens()
        val (metadata, tokensAfterMetadata) = fetchMetadataWithRetry(postId, currentTokens)
        if (archive.isFile) {
            if (isValidArchive(archive, metadata.frames)) {
                archive.setLastModified(System.currentTimeMillis())
                writeUgoiraFrameMetadata(archive, metadata.frames)
                return UgoiraArchive(metadata = metadata, file = archive)
            }
            archive.delete()
            ugoiraMetadataFile(archive).delete()
        }
        downloadZipWithRetry(
            url = metadata.zipUrl,
            initialTokens = tokensAfterMetadata,
            destination = archive,
        )
        if (!isValidArchive(archive, metadata.frames)) {
            archive.delete()
            ugoiraMetadataFile(archive).delete()
            throw IOException("Pixiv ugoira archive is invalid")
        }
        writeUgoiraFrameMetadata(archive, metadata.frames)
        pruneArchiveDirectory(protected = archive)
        return UgoiraArchive(metadata = metadata, file = archive)
    }

    private suspend fun isStructurallyValidArchive(archive: File, frames: List<UgoiraFrameSpec>): Boolean =
        runCatchingPreservingCancellation {
            runInterruptible(Dispatchers.IO) { checkUgoiraArchiveStructure(archive, frames) }
        }.isSuccess

    private suspend fun isValidArchive(archive: File, frames: List<UgoiraFrameSpec>): Boolean {
        val context = currentCoroutineContext()
        return runCatchingPreservingCancellation {
            runInterruptible(Dispatchers.IO) { validateUgoiraArchive(archive, frames) { context.ensureActive() } }
        }.isSuccess
    }

    private suspend fun fetchMetadataWithRetry(
        postId: String,
        initialTokens: PixivAuthTokens,
    ): Pair<ParsedMetadata, PixivAuthTokens> {
        metadataCache.get(postId)
            ?.let { body -> runCatching { parseMetadata(body) }.getOrNull() }
            ?.let { cached -> return cached to initialTokens }
        val (response, tokens) = withUgoiraAuthRetry("metadata", initialTokens, TextResponse::statusCode) { accessToken ->
            metadataFetcher?.let { fetch ->
                runInterruptible(Dispatchers.IO) { fetch(postId, accessToken) }
            } ?: transport.fetchMetadata(postId, accessToken)
        }
        val parsed = parseMetadata(response.body)
        metadataCache.put(postId, response.body)
        return parsed to tokens
    }

    private suspend fun downloadZipWithRetry(
        url: String,
        initialTokens: PixivAuthTokens,
        destination: File,
    ) {
        withUgoiraAuthRetry("zip", initialTokens, BinaryResponse::statusCode) { accessToken ->
            zipDownloader?.let { download ->
                runInterruptible(Dispatchers.IO) { download(url, accessToken, destination) }
            } ?: transport.downloadZip(url, accessToken, destination)
        }
    }

    /**
     * Runs a Pixiv [request] up to [UGOIRA_NETWORK_MAX_ATTEMPTS] times, returning the successful response and the
     * tokens it used. I/O errors and 429/5xx back off and retry; the first 401/403 refreshes the tokens and retries,
     * a second one fails.
     */
    private suspend fun <R> withUgoiraAuthRetry(
        label: String,
        initialTokens: PixivAuthTokens,
        statusOf: (R) -> Int,
        request: suspend (accessToken: String) -> R,
    ): Pair<R, PixivAuthTokens> {
        var tokens = initialTokens
        var didRefreshAfterAuthFailure = false

        repeat(UGOIRA_NETWORK_MAX_ATTEMPTS) { attempt ->
            val response = try {
                request(tokens.accessToken)
            } catch (error: IOException) {
                if (attempt >= UGOIRA_NETWORK_MAX_ATTEMPTS - 1) throw error
                delay(ugoiraRetryDelayMs(attempt))
                return@repeat
            }
            val status = statusOf(response)
            when {
                status in 200..299 -> return response to tokens

                status == 401 || status == 403 -> {
                    if (didRefreshAfterAuthFailure) throw IOException("Pixiv ugoira $label request failed ($status)")
                    tokens = tokenCoordinator.refreshAfterAuthFailure(tokens)
                    didRefreshAfterAuthFailure = true
                }

                isRetryableUgoiraStatus(status) && attempt < UGOIRA_NETWORK_MAX_ATTEMPTS - 1 -> {
                    delay(ugoiraRetryDelayMs(attempt))
                }

                else -> throw IOException("Pixiv ugoira $label request failed ($status)")
            }
        }

        throw IOException("Pixiv ugoira $label request failed (retry limit reached)")
    }

    private suspend fun exportToMp4OrThrow(
        context: Context,
        postId: String,
        title: String?,
        marker: String?,
    ): Uri = withContext(Dispatchers.IO) {
        val destination = com.theoriacodex.app.media.AnimationExportDestination(context)
        marker?.let { destination.findCompleted(it)?.let { uri -> return@withContext uri } }
        val playback = load(postId, UgoiraSizeBucket.EXPORT).getOrThrow()
        destination.save(ugoiraMp4FileName(postId = postId, title = title), marker) { descriptor ->
            encodeUgoiraMp4(playback = playback, outputFileDescriptor = descriptor)
        }
    }

    private fun parseMetadata(body: String): ParsedMetadata {
        OffMainWork.note(OffMainWork.SOURCE_PARSE)
        val root = try {
            DurableJson.decodeFromString<UgoiraMetadataResponse>(body)
        } catch (_: SerializationException) {
            throw IOException("Pixiv ugoira metadata was not valid JSON")
        }
        val metadata = root.metadata ?: throw IOException("Pixiv ugoira metadata payload missing")
        val zipUrls = metadata.zipUrls ?: throw IOException("Pixiv ugoira zip_urls missing")
        val zipUrl = zipUrls["medium"] ?: zipUrls.values.firstOrNull() ?: throw IOException("Pixiv ugoira zip URL missing")
        val frameSpecs = metadata.frames.decodeEachOrSkip<UgoiraMetadataFrame>().mapNotNull { frame ->
            val file = frame.file.trim()
            if (file.isBlank()) null else UgoiraFrameSpec(fileName = file, delayMs = frame.delay.coerceAtLeast(16))
        }
        if (frameSpecs.isEmpty()) {
            throw IOException("Pixiv ugoira frames are empty")
        }
        return ParsedMetadata(zipUrl = zipUrl, frames = frameSpecs)
    }

    private fun cachePlayback(key: UgoiraLoadKey, playback: UgoiraPlayback) {
        synchronized(cacheLock) {
            if (!playback.isComplete || key.sizeBucket == UgoiraSizeBucket.EXPORT) return
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
        val inUseNames = synchronized(cacheLock) {
            (loadFlights.keys.map { it.postId } + archiveWaiters.keys).mapTo(mutableSetOf()) {
                "${it.safeCacheComponent()}.zip"
            }
        }
        val archives = archiveDirectory.listFiles { file -> file.isFile && file.extension == "zip" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        var retainedCount = 0
        archives.forEach { archive ->
            if (archive == protected || archive.name in inUseNames ||
                (retainedCount < 128 && retainedBytes + archive.length() <= UGOIRA_ARCHIVE_CACHE_MAX_BYTES)
            ) {
                retainedBytes += archive.length()
                retainedCount += 1
            } else {
                archive.delete()
                ugoiraMetadataFile(archive).delete()
            }
        }
    }
}

data class UgoiraPlayback(
    val frames: List<UgoiraFrame>,
    val frameDelaysMs: List<Int> = frames.map(UgoiraFrame::delayMs),
) {
    init {
        require(frames.isNotEmpty() && frames.size <= frameDelaysMs.size)
    }

    val isComplete: Boolean get() = frames.size == frameDelaysMs.size
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

/** The app API's `/v1/ugoira/metadata` response; only the fields playback needs. */
@Serializable
private class UgoiraMetadataResponse(
    @SerialName("ugoira_metadata")
    val metadata: UgoiraMetadataBody? = null,
)

@Serializable
private class UgoiraMetadataBody(
    @SerialName("zip_urls")
    val zipUrls: Map<String, String>? = null,
    /** Decoded one at a time, so a malformed frame is skipped instead of failing playback. */
    @SerialName("frames")
    val frames: List<JsonElement> = emptyList(),
)

@Serializable
private class UgoiraMetadataFrame(
    @SerialName("file")
    val file: String = "",
    @SerialName("delay")
    val delay: Int = 50,
)

data class TextResponse(
    val statusCode: Int,
    val body: String,
)

data class BinaryResponse(
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
    val deferred: Deferred<Unit>,
    val updates: MutableStateFlow<Result<UgoiraPlayback>?>,
    var consumers: Int,
)

private const val UGOIRA_NETWORK_MAX_ATTEMPTS = 3
private const val UGOIRA_RETRY_BASE_DELAY_MS = 350L
internal const val UGOIRA_DECODED_CACHE_MAX_BYTES = 96L * 1024L * 1024L
internal const val UGOIRA_ARCHIVE_CACHE_MAX_BYTES = 512L * 1024L * 1024L
private const val UGOIRA_ARCHIVE_MEMORY_ENTRY_LIMIT = 32
