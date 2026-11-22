package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Moves the stored series [from] to [to], the same work on [source]: fetches [to]'s details and entries, then has the
 * repository [SeriesRepository.moveSeries] in one step. A failed fetch throws what the source throws and changes
 * nothing. Once the move is stored, [removeDownloads] gets [from] so its downloaded chapters can go; its failure leaves
 * the move as it is. Moving a series onto itself does nothing.
 */
suspend fun SeriesRepository.switchSource(
    from: SeriesId,
    source: SeriesSource,
    to: Series,
    removeDownloads: suspend (SeriesId) -> Unit = {},
) {
    if (from == to.id) return
    // The same lock as a refresh, so a check of [to] cannot store an older list over the one moved in.
    refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = source.details(to)
        moveSeries(from, details, source.entries(details))
    }
    try {
        removeDownloads(from)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The files stay behind; the move itself is done.
    }
}

/**
 * [this] with the per-series settings the user chose on [from], for a series replacing it. Each setting is one line;
 * one added to [StoredSeries] belongs here too.
 */
fun StoredSeries.withSettingsOf(from: StoredSeries): StoredSeries = copy(
    followed = followed || from.followed,
    readingMode = from.readingMode ?: readingMode,
)

/**
 * [to] and its listed [toEntries] after taking over the progress the user made on [from] and its [fromEntries], the
 * same work on another source. Returns the series, with [from]'s settings, and only the entries that changed.
 *
 * - Read: an entry is read when its number is at most the highest number read on [from]. Without a number on
 *   either side, an entry is read when a read entry of [from] has the same name, by [matchKey].
 * - Resume: the entry [from] opened last maps to its [matchingEntry], which takes its last page (capped at a known
 *   page count) and becomes the entry opened last, unless [to]'s own last opened entry is as far or further.
 *
 * Read entries stay read and pages never go back, so a [to] the user already read keeps any further progress.
 */
fun carryProgress(
    from: StoredSeries,
    fromEntries: List<StoredSeriesEntry>,
    to: StoredSeries,
    toEntries: List<StoredSeriesEntry>,
): Pair<StoredSeries, List<StoredSeriesEntry>> {
    val read = fromEntries.filter { it.read }
    val readUpTo = read.mapNotNull { it.entry.number }.maxOrNull()
    val readNames = read.mapNotNullTo(HashSet()) { matchKey(it.entry.name).ifEmpty { null } }
    fun wasRead(entry: SeriesEntry): Boolean {
        val number = entry.number
        return if (number != null && readUpTo != null) number <= readUpTo else matchKey(entry.name) in readNames
    }

    val ordered = toEntries.inReadingOrder().map { if (!it.read && wasRead(it.entry)) it.copy(read = true) else it }.toMutableList()
    val opened = fromEntries.firstOrNull { it.entry.address == from.lastOpenedEntry }
    val resumed = opened?.let { matchingEntry(it.entry, ordered.map(StoredSeriesEntry::entry)) }
        ?.let { match -> ordered.indexOfFirst { it.entry.address == match.address } } ?: -1
    var lastOpened = to.lastOpenedEntry
    if (opened != null && resumed >= 0) {
        val entry = ordered[resumed]
        val page = entry.pageCount?.let { opened.lastPage.coerceAtMost(it) } ?: opened.lastPage
        ordered[resumed] = entry.copy(lastPage = maxOf(entry.lastPage, page))
        if (resumed > ordered.indexOfFirst { it.entry.address == to.lastOpenedEntry }) lastOpened = entry.entry.address
    }
    val before = toEntries.toSet()
    return to.withSettingsOf(from).copy(lastOpenedEntry = lastOpened) to ordered.filterNot { it in before }
}

/**
 * The entry among [candidates] (in reading order) that is [entry] on another source: the same chapter number, by the
 * same translator group when one has it, else the first in reading order. Without a number on either side, or with no
 * candidate of that number, the first of the same name by [matchKey] among those without a number on either side. Null
 * when nothing matches.
 */
fun matchingEntry(entry: SeriesEntry, candidates: List<SeriesEntry>): SeriesEntry? {
    val sameNumber = entry.number?.let { number -> candidates.filter { it.number == number } }.orEmpty()
    val group = entry.translatorGroup?.let(::matchKey)?.ifEmpty { null }
    return sameNumber.firstOrNull { group != null && it.translatorGroup?.let(::matchKey) == group }
        ?: sameNumber.firstOrNull()
        ?: matchKey(entry.name).ifEmpty { null }?.let { name ->
            candidates.firstOrNull { (entry.number == null || it.number == null) && matchKey(it.name) == name }
        }
}

/** [text] reduced to what names the same thing across sources: its letters and digits, in lower case. */
fun matchKey(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)
