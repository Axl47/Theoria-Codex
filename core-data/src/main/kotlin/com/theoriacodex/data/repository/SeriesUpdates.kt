package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A followed series that gained [newEntries], in its source's order (newest first). */
data class SeriesUpdate(val series: Series, val newEntries: List<SeriesEntry>)

/**
 * One lock per source, held while a series' entry list is fetched and stored. Without it, two refreshes of one series
 * could store their lists in the other order than they were fetched in, and the older list would win.
 */
private val refreshLocks = ConcurrentHashMap<SourceKey, Mutex>()

/**
 * Stores what [source] says about [series] now: its whole entry list and, [withDetails], its details, which cost a
 * request of their own. Returns the new entries, as [SeriesRepository.store] does. Throws what the source throws.
 */
suspend fun SeriesRepository.refresh(source: SeriesSource, series: Series, withDetails: Boolean = true): List<SeriesEntry> =
    refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = if (withDetails) source.details(series) else series
        store(details, source.entries(details))
    }

/**
 * Refreshes every followed series' entry list from its source and returns the ones that gained entries. Sources are
 * checked side by side, but each source's series one at a time, because a source's requests share one rate limit.
 * [sources] is asked again before each series, so a source switched off or removed midway is not used any further. A
 * series whose source is missing or fails is skipped and the rest go on.
 *
 * [onUpdate] gets each series as soon as its new entries are stored, so a check stopped midway has already reported
 * everything it stored. Sources report side by side.
 */
suspend fun SeriesRepository.checkFollowed(
    sources: suspend () -> List<SeriesSource>,
    onUpdate: suspend (SeriesUpdate) -> Unit = {},
): List<SeriesUpdate> = coroutineScope {
    observeFollowed().first().map(FollowedSeries::series).groupBy { it.id.source }.values.map { followed ->
        async {
            followed.mapNotNull { series ->
                val source = sources().firstOrNull { it.key == series.id.source }
                source?.let { update(it, series) }?.also { onUpdate(it) }
            }
        }
    }.awaitAll().flatten()
}

/** [series]' new entries after a refresh from [source]; null when it gained none or its source failed. */
private suspend fun SeriesRepository.update(source: SeriesSource, series: Series): SeriesUpdate? = try {
    refresh(source, series, withDetails = false).takeIf { it.isNotEmpty() }?.let { SeriesUpdate(series, it) }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
