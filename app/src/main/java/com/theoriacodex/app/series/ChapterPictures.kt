package com.theoriacodex.app.series

import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.isSamePicture
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which page pictures each series' chapters on the Viewer's strip. Two chapters that open on the same page, as a
 * group's credit page does, would look alike, so once any two of a series' chapters do, each is pictured by its second
 * page. The app container keeps this, so it holds while the app runs; a new process finds it again from the first two
 * chapters it compares.
 */
class ChapterPictures(
    /**
     * A page's picture hash from its source, or null when it cannot be loaded or is too flat to tell apart. The app's
     * reads only files and the disk cache, so comparing never adds a request to the source.
     */
    private val hashPage: suspend (source: SourceKey, page: ImageRef) -> Long? = { _, _ -> null },
) {
    private val skipping = MutableStateFlow(emptySet<SeriesId>())
    private val hashes = HashMap<SeriesId, MutableMap<String, Long>>()
    private val hashing = Mutex()

    /** The index of the page that pictures [id]'s chapters, from now on: 0, or 1 once two chapters share their first. */
    fun leadPage(id: SeriesId): Flow<Int> = skipping.map { if (id in it) 1 else 0 }.distinctUntilChanged()

    /**
     * Compares the [firstPages] of [id]'s chapters, by address, with those compared before, until two match. A page
     * that does not hash is tried again the next time its chapter is offered.
     */
    suspend fun compare(id: SeriesId, firstPages: Map<String, ImageRef>) = hashing.withLock {
        val known = hashes.getOrPut(id, ::mutableMapOf)
        for ((address, page) in firstPages) {
            if (id in skipping.value) return@withLock
            if (address in known) continue
            val hash = hashPage(id.source, page) ?: continue
            if (known.values.any { isSamePicture(it, hash) }) skipping.update { it + id }
            known[address] = hash
        }
    }
}
