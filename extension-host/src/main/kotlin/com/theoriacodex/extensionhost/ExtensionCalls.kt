package com.theoriacodex.extensionhost

import com.theoriacodex.domain.adapter.SourceAdapterException
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.diagnostics.Diagnostics
import com.theoriacodex.domain.series.MAX_MEMO_LENGTH
import com.theoriacodex.domain.series.Series
import com.theoriacodex.domain.series.SeriesEntry
import com.theoriacodex.domain.series.SeriesId
import com.theoriacodex.domain.series.extensionSeriesStatus
import com.theoriacodex.sources.http.HostOutageKind
import com.theoriacodex.sources.http.HostUnavailableException
import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class ExtensionCalls(private val name: String, private val onChallenged: (host: String) -> Unit) {
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    suspend fun <T> calling(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (error: Throwable) {
        throw failure(extensionFailure(error))
    }

    fun failure(error: Throwable): SourceAdapterException {
        if (error is SourceAdapterException) return error
        if (error.isBrowserCheck()) generateSequence(error) { it.cause }.firstNotNullOf { it as? HostUnavailableException }.let { onChallenged(it.host) }
        val reason = when {
            error is HttpException -> when (error.code) {
                HTTP_TOO_MANY_REQUESTS -> SourceFailureReason.RATE_LIMITED
                HTTP_FORBIDDEN -> SourceFailureReason.BLOCKED
                else -> SourceFailureReason.NETWORK
            }
            error.isBrowserCheck() -> SourceFailureReason.BLOCKED
            error is HostUnavailableException && error.kind == HostOutageKind.RATE_LIMITED -> SourceFailureReason.RATE_LIMITED
            error is SocketTimeoutException || error is InterruptedIOException && error.message == OKHTTP_CALL_TIMEOUT -> SourceFailureReason.TIMED_OUT
            error is IOException -> SourceFailureReason.NETWORK
            error is LinkageError -> {
                Diagnostics.report(LOG_AREA, "$name needs host API this module lacks", error)
                SourceFailureReason.UNKNOWN
            }
            error is Error -> {
                Diagnostics.report(LOG_AREA, "$name failed", error)
                SourceFailureReason.UNKNOWN
            }
            else -> SourceFailureReason.PARSE
        }
        return SourceAdapterException(reason, error.message, error)
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_FORBIDDEN = 403
        const val OKHTTP_CALL_TIMEOUT = "timeout"
        const val LOG_AREA = "extensions"
    }
}

internal fun Throwable.endsTheProcess(): Boolean = this is VirtualMachineError

internal suspend fun extensionFailure(error: Throwable): Throwable {
    if (error.endsTheProcess()) throw error
    if (error is CancellationException) currentCoroutineContext().ensureActive()
    return error
}

internal fun Throwable.isBrowserCheck(): Boolean =
    generateSequence(this) { it.cause }.any { it is HostUnavailableException && it.kind == HostOutageKind.CHALLENGE }

internal fun <T> recentlyListed(): MutableMap<String, T> = Collections.synchronizedMap(
    object : LinkedHashMap<String, T>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, T>): Boolean = size > MAX_LISTED
    },
)

private const val MAX_LISTED = 2_048

internal fun extensionSeries(
    id: SeriesId,
    title: String,
    cover: String?,
    author: String?,
    genres: String?,
    language: String,
    description: String?,
    status: Int,
    memo: JsonObject? = null,
) = Series(
    id = id,
    title = title,
    coverUrl = cover?.takeIf(String::isNotBlank),
    author = author?.takeIf(String::isNotBlank),
    genres = genres.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
    language = language,
    description = description?.trim()?.takeIf(String::isNotEmpty),
    status = extensionSeriesStatus(status),
    memo = memo.storedMemo(),
)

internal fun extensionWebUrl(series: Series, stored: String?, baseUrl: String?, sourceUrl: (() -> String)?): String? =
    sourceUrl?.let { runCatching(it).getOrNull()?.takeIf(String::isNotBlank) }
        ?: stored
        ?: baseUrl?.takeIf(String::isNotBlank)?.let { "${it.trimEnd('/')}/${series.id.address.trimStart('/')}" }

internal fun extensionEntry(
    seriesTitle: String,
    address: String,
    name: String,
    number: Float,
    group: String?,
    uploadedAtEpochMs: Long,
    memo: JsonObject? = null,
) = SeriesEntry(
    address = address,
    name = name,
    number = number.takeIf { it >= 0f } ?: ChapterRecognition.parseChapterNumber(seriesTitle, name),
    translatorGroup = group?.takeIf(String::isNotBlank),
    uploadedAtEpochMs = uploadedAtEpochMs.takeIf { it > 0L },
    memo = memo.storedMemo(),
)

internal fun JsonObject?.storedMemo(): String? {
    val text = this?.takeIf(JsonObject::isNotEmpty)?.toString() ?: return null
    if (text.length <= MAX_MEMO_LENGTH) return text
    if (oversizedMemoReported.compareAndSet(false, true)) {
        Diagnostics.report("extensions", "An extension memo of ${text.length} characters was too long to store and was cleared")
    }
    return EMPTY_MEMO
}

private val oversizedMemoReported = AtomicBoolean(false)

internal const val EMPTY_MEMO = "{}"

internal fun sourceMemo(stored: String?): JsonObject =
    stored?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
