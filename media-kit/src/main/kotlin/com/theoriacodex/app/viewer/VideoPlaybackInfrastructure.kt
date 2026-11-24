@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.MediaSource
import com.theoriacodex.app.diagnostics.NetworkUsage
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal const val VIDEO_PLAYBACK_CACHE_MAX_BYTES = 256L * 1024L * 1024L

const val VIEWER_VIDEO_PREFETCH_BYTES = 4L * 1024L * 1024L
internal const val FEED_PREVIEW_TARGET_BUFFER_BYTES = 2 * 1024 * 1024
internal const val FEED_PREVIEW_MIN_BUFFER_MS = 6_000
internal const val FEED_PREVIEW_MAX_BUFFER_MS = 12_000
internal const val FEED_PREVIEW_PLAYBACK_BUFFER_MS = 750
internal const val FEED_PREVIEW_REBUFFER_MS = 1_500

internal const val VIEWER_START_BUFFER_MS = 500

internal enum class VideoPlaybackProfile(val usesPlatformDiagnostics: Boolean) {
    FEED_PREVIEW(usesPlatformDiagnostics = false),
    VIEWER(usesPlatformDiagnostics = true),
}

internal object VideoLoadControlFactory {
    fun create(profile: VideoPlaybackProfile): LoadControl {
        return when (profile) {
            VideoPlaybackProfile.FEED_PREVIEW -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    FEED_PREVIEW_MIN_BUFFER_MS,
                    FEED_PREVIEW_MAX_BUFFER_MS,
                    FEED_PREVIEW_PLAYBACK_BUFFER_MS,
                    FEED_PREVIEW_REBUFFER_MS,
                )
                .setTargetBufferBytes(FEED_PREVIEW_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(0, false)
                .build()

            VideoPlaybackProfile.VIEWER -> ViewerLoadControl()
        }
    }
}

private class ViewerLoadControl : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    DEFAULT_MIN_BUFFER_MS,
    DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_MAX_BUFFER_MS,
    DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    VIEWER_START_BUFFER_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
    DEFAULT_TARGET_BUFFER_BYTES,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
    DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
    DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
    emptyMap(),
) {
    private var stalled = false

    override fun onPrepared(playerId: PlayerId) {
        stalled = false
        super.onPrepared(playerId)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (parameters.rebuffering) stalled = true
        return super.shouldStartPlayback(parameters) && (
            parameters.rebuffering || !stalled ||
                Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed) >=
                Util.msToUs(DEFAULT_BUFFER_FOR_PLAYBACK_MS.toLong())
            )
    }
}

