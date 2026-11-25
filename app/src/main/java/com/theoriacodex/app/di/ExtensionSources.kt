package com.theoriacodex.app.di

import com.theoriacodex.app.series.ChapterPictures
import com.theoriacodex.app.series.observeExtensionAlerts
import com.theoriacodex.data.repository.CheckProgress
import com.theoriacodex.data.repository.FollowedCheck
import com.theoriacodex.data.repository.SeriesDownloads
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.data.repository.SeriesUpdate
import com.theoriacodex.data.repository.checkFollowed
import com.theoriacodex.data.repository.switchSource
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.ExtensionAlerts
import com.theoriacodex.domain.series.ExtensionRepos
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesCovers
import com.theoriacodex.domain.series.SeriesExtensions
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesSource
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeriesDependencies(
    val repository: SeriesRepository,
    val sources: suspend () -> List<SeriesSource>,
    val source: suspend (SourceKey) -> SeriesSource?,
    val extensions: SeriesExtensions,
    val repos: ExtensionRepos,
    val downloads: SeriesDownloads,
    val scope: CoroutineScope,
    val covers: SeriesCovers,
    val pictures: ChapterPictures = ChapterPictures(repository),
    val log: (String) -> Unit,
    val loadedSources: () -> List<SeriesSource> = { emptyList() },
) {
    private val checking = Mutex()

    val extensionAlerts: StateFlow<ExtensionAlerts> by lazy {
        observeExtensionAlerts(repository, sources, repos, merge(extensions.revision, repos.changes))
            .stateIn(scope, SharingStarted.WhileSubscribed(ALERTS_KEPT_MS), ExtensionAlerts())
    }

    suspend fun checkFollowed(
        runEvery: Duration? = null,
        only: Set<SeriesId>? = null,
        onProgress: (CheckProgress) -> Unit = {},
        onUpdate: suspend (SeriesUpdate) -> Unit = {},
    ): FollowedCheck = checking.withLock {
        repository.checkFollowed(source, runEvery, only = only, onProgress = onProgress) { update ->
            downloads.downloadNew(update)
            onUpdate(update)
        }
    }

    suspend fun switchSource(from: SeriesId, source: SeriesSource, to: Series) =
        repository.switchSource(from, source, to, downloads::deleteSeries)
}

private const val ALERTS_KEPT_MS = 5_000L
