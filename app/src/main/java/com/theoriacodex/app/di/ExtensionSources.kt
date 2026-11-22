package com.theoriacodex.app.di

import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Series area's dependencies; a container without an extension host has none. */
class SeriesDependencies(
    val repository: SeriesRepository,
    /** Every source that is on; loads their extensions on first use. */
    val sources: suspend () -> List<SeriesSource>,
    val extensions: SeriesExtensions,
) {
    private val checking = Mutex()

    /**
     * Stores the followed series' new chapters and returns the series that gained some, each also handed to [onUpdate]
     * as soon as it is stored. The Series tab's refresh and the background job both call this; one check runs at a
     * time, so neither repeats the other's requests midway.
     */
    suspend fun checkFollowed(onUpdate: suspend (SeriesUpdate) -> Unit = {}): List<SeriesUpdate> =
        checking.withLock { repository.checkFollowed(sources, onUpdate) }
}
