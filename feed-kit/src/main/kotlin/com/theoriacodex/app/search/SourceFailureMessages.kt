package com.theoriacodex.app.search

import com.theoriacodex.app.source.displayName
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.orchestration.SourceRunState
import com.theoriacodex.domain.orchestration.SourceRunStatus

/** A source that only an account change can fix, whatever a retry would do. */
fun SourceRunStatus.requiresAccount(): Boolean =
    state == SourceRunState.FAILED &&
        (failureReason == SourceFailureReason.AUTH_REQUIRED || failureReason == SourceFailureReason.AUTH_EXPIRED)

/** A failure a returning network could fix; waiting for rate limits or sign-ins is a separate matter. */
fun SourceRunStatus.retriesOnReconnect(): Boolean =
    state == SourceRunState.FAILED && !requiresAccount() && failureReason != SourceFailureReason.RATE_LIMITED

const val OFFLINE_FEED_TITLE = "You're offline"
const val OFFLINE_FEED_MESSAGE = "This feed will retry when you reconnect."
fun buildSourceAuthErrorMessage(statuses: List<SourceRunStatus>): String? {
    val authSources = statuses
        .filter(SourceRunStatus::requiresAccount)
        .map { it.source.displayName() }
        .distinct()
    if (authSources.isEmpty()) return null

    val names = authSources.joinToString(", ")
    val verb = if (authSources.size == 1) "needs" else "need"
    return "$names $verb account setup. Connect or refresh the account, or add its API key, in Settings > Sites."
}

fun buildSourceFailureMessage(statuses: List<SourceRunStatus>): String? {
    val failures = statuses.filter { status ->
        status.state == SourceRunState.FAILED &&
            status.failureReason != SourceFailureReason.AUTH_REQUIRED &&
            status.failureReason != SourceFailureReason.AUTH_EXPIRED
    }
    if (failures.isEmpty()) return null

    val details = failures.take(3).map { status ->
        val rawMessage = status.errorMessage?.trim().orEmpty()
        val message = sourceFailureDetail(status.failureReason, rawMessage)
        "${status.source.displayName()}: $message"
    }
    val suffix = if (failures.size > 3) "\n+${failures.size - 3} more source errors" else ""
    return details.joinToString(separator = "\n") + suffix
}

private fun sourceFailureDetail(reason: SourceFailureReason?, rawMessage: String): String {
    return when (reason) {
        SourceFailureReason.RATE_LIMITED -> "Rate limited. Wait a bit, then retry."
        SourceFailureReason.NETWORK -> rawMessage.takeIf(String::isNotBlank)
            ?: "Provider unreachable or blocked right now."
        SourceFailureReason.PARSE -> rawMessage.takeIf(String::isNotBlank)
            ?: "Provider response changed; parsing needs an update."
        SourceFailureReason.UNKNOWN, null -> rawMessage.takeIf(String::isNotBlank)
            ?: "Request failed for an unknown reason."
        SourceFailureReason.AUTH_REQUIRED,
        SourceFailureReason.AUTH_EXPIRED -> rawMessage.takeIf(String::isNotBlank)
            ?: "Account setup is required."
    }
}
