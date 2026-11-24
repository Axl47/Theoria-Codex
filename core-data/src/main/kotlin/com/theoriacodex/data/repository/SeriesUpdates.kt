package com.theoriacodex.data.repository

import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
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
 * What a check of the followed series found: the [updates], how many series it [checked] (those whose source was on),
 * and how many of those [failed] because their source threw.
 */
data class FollowedCheck(val updates: List<SeriesUpdate>, val checked: Int, val failed: Int)

/**
 * One lock per source, held while a series' entry list is fetched and stored. Without it, two refreshes of one series
 * could store their lists in the other order than they were fetched in, and the older list would win.
 */
internal val refreshLocks = ConcurrentHashMap<SourceKey, Mutex>()

/**
 * Stores what [source] says about [series] now: its details and its whole entry list. Returns the new entries, as
 * [SeriesRepository.store] does. Throws what the source throws.
 */
suspend fun SeriesRepository.refresh(source: SeriesSource, series: Series): List<SeriesEntry> =
    refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = source.details(series)
        val listed = fetchEntries(source, details)
        store(listed.memoOn(details), listed.entries)
    }

/** [series]' entries from [source], which is handed the list stored for it. */
internal suspend fun SeriesRepository.fetchEntries(source: SeriesSource, series: Series): SeriesEntries =
    source.entries(series, entries(series.id).map(StoredSeriesEntry::entry))

/**
 * Refreshes every followed series' entry list from its source and returns what it found. Sources are checked side by
 * side, but each source's series one at a time, because a source's requests share one rate limit. [source] is asked
 * for each series' source once its turn has come, so a source switched off or removed while the check ran or waited is
 * not used any further, and only the extensions of followed series load. A series whose source is missing is skipped;
 * one whose source fails is reported to [Diagnostics] and counted, and the rest go on. Only entry lists are fetched and
 * stored, with the series memo a source records while listing: details cost a request of their own, and the ones the
 * check started with may be older than what a series' page stored meanwhile.
 *
 * [onUpdate] gets each series as soon as its new entries are stored, so a check stopped midway has already reported
 * everything it stored. Sources report side by side.
 */
suspend fun SeriesRepository.checkFollowed(
    source: suspend (SourceKey) -> SeriesSource?,
    onUpdate: suspend (SeriesUpdate) -> Unit = {},
): FollowedCheck = coroutineScope {
    val checks = observeFollowed().first().map(FollowedSeries::series).groupBy { it.id.source }.values.map { followed ->
        async { followed.map { series -> check(source, series).also { if (it is Checked.Updated) onUpdate(it.update) } } }
    }.awaitAll().flatten()
    FollowedCheck(
        updates = checks.filterIsInstance<Checked.Updated>().map(Checked.Updated::update),
        checked = checks.count { it != Checked.Skipped },
        failed = checks.count { it == Checked.Failed },
    )
}

/** How checking one followed series went. */
private sealed interface Checked {
    data class Updated(val update: SeriesUpdate) : Checked

    data object Unchanged : Checked

    /** Its source is not on. */
    data object Skipped : Checked

    data object Failed : Checked
}

/** Checks [series] for new entries on the [source] its key names. */
private suspend fun SeriesRepository.check(source: suspend (SourceKey) -> SeriesSource?, series: Series): Checked = try {
    refreshLocks.getOrPut(series.id.source, ::Mutex).withLock {
        val found = source(series.id.source) ?: return@withLock Checked.Skipped
        val listed = fetchEntries(found, series)
        val added = storeEntries(series.id, listed.entries, listed.seriesMemo)
        if (added.isEmpty()) Checked.Unchanged else Checked.Updated(SeriesUpdate(series, added))
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Diagnostics.report("series", "Checking a followed series on ${series.id.source.id} failed", error)
    Checked.Failed
}
