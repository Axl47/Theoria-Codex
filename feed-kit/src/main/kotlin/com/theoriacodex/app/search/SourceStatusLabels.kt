package com.theoriacodex.app.search

import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.sources.http.HostOutageKind

fun sourceStatusChipText(status: SourceRunStatus): String {
    return when (status.state) {
        SourceRunState.EXCLUDED -> "${status.source.displayName()} excluded"
        SourceRunState.FAILED -> "${status.source.displayName()} ${sourceFailureShortLabel(status.failureReason)}"
        SourceRunState.SUCCESS -> "${status.source.displayName()} OK"
    }
}

internal const val OFFLINE_STATUS_CHIP = "Offline, retries when you reconnect"

fun List<SourceRunStatus>.challengedSource(issues: Map<SourceKey, HostOutageKind>): SourceKey? =
    firstOrNull { it.state == SourceRunState.FAILED && issues[it.source] == HostOutageKind.CHALLENGE }?.source

private fun sourceFailureShortLabel(reason: SourceFailureReason?): String {
    return when (reason) {
        SourceFailureReason.AUTH_REQUIRED -> "needs account setup"
        SourceFailureReason.AUTH_EXPIRED -> "needs sign-in refresh"
        SourceFailureReason.RATE_LIMITED -> "rate limited"
        SourceFailureReason.NETWORK -> "unreachable"
        SourceFailureReason.PARSE -> "changed response"
        SourceFailureReason.UNKNOWN, null -> "failed"
    }
}
