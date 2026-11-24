package com.theoriacodex.app.search

import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.HostOutageKind

internal fun visibleSourceStatusChipStatuses(
    statuses: List<SourceRunStatus>,
): List<SourceRunStatus> {
    return statuses.filter { status ->
        status.state != SourceRunState.SUCCESS && status.state != SourceRunState.EXCLUDED
    }
}

data class SourceOutageNotice(val kind: HostOutageKind, val sources: List<SourceKey>) {
    val message: String
        get() = "${sources.joinToString(", ") { it.displayName() }}: ${kind.userMessage}"

    val verifiableSource: SourceKey?
        get() = sources.singleOrNull()?.takeIf { kind == HostOutageKind.CHALLENGE }
}

internal fun sourceOutageNotices(
    statuses: List<SourceRunStatus>,
    issues: Map<SourceKey, HostOutageKind>,
): List<SourceOutageNotice> {
    val down = statuses.filter { it.state == SourceRunState.FAILED }
        .mapNotNull { status -> issues[status.source]?.let { kind -> status.source to kind } }
    val (challenged, unavailable) = down.partition { (_, kind) -> kind == HostOutageKind.CHALLENGE }
    return challenged.map { (source, kind) -> SourceOutageNotice(kind, listOf(source)) } +
        unavailable.groupBy({ (_, kind) -> kind }, { (source, _) -> source }).map { (kind, sources) ->
            SourceOutageNotice(kind, sources)
        }
}

internal fun List<SourceRunStatus>.siteNeedingKey(): SourceKey? =
    firstOrNull { it.requiresAccount() && it.source.isCustom }?.source

internal fun siteKeyFailureMessage(providerMessage: String, statuses: List<SourceRunStatus>): String =
    listOfNotNull(providerMessage.takeIf(String::isNotBlank), buildSourceAuthErrorMessage(statuses)).joinToString("\n")
