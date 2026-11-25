package com.theoriacodex.data.repository

import com.theoriacodex.domain.coroutines.runCatchingExceptionsPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntries
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SeriesUpdate(val series: Series, val newEntries: List<SeriesEntry>)

data class FollowedCheck(val updates: List<SeriesUpdate>, val checked: Int, val failed: Int, val notDue: Int = 0)

data class CheckProgress(val done: Int, val total: Int)

internal val refreshLocks = ConcurrentHashMap<SourceKey, Mutex>()

suspend fun SeriesRepository.refresh(
    source: SeriesSource,
    series: Series,
    onNew: suspend (List<SeriesEntry>) -> Unit = {},
): List<SeriesEntry> = refreshLocks.getOrPut(source.key, ::Mutex).withLock {
    val details = source.details(series)
    val listed = fetchEntries(source, details)
    withContext(NonCancellable) {
        store(listed.memoOn(details), listed.entries).also { if (it.isNotEmpty()) onNew(it) }
    }
}

internal suspend fun SeriesRepository.fetchEntries(source: SeriesSource, series: Series): SeriesEntries =
    source.entries(series, entries(series.id).map(StoredSeriesEntry::entry))

suspend fun SeriesRepository.checkFollowed(
    source: suspend (SourceKey) -> SeriesSource?,
    runEvery: Duration? = null,
    now: Long = System.currentTimeMillis(),
    onProgress: (CheckProgress) -> Unit = {},
    onUpdate: suspend (SeriesUpdate) -> Unit = {},
): FollowedCheck = coroutineScope {
    val followed = observeFollowed().first().map(FollowedSeries::series)
    val due = if (runEvery == null) {
        followed
    } else {
        followed.filter { isCheckDue(series(it.id)?.checkedAtEpochMs, runEvery, now) { entries(it.id) } }
    }
    val progress = CheckCounter(due.size, onProgress)
    val checks = due.groupBy { it.id.source }.values.map { sameSource ->
        async { sameSource.map { series -> check(source, series, onUpdate).also(progress::finished) } }
    }.awaitAll().flatten()
    FollowedCheck(
        updates = checks.filterIsInstance<Checked.Updated>().map(Checked.Updated::update),
        checked = checks.count { it != Checked.Skipped },
        failed = checks.count { it == Checked.Failed },
        notDue = followed.size - due.size,
    )
}

private class CheckCounter(total: Int, private val onProgress: (CheckProgress) -> Unit) {
    private var progress = CheckProgress(0, total)

    init {
        onProgress(progress)
    }

    @Synchronized
    fun finished(checked: Checked) {
        progress = if (checked == Checked.Skipped) progress.copy(total = progress.total - 1) else progress.copy(done = progress.done + 1)
        onProgress(progress)
    }
}

private sealed interface Checked {
    data class Updated(val update: SeriesUpdate) : Checked

    data object Unchanged : Checked

    data object Skipped : Checked

    data object Failed : Checked
}

private suspend fun SeriesRepository.check(
    source: suspend (SourceKey) -> SeriesSource?,
    series: Series,
    onUpdate: suspend (SeriesUpdate) -> Unit,
): Checked = runCatchingExceptionsPreservingCancellation {
    refreshLocks.getOrPut(series.id.source, ::Mutex).withLock {
        val found = source(series.id.source) ?: return@withLock Checked.Skipped
        val listed = fetchEntries(found, series)
        withContext(NonCancellable) {
            val added = storeEntries(series.id, listed.entries, listed.seriesMemo)
            if (added.isEmpty()) Checked.Unchanged else SeriesUpdate(series, added).also { onUpdate(it) }.let(Checked::Updated)
        }
    }
}.getOrElse { error ->
    Diagnostics.report("series", "Checking a followed series on ${series.id.source.id} failed", error)
    Checked.Failed
}
