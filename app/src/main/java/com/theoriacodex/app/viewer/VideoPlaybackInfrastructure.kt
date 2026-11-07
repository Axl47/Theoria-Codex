@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.theoriacodex.app.TheoriaApplication
import com.theoriacodex.app.viewer.state.ViewerPrefetchOutcome
import com.theoriacodex.app.viewer.state.ViewerPrefetchResult
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val VIDEO_PLAYBACK_CACHE_MAX_BYTES = 256L * 1024L * 1024L

/**
 * A neighbour video warms only its opening seconds, on every network: enough for a swipe to start from the
 * cache, while a neighbour that is never shown costs a few megabytes instead of most of its file.
 */
internal const val VIEWER_VIDEO_PREFETCH_BYTES = 4L * 1024L * 1024L
internal const val FEED_PREVIEW_TARGET_BUFFER_BYTES = 2 * 1024 * 1024
internal const val FEED_PREVIEW_MIN_BUFFER_MS = 6_000
internal const val FEED_PREVIEW_MAX_BUFFER_MS = 12_000
internal const val FEED_PREVIEW_PLAYBACK_BUFFER_MS = 750
internal const val FEED_PREVIEW_REBUFFER_MS = 1_500

internal enum class VideoPlaybackProfile {
    FEED_PREVIEW,
    VIEWER,
}

internal data class VideoPlaybackIdentity(
    val location: String,
    val cacheKey: String,
)

/**
 * Binds request-specific identity to one shared process resource without retaining request headers
 * in the shared resource. The generic shape keeps the identity/isolation contract JVM-testable.
 */
internal class SharedVideoResourcePool<T>(
    private val sharedResource: T,
) {
    fun bind(
        location: String,
        headers: Map<String, String>,
    ): BoundVideoResource<T> {
        return BoundVideoResource(
            sharedResource = sharedResource,
            identity = videoPlaybackIdentity(location, headers),
        )
    }
}

internal data class BoundVideoResource<T>(
    val sharedResource: T,
    val identity: VideoPlaybackIdentity,
)

/** Immutable shared policy; each player receives a fresh state-owning LoadControl instance. */
internal object VideoLoadControlFactory {
    fun create(profile: VideoPlaybackProfile): LoadControl {
        return when (profile) {
            VideoPlaybackProfile.FEED_PREVIEW -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ FEED_PREVIEW_MIN_BUFFER_MS,
                    /* maxBufferMs = */ FEED_PREVIEW_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ FEED_PREVIEW_PLAYBACK_BUFFER_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ FEED_PREVIEW_REBUFFER_MS,
                )
                .setTargetBufferBytes(FEED_PREVIEW_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(false)
                .setBackBuffer(0, false)
                .build()

            VideoPlaybackProfile.VIEWER -> DefaultLoadControl.Builder().build()
        }
    }
}

