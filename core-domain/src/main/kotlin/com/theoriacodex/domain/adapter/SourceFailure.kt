package com.theoriacodex.domain.adapter

enum class SourceFailureReason {
    AUTH_REQUIRED,
    AUTH_EXPIRED,
    RATE_LIMITED,
    BLOCKED,
    NETWORK,
    TIMED_OUT,
    PARSE,
    UNKNOWN,
}

class SourceAdapterException(
    val reason: SourceFailureReason,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
