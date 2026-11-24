package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoChaptersToMoveToException : Exception("The series switched to lists no chapters")

suspend fun SeriesRepository.switchSource(
    from: SeriesId,
    source: SeriesSource,
    to: Series,
    removeDownloads: suspend (SeriesId) -> Unit = {},
): Boolean {
    if (from == to.id) return false
    val moved = refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = source.details(to)
        val listed = fetchEntries(source, details)
        moveSeries(from, listed.memoOn(details), listed.entries)
    }
    if (!moved) return false
    try {
        removeDownloads(from)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
    }
    return true
}

fun StoredSeries.withSettingsOf(from: StoredSeries): StoredSeries = copy(
    followed = followed || from.followed,
    readingMode = from.readingMode ?: readingMode,
    downloadNewChapters = downloadNewChapters || from.downloadNewChapters,
    followedAtEpochMs = listOfNotNull(followedAtEpochMs.takeIf { followed }, from.followedAtEpochMs.takeIf { from.followed }).minOrNull(),
)

fun carryProgress(
    from: StoredSeries,
    fromEntries: List<StoredSeriesEntry>,
    to: StoredSeries,
    toEntries: List<StoredSeriesEntry>,
): Pair<StoredSeries, List<StoredSeriesEntry>> {
    val kind = to.series.id.source.seriesKind
    require(from.series.id.source.seriesKind == kind) { "Progress moves only between series of one kind" }
    val olds = fromEntries.map(StoredSeriesEntry::entry)
    val ordered = toEntries.inReadingOrder()
    val targets = ordered.map(StoredSeriesEntry::entry)
    val forward = EntryMatcher(olds, targets)
    val backward = EntryMatcher(targets, olds)
    val matched = fromEntries.groupBy { forward.match(it.entry)?.address }
    val byNumber = fromEntries.filter { it.entry.number != null }.groupBy { it.entry.number }
    val byAddress = fromEntries.associateBy { it.entry.address }
    val opened = byAddress[from.lastOpenedEntry]
    val resumed = opened?.let { forward.match(it.entry) }
    val manga = kind == SeriesKind.MANGA
    val boundary = opened?.entry?.number?.takeIf { manga }

    val carried = ordered.map { stored ->
        val incoming = matched[stored.entry.address].orEmpty()
        val own = incoming.filter { sameRelease(it.entry, stored.entry) }.ifEmpty { incoming }
        val number = stored.entry.number
        val chapter = own.ifEmpty {
            if (!manga) return@ifEmpty emptyList()
            number?.let(byNumber::get).orEmpty() + listOfNotNull(backward.sameName(stored.entry)?.let { byAddress[it.address] })
        }
        when {
            chapter.isNotEmpty() -> {
                val further = maxOf(stored.position, own.maxOfOrNull { it.position } ?: 0)
                val readAt = (own.mapNotNull { it.lastReadAtEpochMs } + listOfNotNull(stored.lastReadAtEpochMs)).maxOrNull()
                stored.copy(
                    read = stored.read || chapter.any { it.read },
                    position = stored.length?.let { further.coerceAtMost(it) } ?: further,
                    lastReadAtEpochMs = readAt,
                )
            }
            boundary != null && number != null && number < boundary -> stored.copy(read = true)
            else -> stored
        }
    }
    val own = carried.indexOfFirst { it.entry.address == to.lastOpenedEntry }
    val reached = if (resumed != null) carried.indexOfFirst { it.entry.address == resumed.address }
    else carried.indices.lastOrNull { carried[it].read && !ordered[it].read } ?: -1
    val lastOpened = if (reached > own) carried[reached].entry.address else to.lastOpenedEntry
    return to.withSettingsOf(from).copy(lastOpenedEntry = lastOpened) to carried.filterIndexed { index, entry -> entry != ordered[index] }
}

private fun sameRelease(a: SeriesEntry, b: SeriesEntry): Boolean =
    a.translatorGroup?.let(::matchKey).orEmpty() == b.translatorGroup?.let(::matchKey).orEmpty()

class EntryMatcher(own: List<SeriesEntry>, private val candidates: List<SeriesEntry>) {
    private val ownNames = own.groupingBy { matchKey(it.name) }.eachCount()
    private val candidatesByName = candidates.groupBy { matchKey(it.name) }
    private val candidatesByNumber = candidates.filter { it.number != null }.groupBy { it.number }

    fun match(entry: SeriesEntry): SeriesEntry? {
        val sameNumber = entry.number?.let(candidatesByNumber::get).orEmpty()
        return sameNumber.firstOrNull { sameRelease(entry, it) } ?: sameNumber.firstOrNull() ?: sameName(entry)
    }

    fun sameName(entry: SeriesEntry): SeriesEntry? {
        val key = matchKey(entry.name).ifEmpty { return null }
        if (ownNames[key] != 1) return null
        return candidatesByName[key]?.singleOrNull()?.takeIf { entry.number == null || it.number == null }
    }
}

fun matchKey(text: String): String = text.lowercase().let { lower ->
    lower.filterIndexed { index, char ->
        char.isLetterOrDigit() || char == '.' && lower.getOrNull(index - 1)?.isDigit() == true && lower.getOrNull(index + 1)?.isDigit() == true
    }
}
