package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** A followed series that gained [newEntries], in its source's order (newest first). */
data class SeriesUpdate(val series: Series, val newEntries: List<SeriesEntry>)

/**
 * Stores what [source] says about [series] now: its details and its whole entry list. Returns the new entries, as
 * [SeriesRepository.store] does. Throws what the source throws.
 */
suspend fun SeriesRepository.refresh(source: SeriesSource, series: Series): List<SeriesEntry> {
    val details = source.details(series)
    return store(details, source.entries(details))
}

/**
 * Refreshes every followed series from its source in [sources] and returns the ones that gained entries. Sources are
 * checked side by side, but each source's series one at a time, because a source's requests share one rate limit. A
 * series whose source is missing or fails is skipped and the rest go on.
 *
 * [onUpdate] gets each series as soon as its new entries are stored, so a check stopped midway has already reported
 * everything it stored. Sources report side by side.
 */
suspend fun SeriesRepository.checkFollowed(
    sources: List<SeriesSource>,
    onUpdate: suspend (SeriesUpdate) -> Unit = {},
): List<SeriesUpdate> = coroutineScope {
    val followedBySource = observeFollowed().first().map(FollowedSeries::series).groupBy { it.id.source }
    sources.mapNotNull { source ->
        followedBySource[source.key]?.let { followed ->
            async { followed.mapNotNull { series -> update(source, series)?.also { onUpdate(it) } } }
        }
    }.awaitAll().flatten()
}

/** [series]' new entries after a refresh from [source]; null when it gained none or its source failed. */
private suspend fun SeriesRepository.update(source: SeriesSource, series: Series): SeriesUpdate? = try {
    refresh(source, series).takeIf { it.isNotEmpty() }?.let { SeriesUpdate(series, it) }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