/** Process-owned Media3 infrastructure shared by every feed and Viewer player. */
internal class VideoPlaybackInfrastructure(
    context: Context,
) {
    internal val feedPositionHandoff = FeedVideoPositionHandoff()
    private val appContext = context.applicationContext
    private val feedPlayers = lazy { FeedPreviewPlayerPool(this) }
    internal val feedPreviewPlayerPool: FeedPreviewPlayerPool
        get() = feedPlayers.value
    private val viewerPlayers = lazy { ViewerVideoPlayerPool(this) }
    internal val viewerVideoPlayerPool: ViewerVideoPlayerPool
        get() = viewerPlayers.value
    internal val hasActiveViewerPlayer: Boolean
        get() = viewerPlayers.isInitialized() && viewerPlayers.value.activePlayerCount() > 0
    private val sharedFactories = SharedVideoFactories(
        http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(24_000)
            .setUserAgent("Mozilla/5.0"),
        local = DefaultDataSource.Factory(appContext),
        cache = SimpleCache(
            File(appContext.cacheDir, VIDEO_PLAYBACK_CACHE_DIRECTORY),
            LeastRecentlyUsedCacheEvictor(VIDEO_PLAYBACK_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(appContext),
        ),
    )
    private val resourcePool = SharedVideoResourcePool(sharedFactories)
    private val localMediaSourceFactory = DefaultMediaSourceFactory(sharedFactories.local)

    /** A Viewer stream is starting: the feed previews hidden behind it stop loading. */
    internal fun onViewerStreamAcquired() {
        if (feedPlayers.isInitialized()) feedPlayers.value.stopForViewer()
    }

    /**
     * Binds a player's media to [location]. [boundedLocks] is for players that keep loading while another reader
     * may want the same stream (a feed card behind the Viewer): each of their requests locks at most a chunk of it.
     */
    internal fun bind(
        location: String,
        headers: Map<String, String>,
        boundedLocks: Boolean = false,
    ): BoundVideoPlaybackRequest {
        val bound = resourcePool.bind(location, headers)
        val mediaSourceFactory = if (isHttpVideoLocation(location)) {
            DefaultMediaSourceFactory(
                cacheDataSourceFactory(bound, headers, lockChunkBytes = VIDEO_CACHE_CHUNK_BYTES.takeIf { boundedLocks }),
            )
        } else {
            localMediaSourceFactory
        }
        val mediaItemBuilder = MediaItem.Builder().setUri(videoLocationToUri(location))
        if (isHttpVideoLocation(location)) {
            mediaItemBuilder.setCustomCacheKey(bound.identity.cacheKey)
        }
        return BoundVideoPlaybackRequest(
            identity = bound.identity,
            mediaSourceFactory = mediaSourceFactory,
            mediaItem = mediaItemBuilder.build(),
        )
    }

    /** A supported hardware codec can still run out of resources during simultaneous autoplay. */
    internal fun playerBuilder(profile: VideoPlaybackProfile): ExoPlayer.Builder {
        val renderers = DefaultRenderersFactory(appContext).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(VideoLoadControlFactory.create(profile))
    }

    /** Caches at most [maxBytes] from the start of [location] into the shared video cache. */
    internal suspend fun prefetch(
        location: String,
        headers: Map<String, String>,
        maxBytes: Long = VIEWER_VIDEO_PREFETCH_BYTES,
    ): ViewerPrefetchResult {
        if (!isHttpVideoLocation(location)) {
            return ViewerPrefetchResult(ViewerPrefetchOutcome.SKIPPED)
        }
        return withContext(Dispatchers.IO) {
            val bound = resourcePool.bind(location, headers)
            val cacheKey = bound.identity.cacheKey
            val dataSpec = DataSpec.Builder()
                .setUri(videoLocationToUri(location))
                .setKey(cacheKey)
                .setLength(maxBytes)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build()
            // The swiped-to player reads what a still-running prefetch has committed, a chunk at a time.
            val dataSource = cacheDataSourceFactory(
                bound, headers, blockOnCache = true, lockChunkBytes = VIDEO_CACHE_CHUNK_BYTES,
            ).createDataSource()
            val writer = CacheWriter(dataSource, dataSpec, null, null)
            writer.cacheCancellable()
            currentCoroutineContext().ensureActive()
            val cachedBytes = bound.sharedResource.cache.getCachedBytes(
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

    /** Copies [location]'s video to [destination] when the shared cache already holds all of it; never networks. */
    internal suspend fun copyFullyCached(
        location: String,
        headers: Map<String, String>,
        destination: File,
    ): Boolean {
        if (!isHttpVideoLocation(location)) return false
        return withContext(Dispatchers.IO) {
            copyFullyCachedVideo(sharedFactories.cache, videoPlaybackIdentity(location, headers).cacheKey, destination)
        }
    }

    /** Remove entries through Media3's owner; never delete its open database or cache directory. */
    internal suspend fun clearDisposableCache() = withContext(Dispatchers.IO) {
        sharedFactories.cache.keys.toList().forEach(sharedFactories.cache::removeResource)
    }

    /** Bytes of one stream held in the shared cache, from its start. */
    internal suspend fun cachedBytes(location: String, headers: Map<String, String>): Long = withContext(Dispatchers.IO) {
        sharedFactories.cache.getCachedBytes(videoPlaybackIdentity(location, headers).cacheKey, 0L, Long.MAX_VALUE)
    }

    /** Drops one stream's cached bytes, leaving every other entry in place. */
    internal suspend fun removeCached(location: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        sharedFactories.cache.removeResource(videoPlaybackIdentity(location, headers).cacheKey)
    }

    internal suspend fun disposableCacheBytes(): Long = withContext(Dispatchers.IO) {
        sharedFactories.cache.cacheSpace
    }

    private fun cacheDataSourceFactory(
        bound: BoundVideoResource<SharedVideoFactories>,
        headers: Map<String, String>,
        blockOnCache: Boolean = false,
        lockChunkBytes: Long? = null,
    ): CacheDataSource.Factory {
        val immutableHeaders = headers.toMap()
        val requestScopedUpstream = ResolvingDataSource.Factory(bound.sharedResource.http) { dataSpec ->
            dataSpec.withAdditionalHeaders(immutableHeaders)
        }
        return videoCacheDataSourceFactory(bound.sharedResource.cache, requestScopedUpstream, blockOnCache, lockChunkBytes)
    }
}

/**
 * Copies the entry at [cacheKey] to [destination] when [cache] holds every byte of it, as after a video was
 * watched through. Reads the cache only: a gap or a range another writer holds returns false, never a download.
 */
internal suspend fun copyFullyCachedVideo(cache: Cache, cacheKey: String, destination: File): Boolean {
    val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
    if (length <= 0L || !cache.isCached(cacheKey, 0L, length)) return false
    // Without an upstream, any byte missing from the cache fails the read instead of downloading it.
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

/**
 * Playback bypasses occupied cache ranges; only background writers may wait for another writer. With
 * [lockChunkBytes], each request locks at most that much of a stream ([BoundedLockCache]), so another reader of it
 * can read what was committed and cache past the lock; without it, a request locks to the next cached span or the
 * end of the file, which keeps the reader that matters on one request.
 */
internal fun videoCacheDataSourceFactory(
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

internal data class BoundVideoPlaybackRequest(
    val identity: VideoPlaybackIdentity,
    val mediaSourceFactory: MediaSource.Factory,
    val mediaItem: MediaItem,
)

internal fun Context.videoPlaybackInfrastructure(): VideoPlaybackInfrastructure {
    val owner = applicationContext as? TheoriaApplication
        ?: error("Application must own VideoPlaybackInfrastructure")
    return owner.videoPlaybackInfrastructure
}

internal fun videoPlaybackIdentity(
    location: String,
    headers: Map<String, String>,
): VideoPlaybackIdentity {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(location)
    headers.entries
        .sortedWith(compareBy<Map.Entry<String, String>> { entry -> entry.key.lowercase() }
            .thenBy { entry -> entry.key })
        .forEach { (name, value) ->
            digest.updateLengthPrefixed(name.lowercase())
            digest.updateLengthPrefixed(value)
        }
    return VideoPlaybackIdentity(
        location = location,
        cacheKey = "theoria-video-v1-${digest.digest().toHexString()}",
    )
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

private fun isHttpVideoLocation(location: String): Boolean {
    return location.startsWith("http://", ignoreCase = true) ||
        location.startsWith("https://", ignoreCase = true)
}

private const val VIDEO_PLAYBACK_CACHE_DIRECTORY = "theoria_codex/media3/previews"
