package com.theoriacodex.domain.adapter

enum class SourceFailureReason {
    AUTH_REQUIRED,
    AUTH_EXPIRED,
    RATE_LIMITED,
    NETWORK,
    PARSE,

    /** A browser check (such as Cloudflare's) stands in front of the site; [SourceAdapterException.browserCheck] says where. */
    BROWSER_CHECK,
    UNKNOWN,
}

/**
 * A page that must be passed in a browser before the site answers: [url] is the address that was refused, [userAgent]
 * the agent that asked for it. A clearance cookie is bound to the agent that earned it, so the browser shown must use
 * this one.
 */
data class BrowserCheck(val url: String, val userAgent: String)

class SourceAdapterException(
    val reason: SourceFailureReason,
    message: String? = null,
    cause: Throwable? = null,
    /** Where a [SourceFailureReason.BROWSER_CHECK] failure can be passed, when the source knows. */
    val browserCheck: BrowserCheck? = null,
) : RuntimeException(message, cause)
