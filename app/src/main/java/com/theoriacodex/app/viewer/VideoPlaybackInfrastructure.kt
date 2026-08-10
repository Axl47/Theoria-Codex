@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theoriacodex.app.viewer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.theoriacodex.app.TheoriaApplication
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal const val VIDEO_PLAYBACK_CACHE_MAX_BYTES = 64L * 1024L * 1024L
internal const val FEED_PREVIEW_TARGET_BUFFER_BYTES = 6 * 1024 * 1024
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
    private val appContext = context.applicationContext
    internal val feedPreviewPlayerPool: FeedPreviewPlayerPool by lazy {
        FeedPreviewPlayerPool(appContext, this)
    }
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

    internal fun bind(
        location: String,
        headers: Map<String, String>,
    ): BoundVideoPlaybackRequest {
        val bound = resourcePool.bind(location, headers)
        val mediaSourceFactory = if (isHttpVideoLocation(location)) {
            val immutableHeaders = headers.toMap()
            val requestScopedUpstream = ResolvingDataSource.Factory(bound.sharedResource.http) { dataSpec ->
                dataSpec.withAdditionalHeaders(immutableHeaders)
            }
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(bound.sharedResource.cache)
                .setUpstreamDataSourceFactory(requestScopedUpstream)
                .setFlags(
                    CacheDataSource.FLAG_BLOCK_ON_CACHE or
                        CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
                )
            DefaultMediaSourceFactory(cacheDataSource)
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

    internal fun loadControl(profile: VideoPlaybackProfile): LoadControl {
        return VideoLoadControlFactory.create(profile)
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
