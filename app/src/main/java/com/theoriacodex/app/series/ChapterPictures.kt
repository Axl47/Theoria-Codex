package com.theoriacodex.app.series

import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.isSamePicture
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which page pictures each series' chapters on the Viewer's strip. Two chapters that open on the same page, as a
 * group's credit page does, would look alike, so once any two of a series' chapters do, each is pictured by its second
 * page. The series keeps that ([StoredSeries.chaptersShareFirstPage]), so a Viewer opened later, even in a new
 * process, pictures them so from its first frame.
 */
class ChapterPictures(
    private val repository: SeriesRepository,
    /**
     * A page's picture hash from its source, or null when it cannot be loaded or is too flat to tell apart. The app's
     * reads only files and the disk cache, so comparing never adds a request to the source.
     */
    private val hashPage: suspend (source: SourceKey, page: ImageRef) -> Long? = { _, _ -> null },
) {
    /** The first pages hashed so far, by series and chapter address; the app's lifetime only. */
    private val hashes = HashMap<SeriesId, MutableMap<String, Long>>()
    private val hashing = Mutex()

    /** The index of the page that pictures [id]'s chapters, from now on. */
    fun leadPage(id: SeriesId): Flow<Int> = repository.observeSeries(id).map { it?.chapterLeadPage() ?: 0 }.distinctUntilChanged()

    /**
     * Compares the [firstPages] of [id]'s chapters, by address, with those compared before, until two match. A page
     * that does not hash is tried again the next time its chapter is offered. Nothing is compared for a series that
     * is not stored or already shares its first pages.
     */
    suspend fun compare(id: SeriesId, firstPages: Map<String, ImageRef>) = hashing.withLock {
        val known = hashes.getOrPut(id, ::mutableMapOf)
        val fresh = firstPages.filterKeys { it !in known }
        if (fresh.isEmpty() || repository.series(id)?.chaptersShareFirstPage != false) return@withLock
        for ((address, page) in fresh) {
            val hash = hashPage(id.source, page) ?: continue
            if (known.values.any { isSamePicture(it, hash) }) {
                repository.setChaptersShareFirstPage(id)
                return@withLock
            }
            known[address] = hash
        }
    }
}

/** The index of the page that pictures this series' chapters on the Viewer's strip: the second once they share a first. */
internal fun StoredSeries.chapterLeadPage() = if (chaptersShareFirstPage) 1 else 0
