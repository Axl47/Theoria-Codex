package com.theoriacodex.data.repository

import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The series switched to lists no chapters, so the progress made on the one switched from would be lost. */
class NoChaptersToMoveToException : Exception("The series switched to lists no chapters")

/**
 * Moves the stored series [from] to [to], the same work on [source]: fetches [to]'s details and entries, then has the
 * repository [SeriesRepository.moveSeries] in one step. Returns whether this call moved it: false when [to] is [from],
 * or when [from] is no longer stored, as when another switch moved it first.
 *
 * Changes nothing and throws when the fetch fails, with what the source throws, or when the move is refused, with
 * [NoChaptersToMoveToException]. Once a move is stored, [removeDownloads] gets [from] so its
 * downloaded chapters can go; its failure leaves the move as it is.
 */
suspend fun SeriesRepository.switchSource(
    from: SeriesId,
    source: SeriesSource,
    to: Series,
    removeDownloads: suspend (SeriesId) -> Unit = {},
): Boolean {
    if (from == to.id) return false
    // The same lock as a refresh, so a check of [to] cannot store an older list over the one moved in.
    val moved = refreshLocks.getOrPut(source.key, ::Mutex).withLock {
        val details = source.details(to)
        val entries = source.entries(details)
        moveSeries(from, details, entries)
    }
    if (!moved) return false
    try {
        removeDownloads(from)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The files stay behind; the move itself is done.
    }
    return true
}

/**
 * [this] with the per-series settings the user chose on [from], for a series replacing it. Each setting is one line;
 * one added to [StoredSeries] belongs here too. [StoredSeries.chaptersShareFirstPage] is not a setting but what the
 * Viewer found on [from]'s pages, so it stays behind: another source's chapters are compared afresh.
 */
fun StoredSeries.withSettingsOf(from: StoredSeries): StoredSeries = copy(
    followed = followed || from.followed,
    readingMode = from.readingMode ?: readingMode,
    downloadNewChapters = downloadNewChapters || from.downloadNewChapters,
    // Followed since the earlier of the two, and read last when either was.
    followedAtEpochMs = listOfNotNull(followedAtEpochMs.takeIf { followed }, from.followedAtEpochMs.takeIf { from.followed }).minOrNull(),
    lastReadAtEpochMs = listOfNotNull(lastReadAtEpochMs, from.lastReadAtEpochMs).maxOrNull(),
)

/**
 * [to] and its listed [toEntries] after taking over the progress the user made on [from] and its [fromEntries], the
 * same work on another source. Returns the series, with [from]'s settings, and only the entries that changed.
 *
 * The entry [from] opened last is where the user is. Its [matchingEntry] takes its read state and last page (capped at
 * a known page count) and becomes the entry opened last. Entries numbered below it are read; any other entry is read
 * only when [from] read that same chapter: one of its number, or, where either lacks a number, of its name. When the
 * entry opened last has no match, the furthest entry this marks read becomes the entry opened last, so Continue goes
 * on after it.
 *
 * [to]'s own progress stays: read entries stay read, pages never go back, and an entry opened last that is as far or
 * further in reading order is kept.
 *
 * Both are the same kind of series: a page number means nothing as a playback position, so anything else is refused.
 */
fun carryProgress(
    from: StoredSeries,
    fromEntries: List<StoredSeriesEntry>,
    to: StoredSeries,
    toEntries: List<StoredSeriesEntry>,
): Pair<StoredSeries, List<StoredSeriesEntry>> {
    require(from.series.id.source.seriesKind == to.series.id.source.seriesKind) { "Progress moves only between series of one kind" }
    val olds = fromEntries.map(StoredSeriesEntry::entry)
    val oldRead = fromEntries.filter { it.read }.mapTo(HashSet()) { it.entry.address }
    val readNumbers = fromEntries.filter { it.read }.mapNotNullTo(HashSet()) { it.entry.number }
    val ordered = toEntries.inReadingOrder()
    val targets = ordered.map(StoredSeriesEntry::entry)
    val opened = fromEntries.firstOrNull { it.entry.address == from.lastOpenedEntry }
    val resumed = opened?.let { matchingEntry(it.entry, olds, targets) }
    val boundary = opened?.entry?.number

    fun readThere(entry: SeriesEntry): Boolean = entry.number?.let { it in readNumbers } == true ||
        sameName(entry, targets, olds)?.address in oldRead

    val carried = ordered.map { stored ->
        val number = stored.entry.number
        when {
            opened != null && stored.entry.address == resumed?.address -> {
                val position = stored.length?.let { opened.position.coerceAtMost(it) } ?: opened.position
                stored.copy(read = stored.read || opened.read, position = maxOf(stored.position, position))
            }
            stored.read -> stored
            boundary != null && number != null && number < boundary -> stored.copy(read = true)
            readThere(stored.entry) -> stored.copy(read = true)
            else -> stored
        }
    }
    val own = carried.indexOfFirst { it.entry.address == to.lastOpenedEntry }
    val reached = if (resumed != null) carried.indexOfFirst { it.entry.address == resumed.address }
    else carried.indices.lastOrNull { carried[it].read && !ordered[it].read } ?: -1
    val lastOpened = if (reached > own) carried[reached].entry.address else to.lastOpenedEntry
    return to.withSettingsOf(from).copy(lastOpenedEntry = lastOpened) to carried.filterIndexed { index, entry -> entry != ordered[index] }
}

/**
 * The entry among [candidates] (in reading order) that is [entry], one of [own], on another source: the same chapter
 * number, by the same translator group when one has it, else the first in reading order. Otherwise, by [sameName].
 * Null when nothing matches.
 */
fun matchingEntry(entry: SeriesEntry, own: List<SeriesEntry>, candidates: List<SeriesEntry>): SeriesEntry? {
    val sameNumber = entry.number?.let { number -> candidates.filter { it.number == number } }.orEmpty()
    val group = entry.translatorGroup?.let(::matchKey)?.ifEmpty { null }
    return sameNumber.firstOrNull { group != null && it.translatorGroup?.let(::matchKey) == group }
        ?: sameNumber.firstOrNull()
        ?: sameName(entry, own, candidates)
}

/**
 * The entry among [candidates] with [entry]'s name by [matchKey], where either of the two lacks a number: numbers,
 * when both have them, say more than names. Only a name that no other entry has, among [own] and among [candidates],
 * matches, since "Extra" or "Notice" can name several.
 */
private fun sameName(entry: SeriesEntry, own: List<SeriesEntry>, candidates: List<SeriesEntry>): SeriesEntry? {
    val key = matchKey(entry.name).ifEmpty { return null }
    if (own.count { matchKey(it.name) == key } != 1) return null
    return candidates.filter { matchKey(it.name) == key }.singleOrNull()?.takeIf { entry.number == null || it.number == null }
}

/**
 * [text] reduced to what names the same thing across sources: its letters and digits in lower case, and a point
 * between two digits, so that chapter 10.5 is not chapter 105.
 */
fun matchKey(text: String): String = text.lowercase().let { lower ->
    lower.filterIndexed { index, char ->
        char.isLetterOrDigit() || char == '.' && lower.getOrNull(index - 1)?.isDigit() == true && lower.getOrNull(index + 1)?.isDigit() == true
    }
}