class VideoPlaybackInfrastructure(
    context: Context,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    val feedPositionHandoff = FeedVideoPositionHandoff()
    private val appContext = context.applicationContext
    private val feedPlayers = lazy { FeedPreviewPlayerPool(this) }
    val feedPreviewPlayerPool: FeedPreviewPlayerPool
        get() = feedPlayers.value
    private val viewerPlayers = lazy { ViewerVideoPlayerPool(this) }
    val viewerVideoPlayerPool: ViewerVideoPlayerPool
        get() = viewerPlayers.value
    internal val hasActiveViewerPlayer: Boolean
        get() = viewerPlayers.isInitialized() && viewerPlayers.value.activePlayerCount() > 0
    private val videoClient = videoHttpClient(httpClient)
    private val sharedFactories = SharedVideoFactories(
        http = AbandoningHttpDataSourceFactory(videoClient),
        local = DefaultDataSource.Factory(appContext),
        cache = SimpleCache(
            File(appContext.cacheDir, VIDEO_PLAYBACK_CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(VIDEO_PLAYBACK_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(appContext),
        ),
    )

    internal fun onViewerStreamAcquired() {
        if (feedPlayers.isInitialized()) feedPlayers.value.stopForViewer()
    }

    fun bind(
        request: VideoPlaybackRequest,
        boundedLocks: Boolean = false,
    ): BoundVideoPlaybackRequest {
        val cached = request.usesSharedCache()
        val upstream = requestScopedHttp(request.headers)
        val dataSourceFactory = if (cached) {
            videoCacheDataSourceFactory(sharedFactories.cache, upstream, lockChunkBytes = VIDEO_CACHE_CHUNK_BYTES.takeIf { boundedLocks })
        } else {
            DefaultDataSource.Factory(appContext, upstream)
        }
        val separateAudio = request.audioTracks.filter { it.url.isNotBlank() }
        val mediaItem = MediaItem.Builder()
            .setUri(videoLocationToUri(request.location))
            .setMimeType(streamMimeType(request.mime))
            .setCustomCacheKey(if (cached) videoCacheKey(request.location, request.headers) else null)
            .setTag(separateAudio.takeIf { it.isNotEmpty() }?.let { tracks -> SeparateAudioLabels(tracks.map { it.label }) })
            .build()
        return BoundVideoPlaybackRequest(
            request = request,
            mediaSourceFactory = StreamMediaSourceFactory(dataSourceFactory, separateAudio, request.subtitles.filter { it.url.isNotBlank() }),
            mediaItem = mediaItem,
        )
    }

    internal fun playerBuilder(profile: VideoPlaybackProfile): ExoPlayer.Builder {
        val renderers = SideLoadedSubtitleRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setEnableAudioTrackPlaybackParams(true)
        return ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(VideoLoadControlFactory.create(profile))
            .setUsePlatformDiagnostics(profile.usesPlatformDiagnostics)
    }

    suspend fun prefetch(
        request: VideoPlaybackRequest,
        maxBytes: Long = VIEWER_VIDEO_PREFETCH_BYTES,
    ): ViewerPrefetchResult {
        if (!request.usesSharedCache()) {
            return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
        }
        val (location, headers) = request
        return withContext(Dispatchers.IO) {
            val cacheKey = videoCacheKey(location, headers)
            val dataSpec = DataSpec.Builder()
                .setUri(videoLocationToUri(location))
                .setKey(cacheKey)
                .setLength(maxBytes)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build()
            val dataSource = cacheDataSourceFactory(
                headers, blockOnCache = true, lockChunkBytes = VIDEO_CACHE_CHUNK_BYTES,
            ).createDataSource()
            val writer = CacheWriter(dataSource, dataSpec, null, null)
            writer.cacheCancellable()
            currentCoroutineContext().ensureActive()
            val cachedBytes = sharedFactories.cache.getCachedBytes(
                cacheKey,
                0L,
                maxBytes,
            )
            if (cachedBytes > 0L) {
                ViewerPrefetchResult(
                    outcome = ViewerPrefetchOutcome.WARMED,
                    bytesCached = cachedBytes,
                )
            } else {
                ViewerPrefetchResult(ViewerPrefetchOutcome.FAILED)
            }
        }
    }

    suspend fun copyFullyCached(
        location: String,
        headers: Map<String, String>,
        destination: File,
    ): Boolean {
        if (!isHttpVideoLocation(location)) return false
        return withContext(Dispatchers.IO) {
            copyFullyCachedVideo(sharedFactories.cache, videoCacheKey(location, headers), destination)
        }
    }

    suspend fun sampleFrames(
        location: String,
        headers: Map<String, String>,
        count: Int,
        sizePx: Int,
        budget: NetworkBudget,
    ): FrameSample = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        val uri = videoLocationToUri(location)
        if (!isHttpVideoLocation(location)) {
            return@withContext sampleVideoFrames(sharedFactories.local.createDataSource(), uri, key = null, count, sizePx, job)
        }
        val immutableHeaders = headers.toMap()
        val http = AbandoningHttpDataSourceFactory(videoClient.newBuilder().addNetworkInterceptor(budget.interceptor).build())
        val upstream = ResolvingDataSource.Factory(http) { it.withAdditionalHeaders(immutableHeaders) }
        val source = CacheDataSource.Factory()
            .setCache(sharedFactories.cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        sampleVideoFrames(source, uri, videoCacheKey(location, headers), count, sizePx, job)
    }

    internal suspend fun clearDisposableCache() = withContext(Dispatchers.IO) {
        sharedFactories.cache.keys.toList().forEach(sharedFactories.cache::removeResource)
    }

    suspend fun cachedBytes(location: String, headers: Map<String, String>): Long = withContext(Dispatchers.IO) {
        sharedFactories.cache.getCachedBytes(videoCacheKey(location, headers), 0L, Long.MAX_VALUE)
    }

    suspend fun removeCached(location: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        sharedFactories.cache.removeResource(videoCacheKey(location, headers))
    }

    internal suspend fun disposableCacheBytes(): Long = withContext(Dispatchers.IO) {
        sharedFactories.cache.cacheSpace
    }

    private fun cacheDataSourceFactory(
        headers: Map<String, String>,
        blockOnCache: Boolean = false,
        lockChunkBytes: Long? = null,
    ): CacheDataSource.Factory =
        videoCacheDataSourceFactory(sharedFactories.cache, requestScopedHttp(headers), blockOnCache, lockChunkBytes)

    private fun requestScopedHttp(headers: Map<String, String>): DataSource.Factory {
        val immutableHeaders = headers.toMap()
        return ResolvingDataSource.Factory(sharedFactories.http) { dataSpec -> dataSpec.withAdditionalHeaders(immutableHeaders) }
    }
}

suspend fun copyFullyCachedVideo(cache: Cache, cacheKey: String, destination: File): Boolean {
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
    if (length <= 0L || !cache.isCached(cacheKey, 0L, length)) return false
    val cacheOnly = CacheDataSource(cache, null)
    return runCatchingPreservingCancellation {
        cacheOnly.open(DataSpec.Builder().setUri(cacheKey).setKey(cacheKey).build())
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = cacheOnly.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, read)
            }
        }
        destination.length() == length
    }.also { cacheOnly.close() }.getOrDefault(false)
}

