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
        val listed = fetchEntries(source, details)
        moveSeries(from, listed.memoOn(details), listed.entries)
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
    // Followed since the earlier of the two.
    followedAtEpochMs = listOfNotNull(followedAtEpochMs.takeIf { followed }, from.followedAtEpochMs.takeIf { from.followed }).minOrNull(),
)

/**
 * [to] and its listed [toEntries] after taking over the progress the user made on [from] and its [fromEntries], the
 * same work on another source. Returns the series, with [from]'s settings, and only the entries that changed.
 *
 * An entry of [to] that is a chapter [from] has, by number or, where either lacks one, by name, is read when [from]
 * read that chapter, and stays unread otherwise. Each entry of [from] passes its place and its read time to its
 * [matchingEntry], the further place and the later time winning, the place capped at a known page count or duration.
 *
 * An entry [from] has no chapter for is read when it is numbered below the entry [from] opened last, as a source that
 * skips chapters still reads past them; that is only for manga, since episodes are watched one by one and an episode
 * missing on [from] was never seen.
 *
 * Continue goes on from where the user is: the [matchingEntry] of the entry [from] opened last, else the furthest
 * entry this marks read.
 *
 * [to]'s own progress stays: read entries stay read, places never go back, and an entry opened last that is as far or
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
    val kind = to.series.id.source.seriesKind
    require(from.series.id.source.seriesKind == kind) { "Progress moves only between series of one kind" }
    val olds = fromEntries.map(StoredSeriesEntry::entry)
    val ordered = toEntries.inReadingOrder()
    val targets = ordered.map(StoredSeriesEntry::entry)
    val matched = fromEntries.groupBy { matchingEntry(it.entry, olds, targets)?.address }
    val byNumber = fromEntries.filter { it.entry.number != null }.groupBy { it.entry.number }
    val byAddress = fromEntries.associateBy { it.entry.address }
    val opened = byAddress[from.lastOpenedEntry]
    val resumed = opened?.let { matchingEntry(it.entry, olds, targets) }
    val boundary = opened?.entry?.number?.takeIf { kind == SeriesKind.MANGA }

    // [from]'s entries for the chapter [entry] is: those matched to it, every release of its number, the one of its name.
    fun sameChapter(entry: SeriesEntry): List<StoredSeriesEntry> = matched[entry.address].orEmpty() +
        entry.number?.let(byNumber::get).orEmpty() + listOfNotNull(sameName(entry, targets, olds)?.let { byAddress[it.address] })

    val carried = ordered.map { stored ->
        val chapter = sameChapter(stored.entry)
        val number = stored.entry.number
        when {
            chapter.isNotEmpty() -> {
                val further = matched[stored.entry.address]?.maxOf { it.position } ?: 0
                val position = stored.length?.let { further.coerceAtMost(it) } ?: further
                stored.copy(read = stored.read || chapter.any { it.read }, position = maxOf(stored.position, position))
            }
            boundary != null && number != null && number < boundary -> stored.copy(read = true)
            else -> stored
        }
    }.withReadTimesOf(fromEntries, olds, targets)
    val own = carried.indexOfFirst { it.entry.address == to.lastOpenedEntry }
    val reached = if (resumed != null) carried.indexOfFirst { it.entry.address == resumed.address }
    else carried.indices.lastOrNull { carried[it].read && !ordered[it].read } ?: -1
    val lastOpened = if (reached > own) carried[reached].entry.address else to.lastOpenedEntry
    return to.withSettingsOf(from).copy(lastOpenedEntry = lastOpened) to carried.filterIndexed { index, entry -> entry != ordered[index] }
}

/** [this] with each read time in [fromEntries] on the entry it matches, where it is later than the entry's own. */
private fun List<StoredSeriesEntry>.withReadTimesOf(
    fromEntries: List<StoredSeriesEntry>,
    olds: List<SeriesEntry>,
    targets: List<SeriesEntry>,
): List<StoredSeriesEntry> {
    val readAt = HashMap<String, Long>()
    fromEntries.forEach { old ->
        val at = old.lastReadAtEpochMs ?: return@forEach
        matchingEntry(old.entry, olds, targets)?.let { readAt.merge(it.address, at, ::maxOf) }
    }
    return map { stored ->
        val at = readAt[stored.entry.address] ?: return@map stored
        if (stored.lastReadAtEpochMs != null && stored.lastReadAtEpochMs >= at) stored else stored.copy(lastReadAtEpochMs = at)
    }
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
