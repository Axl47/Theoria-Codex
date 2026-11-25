package com.theoriacodex.app.ui

import com.theoriacodex.domain.adapter.SourceFailureReason

fun sourceFailureDetail(reason: SourceFailureReason?, rawMessage: String = ""): String = when (reason) {
    SourceFailureReason.RATE_LIMITED -> "Too many requests. Try again later."
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
