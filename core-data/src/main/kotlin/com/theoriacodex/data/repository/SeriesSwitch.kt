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
 * Each entry of [from] goes to the entry of [to] that [EntryMatcher.match] finds for it. An entry of [to] that entries
 * went to takes their progress: read when one of them was read, and the further place and the later read time, the
 * place capped at a known page count or duration. When one of them is its own release, by the same translator group
 * or none, only those count: another group's release that fell back to it says nothing about this one.
 *
 * The rest are filled in only for manga, since episodes are watched one by one and an episode not matched on [from]
 * was never seen. A chapter no entry went to, but that [from] has as another release of its number or by its name
 * ([EntryMatcher.sameName]), is read when [from] read that chapter. A chapter [from] does not have is read when it is
 * numbered below the entry [from] opened last, as a source that skips chapters still reads past them.
 *
 * Continue goes on from where the user is: the match of the entry [from] opened last, else the furthest entry this
 * marks read.
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

/** Whether [a] and [b] are one release of their chapter: by the same translator group, or both by none. */
private fun sameRelease(a: SeriesEntry, b: SeriesEntry): Boolean =
    a.translatorGroup?.let(::matchKey).orEmpty() == b.translatorGroup?.let(::matchKey).orEmpty()

/**
 * Finds, for an entry of one series ([own]), the entry that is the same chapter on another source ([candidates], in
 * reading order). A switch asks for every entry of both lists, so each list is indexed once.
 */
class EntryMatcher(own: List<SeriesEntry>, private val candidates: List<SeriesEntry>) {
    private val ownNames = own.groupingBy { matchKey(it.name) }.eachCount()
    private val candidatesByName = candidates.groupBy { matchKey(it.name) }
    private val candidatesByNumber = candidates.filter { it.number != null }.groupBy { it.number }

    /**
     * The candidate that is [entry]: the same chapter number, of the same release ([sameRelease]) when there is one,
     * else the first in reading order. Otherwise, by [sameName]. Null when nothing matches.
     */
    fun match(entry: SeriesEntry): SeriesEntry? {
        val sameNumber = entry.number?.let(candidatesByNumber::get).orEmpty()
        return sameNumber.firstOrNull { sameRelease(entry, it) } ?: sameNumber.firstOrNull() ?: sameName(entry)
    }

    /**
     * The candidate with [entry]'s name by [matchKey], where either of the two lacks a number: numbers, when both have
     * them, say more than names. Only a name that no other entry has, among own entries and among candidates, matches,
     * since "Extra" or "Notice" can name several.
     */
    fun sameName(entry: SeriesEntry): SeriesEntry? {
        val key = matchKey(entry.name).ifEmpty { return null }
        if (ownNames[key] != 1) return null
        return candidatesByName[key]?.singleOrNull()?.takeIf { entry.number == null || it.number == null }
    }
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