fun videoCacheDataSourceFactory(
    cache: Cache,
    upstream: DataSource.Factory,
    blockOnCache: Boolean = false,
    lockChunkBytes: Long? = null,
): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(lockChunkBytes?.let { BoundedLockCache(cache, it) } ?: cache)
    .setUpstreamDataSourceFactory(RangeRecordingDataSource.Factory(upstream, cache))
    .setFlags(
        CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
            (if (blockOnCache) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0),
    )
    .apply { if (NetworkUsage.enabled) setEventListener(NetworkUsage.videoCacheListener) }

private suspend fun CacheWriter.cacheCancellable() {
    suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            cache()
            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }
}

data class BoundVideoPlaybackRequest(
    val request: VideoPlaybackRequest,
    val mediaSourceFactory: MediaSource.Factory,
    val mediaItem: MediaItem,
)

interface VideoPlaybackInfrastructureOwner {
    val videoPlaybackInfrastructure: VideoPlaybackInfrastructure
}

fun Context.videoPlaybackInfrastructure(): VideoPlaybackInfrastructure {
    val owner = applicationContext as? VideoPlaybackInfrastructureOwner
        ?: error("Application must own VideoPlaybackInfrastructure")
    return owner.videoPlaybackInfrastructure
}

internal fun videoCacheKey(
    location: String,
    headers: Map<String, String>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(location)
    headers.entries
        .sortedWith(compareBy<Map.Entry<String, String>> { entry -> entry.key.lowercase() }
            .thenBy { entry -> entry.key })
        .forEach { (name, value) ->
            digest.updateLengthPrefixed(name.lowercase())
            digest.updateLengthPrefixed(value)
        }
    return "theoria-video-v1-${digest.digest().toHexString()}"
}

private data class SharedVideoFactories(
    val http: DataSource.Factory,
    val local: DataSource.Factory,
    val cache: SimpleCache,
)

private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val VIDEO_PLAYBACK_CACHE_DIRECTORY = "theoria_codex/media3/previews"
