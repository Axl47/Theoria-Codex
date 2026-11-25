package com.theoriacodex.app.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theoriacodex.data.repository.FollowedSeries
import com.theoriacodex.data.repository.SeriesRepository
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.series.InstalledExtension
import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.SeriesKind
import com.theoriacodex.domain.series.SeriesSource
import com.theoriacodex.domain.series.SourceOff
import com.theoriacodex.domain.series.seriesKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.text.DateFormat
import java.util.Date

data class MissingProvider(val extension: RepoExtension, val series: List<Series>)

data class MissingExtensions(val offered: List<MissingProvider>, val unlisted: List<Series>)

data class MissingSeries(val series: List<FollowedSeries>, val switchable: Set<SeriesKind>)

class MissingSeriesViewModel(
    private val repository: SeriesRepository,
    loadSources: suspend () -> List<SeriesSource>,
    sourcesChanged: Flow<*>,
) : ViewModel() {
    val state: StateFlow<MissingSeries?> = combine(
        repository.observeFollowed(),
        channelFlow { sourcesChanged.collectLatest { send(loadSources()) } },
    ) { followed, sources ->
        MissingSeries(missingSeries(followed, sources), sources.mapTo(HashSet()) { it.key.seriesKind })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun unfollow(id: SeriesId) {
        viewModelScope.launch { repository.setFollowed(id, false) }
    }
}

internal fun missingSeries(followed: List<FollowedSeries>, sources: List<SeriesSource>): List<FollowedSeries> {
    val keys = sources.mapTo(HashSet()) { it.key }
    return followed.filter { it.series.id.source !in keys }
}

internal fun readingSummary(followed: FollowedSeries, dates: DateFormat): String? {
    val total = followed.entryCount
    if (total == 0) return null
    val words = followed.series.id.source.seriesKind.words
    val done = (total - followed.unreadCount).coerceAtLeast(0)
    if (done == 0 && !followed.started) return "${words.count(total)} · not started"
    val last = followed.lastReadAtEpochMs?.let { " · ${words.done} ${dates.format(Date(it))}" }.orEmpty()
    return "$done of ${words.count(total)}$last"
}

internal fun siteHint(coverUrl: String?): String? {
    val uri = coverUrl?.let { runCatching { URI(it.trim()) }.getOrNull() } ?: return null
    if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
    val labels = uri.host?.lowercase()?.trimEnd('.')?.split('.')?.filter { it.isNotEmpty() } ?: return null
    if (labels.size < 2 || labels.last().all(Char::isDigit)) return null
    val keep = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL_LABELS) 3 else 2
    return labels.takeLast(keep).joinToString(".")
}

private val WEB_SCHEMES = setOf("http", "https")

private val SECOND_LEVEL_LABELS = setOf("co", "com", "net", "org", "ac", "gov", "edu", "ne", "or")

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

internal sealed interface InstalledGap {
    data object Off : InstalledGap

    data class LanguagesOff(val languages: List<String>) : InstalledGap

    data object NotLoaded : InstalledGap

    data object NotInVersion : InstalledGap
}

internal fun installedGap(copy: InstalledExtension, series: List<Series>, off: Map<SourceKey, SourceOff>): InstalledGap = when {
    !copy.on -> InstalledGap.Off
    copy.problem != null -> InstalledGap.NotLoaded
    else -> series.mapNotNull { off[it.id.source]?.language }.distinct().takeIf { it.isNotEmpty() }?.let(InstalledGap::LanguagesOff) ?: InstalledGap.NotInVersion
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
