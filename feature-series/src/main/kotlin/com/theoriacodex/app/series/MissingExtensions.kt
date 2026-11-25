package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MissingProvider(val extension: RepoExtension, val series: List<Series>)

data class MissingExtensions(val offered: List<MissingProvider>, val unlisted: List<Series>)

data class MissingSeries(val series: List<Series>, val switchable: Set<SeriesKind>)

class MissingSeriesViewModel(
    private val repository: SeriesRepository,
    loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*>,
) : ViewModel() {
    val state: StateFlow<MissingSeries?> = combine(
        repository.observeFollowed(),
        channelFlow { sourcesChanged.collectLatest { send(loadSources()) } },
    ) { followed, sources ->
        MissingSeries(missingSeries(followed.map { it.series }, sources), sources.mapTo(HashSet()) { it.key.seriesKind })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun unfollow(id: SeriesId) {
        viewModelScope.launch { repository.setFollowed(id, false) }
    }
}

internal fun missingSeries(followed: List<Series>, sources: List<SeriesSource>): List<Series> {
    val keys = sources.mapTo(HashSet()) { it.key }
    return followed.filter { it.id.source !in keys }
}

internal fun missingExtensions(missing: List<Series>, listing: List<RepoExtension>): MissingExtensions {
    val providers = listing.flatMap { extension -> extension.sources.map { it to extension } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, candidates) -> candidates.minBy { PROVIDER_PREFERENCE.indexOf(it.status) } }
    val (found, unlisted) = missing.partition { it.id.source in providers }
    val offered = found.groupBy { providers.getValue(it.id.source).let { extension -> extension.repo to extension.packageName } }
        .map { (_, series) -> MissingProvider(providers.getValue(series.first().id.source), series) }
        .sortedWith(compareByDescending<MissingProvider> { it.series.size }.thenBy { it.extension.name.lowercase() })
    return MissingExtensions(offered, unlisted.sortedBy { it.title.lowercase() })
}

internal fun installedCopy(extension: RepoExtension, installed: List<InstalledExtension>?): InstalledExtension? {
    if (extension.status != RepoExtension.Status.INSTALLED) return null
    val copies = installed.orEmpty().filter { it.icon?.packageName == extension.packageName }
    return copies.firstOrNull { it.on } ?: copies.firstOrNull { it.signer != null }
}

private val PROVIDER_PREFERENCE = listOf(
    RepoExtension.Status.INSTALLED,
    RepoExtension.Status.UPDATE,
    RepoExtension.Status.AVAILABLE,
    RepoExtension.Status.OTHER_KEY,
)
