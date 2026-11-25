package com.theoriacodex.app.series

import com.theoriacodex.domain.series.RepoExtension
import com.theoriacodex.domain.series.Series

data class MissingProvider(val extension: RepoExtension, val series: List<Series>)

data class MissingExtensions(val offered: List<MissingProvider>, val unlisted: List<Series>)

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

private val PROVIDER_PREFERENCE = listOf(
    RepoExtension.Status.INSTALLED,
    RepoExtension.Status.UPDATE,
    RepoExtension.Status.AVAILABLE,
    RepoExtension.Status.OTHER_KEY,
)
