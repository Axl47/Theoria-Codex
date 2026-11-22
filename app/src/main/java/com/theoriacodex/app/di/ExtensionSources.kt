package com.theoriacodex.app.di

import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.coroutines.runCatchingPreservingCancellation
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
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
    /** What [anyExtension] said when the app last ran, so that startup does not wait for it. */
    extensionSeen: Boolean = false,
    private val rememberExtensionSeen: suspend (Boolean) -> Unit = {},
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private val checking = Mutex()
    private val mutableHasExtensions = MutableStateFlow(extensionSeen)

    /** Whether an extension was on the device at the last [findExtensions], or before that when the app last ran. */
    val hasExtensions: StateFlow<Boolean> = mutableHasExtensions.asStateFlow()

    /** [tab] before anything was looked up: what the last look for extensions found. */
    val tabAtStartup: Boolean get() = mutableHasExtensions.value

    /**
     * Whether the Series tab shows: with an extension on the device, or with a followed series, which stays reachable
     * when its extension is gone (its page then says so).
     */
    val tab: Flow<Boolean> = combine(hasExtensions, repository.observeFollowed().map { it.isNotEmpty() }) { extension, followed ->
        extension || followed
    }.distinctUntilChanged()

    private val looking = Mutex()
    private val mutableLooked = MutableStateFlow(false)

    /** Whether the app has looked for extensions since it started; until then [hasExtensions] is only what it remembered. */
    val looked: StateFlow<Boolean> = mutableLooked.asStateFlow()

    /**
     * Looks again for extensions on the device, each time the app comes to the foreground; never on the way to the
     * first screen. Looks run one after another, so the latest is the one whose answer stays. Never throws: a look or
     * a remembered answer that fails is handed to [reportFailure] and the tab stays as it was.
     */
    suspend fun findExtensions() = looking.withLock {
        runCatchingPreservingCancellation {
            val found = withContext(Dispatchers.IO) { anyExtension() }
            val changed = mutableHasExtensions.getAndUpdate { found } != found
            mutableLooked.value = true
            if (changed) rememberExtensionSeen(found)
        }.onFailure {
            mutableLooked.value = true
            reportFailure(it)
        }
        Unit
    }

    /**
     * Stores the followed series' new chapters and returns the series that gained some, each also handed to [onUpdate]
     * as soon as it is stored. The Series tab's refresh and the background job both call this; one check runs at a
     * time, so neither repeats the other's requests midway.
     */
    suspend fun checkFollowed(onUpdate: suspend (SeriesUpdate) -> Unit = {}): List<SeriesUpdate> =
        checking.withLock { repository.checkFollowed(sources, onUpdate) }
}
