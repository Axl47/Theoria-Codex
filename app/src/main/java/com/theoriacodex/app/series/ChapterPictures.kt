package com.theoriacodex.app.series

import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.StoredSeries
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.isSamePicture
import com.theoriacodex.domain.series.SeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChapterPictures(
    private val repository: SeriesRepository,
    private val hashPage: suspend (source: SourceKey, page: ImageRef) -> Long? = { _, _ -> null },
) {
    private val hashes = HashMap<SeriesId, MutableMap<String, Long>>()
    private val hashing = Mutex()

    fun leadPage(id: SeriesId): Flow<Int> = repository.observeSeries(id).map { it?.chapterLeadPage() ?: 0 }.distinctUntilChanged()

    suspend fun compare(id: SeriesId, firstPages: Map<String, ImageRef>): Unit = hashing.withLock {
        runCatchingPreservingCancellation {
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
        }.onFailure { Diagnostics.report("series", "Chapter pictures could not be compared", it) }
    }
}

internal fun StoredSeries.chapterLeadPage() = if (chaptersShareFirstPage) 1 else 0
