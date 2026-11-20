package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.sources.common.classifyHttpFailure
import com.theoriacodex.sources.http.SourceHttpBodyTooLargeException
import java.io.IOException

/**
 * The one error boundary of every Hitomi entry point: whatever [block] throws leaves as a
 * [SourceAdapterException]. Protocol and oversized-body failures are PARSE, transport failures NETWORK, and
 * gallery failures keep their own reason. [operation] names the entry point in messages ("search").
 */
internal suspend fun <T> hitomiCall(operation: String, block: suspend () -> T): T = try {
    block()
} catch (error: HitomiGalleryException) {
    throw SourceAdapterException(reason = error.reason, message = error.message, cause = error.cause ?: error)
} catch (error: HitomiProtocolException) {
    throw hitomiParseFailure("Hitomi $operation protocol failed: ${error.message}", error)
} catch (error: SourceHttpBodyTooLargeException) {
    throw hitomiParseFailure("Hitomi $operation response exceeded its bounded size", error)
} catch (error: IOException) {
    throw SourceAdapterException(SourceFailureReason.NETWORK, "Hitomi $operation request failed", error)
}

internal fun hitomiParseFailure(message: String, cause: Throwable? = null): SourceAdapterException =
    SourceAdapterException(reason = SourceFailureReason.PARSE, message = message, cause = cause)

internal fun hitomiHttpFailure(step: String, statusCode: Int): SourceAdapterException = SourceAdapterException(
    reason = classifyHttpFailure(statusCode),
    message = "Hitomi $step request failed ($statusCode)",
)
