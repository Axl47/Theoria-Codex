package com.theoriacodex.app.di

import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Series area's dependencies; a container without an extension host has none, and no Series tab. */
class SeriesDependencies(
    val repository: SeriesRepository,
    /** Every source that is on; loads their extensions on first use. */
    val sources: suspend () -> List<SeriesSource>,
    val extensions: SeriesExtensions,
    /** Whether any extension is on the device, on or off; may block, and runs none of their code. */
    private val anyExtension: () -> Boolean = { true },
) {
    private val checking = Mutex()
    private val mutableHasExtensions = MutableStateFlow(false)

    /** Whether an extension was on the device at the last [findExtensions]. The Series tab shows only then. */
    val hasExtensions: StateFlow<Boolean> = mutableHasExtensions.asStateFlow()

    /** Looks again for extensions on the device: at startup, and whenever the app returns to the foreground. */
    suspend fun findExtensions() {
        mutableHasExtensions.value = withContext(Dispatchers.IO) { anyExtension() }
    }

    /**
     * Stores the followed series' new chapters and returns the series that gained some, each also handed to [onUpdate]
     * as soon as it is stored. The Series tab's refresh and the background job both call this; one check runs at a
     * time, so neither repeats the other's requests midway.
     */
    suspend fun checkFollowed(onUpdate: suspend (SeriesUpdate) -> Unit = {}): List<SeriesUpdate> =
        checking.withLock { repository.checkFollowed(sources, onUpdate) }
}
