package com.theoriacodex.app.di

import android.app.Application
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.domain.series.ExtensionChoiceStore
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor

/**
 * Hosts series sources backed by third-party extension code. Only debug builds contain an implementation, which the
 * application finds with [java.util.ServiceLoader]; every other build has no extension sources and none of this code.
 *
 * Nothing loads at startup, and nothing ever loads but the extensions the user turned on. Those load once, the first
 * time [sources] is called or [mediaInterceptor] is asked for an image only an extension can fetch.
 */
interface ExtensionSourceHost {
    /** Goes on the app's media clients: answers requests only an extension can fetch and passes the rest on. */
    val mediaInterceptor: Interceptor

    /** The extensions on the device and the user's choices about them, for the Extensions page. */
    val extensions: SeriesExtensions

    /** Names the application extensions load into. Called once, before anything else; loads nothing. */
    fun attach(application: Application)

    /** Says where the user's choices are kept. Called once, when the app's data opens; everything else waits for it. */
    fun bind(choices: ExtensionChoiceStore)

    /**
     * The sources of the extensions and languages that are on. The first call loads them, which can take seconds;
     * later calls answer at once. Never throws: an extension that fails to load is left out.
     */
    suspend fun sources(): List<SeriesSource>
}

/** The Series area's dependencies; a build without an [ExtensionSourceHost] has none, and no Series tab. */
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
        checking.withLock { repository.checkFollowed(sources(), onUpdate) }
}
