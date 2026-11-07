package com.theoriacodex.app.statistics

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import com.theoriacodex.app.media.copyCachedMediaBytes
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.app.media.MediaRequestFactory
import com.theoriacodex.data.repository.CodexStatistics
import com.theoriacodex.data.repository.LifetimeStatistics
import com.theoriacodex.data.repository.StatisticsRepository
import com.theoriacodex.data.repository.StatisticsThumbnailStore
import com.theoriacodex.data.repository.statisticsPostPromotions
import com.theoriacodex.data.repository.StatisticsPostPromotion
import com.theoriacodex.domain.model.Post
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Application-owned thumbnail work; observing Stats only reads the resulting local paths. */
internal class ThumbnailStatisticsRepository(
    private val delegate: StatisticsRepository,
    scope: CoroutineScope,
    private val store: StatisticsThumbnailStore,
    private val fetch: suspend (Post, File) -> Boolean,
) : StatisticsRepository by delegate {
    private val events = Channel<ThumbnailEvent>(Channel.UNLIMITED)
    private val thumbnails = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        scope.launch { delegate.observeStatistics().collect { events.send(ThumbnailEvent.Snapshot(it)) } }
        scope.launch {
            var codexStatistics: Map<String, CodexStatistics>? = null
            var promotions = emptySet<StatisticsPostPromotion>()
            var promoted = emptySet<String>()
            var reconciled = false
            // Only metadata for recently available posts is needed; no provider lookup is ever performed.
            val available = linkedMapOf<String, Post>()
            for (event in events) {
                val retry = when (event) {
                    is ThumbnailEvent.Snapshot -> {
                        // Most snapshots change only counters outside Codex post rankings.
                        if (reconciled && event.statistics.codexStatistics == codexStatistics) continue
                        codexStatistics = event.statistics.codexStatistics
                        val next = statisticsPostPromotions(event.statistics)
                        val nextKeys = next.mapTo(linkedSetOf()) { it.postKey }
                        val retry = (next - promotions).mapTo(linkedSetOf()) { it.postKey }
                        val unchanged = reconciled && nextKeys == promoted && retry.isEmpty()
                        promotions = next
                        promoted = nextKeys
                        if (unchanged) continue
                        retry
                    }
                    is ThumbnailEvent.Available -> {
                        val key = statisticsPostKey(event.post.id)
                        available.remove(key)
                        available[key] = event.post
                        while (available.size > 128) available.remove(available.keys.first())
                        setOf(key).intersect(promoted)
                    }
                }
                // A post outside the top lists needs no disk work, and promotions may not be known yet:
                // reconciling before the first snapshot would delete every retained thumbnail.
                if (event is ThumbnailEvent.Available && retry.isEmpty()) continue
                runCatchingPreservingCancellation {
                    thumbnails.value = store.reconcile(promoted, retry.mapNotNull { key ->
                        available[key]?.let { post -> key to suspend { file: File ->
                            withTimeoutOrNull(15_000L) { fetch(post, file) } ?: false
                        } }
                    }.toMap())
                    reconciled = true
                }
            }
        }
    }

    override fun observeStatistics() = combine(delegate.observeStatistics(), thumbnails) { statistics, paths ->
        statistics.copy(thumbnails = paths)
    }

    override suspend fun recordPostAvailable(post: Post) {
        events.send(ThumbnailEvent.Available(post))
    }
}

private sealed interface ThumbnailEvent {
    data class Snapshot(val statistics: LifetimeStatistics) : ThumbnailEvent
    data class Available(val post: Post) : ThumbnailEvent
}

/** Coil uses its existing memory/disk cache before networking and supplies the usual source headers. */
internal suspend fun fetchStatisticsThumbnail(context: Context, post: Post, destination: File): Boolean {
    val local = post.preview.localPath?.let(::File)?.takeIf(File::isFile)
    if (local != null) {
        local.copyTo(destination, overwrite = true)
        return true
    }
    val url = post.preview.url ?: return false
    if (copyCachedMediaBytes(context, post.id.source, url, destination)) return true
    val request = MediaRequestFactory.imageRequest(
        context, url, post.id.source, crossfade = false, staticAnimatedWebPFrame = true,
        targetWidthPx = 320, targetHeightPx = 320,
    )
    val result = context.imageLoader.execute(request) as? SuccessResult ?: return false
    return destination.outputStream().use { output ->
        result.drawable.toBitmap().compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
    }
}
