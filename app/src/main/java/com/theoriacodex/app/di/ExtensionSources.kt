package com.theoriacodex.app.di

import com.theoriacodex.app.series.ChapterPictures
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.data.repository.switchSource
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Series area's dependencies; a container without an extension host has none. */
class SeriesDependencies(
    val repository: SeriesRepository,
    /** Every source that is on; loads all their extensions on first use. */
    val sources: suspend () -> List<SeriesSource>,
    /** The source a key names when it is on, else null; loads only the extension it needs. */
    val source: suspend (SourceKey) -> SeriesSource?,
    val extensions: SeriesExtensions,
    /** The user's extension repos and installing from them; also where a backup's repos are added. */
    val repos: ExtensionRepos,
    val downloads: SeriesDownloads,
    /** Lives as long as the app, for work that must finish after the screen that started it closes. */
    val scope: CoroutineScope,
    val covers: SeriesCovers,
    /** Which page pictures each series' chapters on the Viewer's strip; lives as long as the app. */
    val pictures: ChapterPictures = ChapterPictures(repository),
    /** Writes a line to the app's log, such as when an entry's lookup starts. */
    val log: (String) -> Unit,
    val loadedSources: () -> List<SeriesSource> = { emptyList() },
) {
    private val checking = Mutex()

    /**
     * Stores the followed series' new chapters and returns what the check found, each series that gained some also
     * handed to [onUpdate] as soon as it is stored, after its new chapters are queued for download where the series
     * asks for that. The
     * Series tab's refresh and the background job both call this and both post a notification per update; one check
     * runs at a time, so neither repeats the other's requests midway, and a chapter one stores is not new to the other.
     * Only the job passes [runEvery], its period, to skip series not due yet.
     */
    suspend fun checkFollowed(runEvery: Duration? = null, onUpdate: suspend (SeriesUpdate) -> Unit = {}): FollowedCheck = checking.withLock {
        repository.checkFollowed(source, runEvery) { update ->
            downloads.downloadNew(update)
            onUpdate(update)
        }
    }

    /**
     * Moves the stored series [from] to [to] on [source], with its settings and progress; see [switchSource]. The
     * downloaded chapters of [from] are deleted: the moved series no longer needs them.
     */
    suspend fun switchSource(from: SeriesId, source: SeriesSource, to: Series) =
        repository.switchSource(from, source, to, downloads::deleteSeries)
}
