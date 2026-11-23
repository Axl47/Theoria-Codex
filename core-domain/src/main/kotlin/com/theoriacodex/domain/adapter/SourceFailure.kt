package com.theoriacodex.domain.adapter

enum class SourceFailureReason {
    AUTH_REQUIRED,
    AUTH_EXPIRED,
    RATE_LIMITED,
    NETWORK,
    PARSE,
    UNKNOWN,
}

class SourceAdapterException(
    val reason: SourceFailureReason,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
