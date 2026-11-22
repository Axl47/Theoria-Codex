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
 * Stores what [source] says about [series] now: its details and its whole entry list. Returns the new entries, as
 * [SeriesRepository.store] does. Throws what the source throws.
 */
suspend fun SeriesRepository.refresh(source: SeriesSource, series: Series): List<SeriesEntry> =
    refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = source.details(series)
        store(details, source.entries(details))
    }

/**
 * Refreshes every followed series' entry list from its source and returns the ones that gained entries. Sources are
 * checked side by side, but each source's series one at a time, because a source's requests share one rate limit.
 * [sources] is asked again for each series, once its turn has come, so a source switched off or removed while the
 * check ran or waited is not used any further. A series whose source is missing or fails is skipped and the rest go
 * on. Only entry lists are fetched and stored: details cost a request of their own, and the ones the check started
 * with may be older than what a series' page stored meanwhile. The exception is a series stored without a cover,
 * whose details are fetched too.
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
            followed.mapNotNull { series -> update(sources, series)?.also { onUpdate(it) } }
        }
    }.awaitAll().flatten()
}

/** [series]' new entries, fetched from its source among [sources]; null when it gained none or its source is gone or failed. */
private suspend fun SeriesRepository.update(sources: suspend () -> List<SeriesSource>, series: Series): SeriesUpdate? = try {
    refreshLocks.getOrPut(series.id.source, ::Mutex).withLock {
        sources().firstOrNull { it.key == series.id.source }?.let { source -> storeEntries(series.id, source.entries(series)) }
    }?.takeIf { it.isNotEmpty() }?.let { SeriesUpdate(series, it) }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
