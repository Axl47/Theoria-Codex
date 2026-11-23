package com.theoriacodex.app.ui

import com.theoriacodex.domain.adapter.SourceFailureReason

/**
 * One short line saying why a source's request failed. [rawMessage], where a caller has one fit to show, replaces the
 * general line; a rate limit always reads the same.
 */
fun sourceFailureDetail(reason: SourceFailureReason?, rawMessage: String = ""): String = when (reason) {
    SourceFailureReason.RATE_LIMITED -> "Rate limited. Wait a bit, then retry."
    SourceFailureReason.BROWSER_CHECK -> "Needs a browser check."
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
