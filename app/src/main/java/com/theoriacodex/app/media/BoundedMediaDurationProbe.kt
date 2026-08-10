package com.theoriacodex.app.media

import com.theoriacodex.app.source.requestHeaders
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.sources.http.SourceByteRange
import com.theoriacodex.sources.http.SourceByteResponse
import com.theoriacodex.sources.http.SourceHttpClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal class BoundedMediaDurationProbe(
    private val httpClient: SourceHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val byteWindowLimit: Int = DEFAULT_DURATION_PROBE_WINDOW_BYTES,
    private val operationTimeoutMs: Long = DEFAULT_DURATION_PROBE_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_DURATION_PROBE_RETRY_MS,
) {
    init {
        require(byteWindowLimit > 0) { "Duration probe byte window must be positive" }
        require(operationTimeoutMs > 0L) { "Duration probe timeout must be positive" }
        require(retryDelayMs > 0L) { "Duration probe retry delay must be positive" }
    }

    suspend fun probe(post: Post): MediaDurationState {
        val ref = authoritativeDurationProbeRef(post)
            ?: return MediaDurationState.Unsupported(
                MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
            )
        val location = ref.url?.trim()?.takeIf(String::isNotBlank)
            ?: return MediaDurationState.Unsupported(
                MediaDurationUnsupportedReason.NO_AUTHORITATIVE_MEDIA,
            )
        return try {
            withTimeoutOrNull(operationTimeoutMs) {
                probeWithinBudget(post, ref, location)
            } ?: retryable(MediaDurationFailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryable(MediaDurationFailureReason.TRANSPORT_FAILURE)
        }
    }

    private suspend fun probeWithinBudget(
        post: Post,
        ref: ImageRef,
        location: String,
    ): MediaDurationState {
        val headRange = SourceByteRange(0L, byteWindowLimit.toLong() - 1L)
        val headResponse = httpClient.getBytes(
            url = location,
            headers = post.id.source.requestHeaders(),
            range = headRange,
            maxBodyBytes = byteWindowLimit,
        )
        val head = headResponse.toHeadWindow(headRange) ?: return retryable(
            MediaDurationFailureReason.TRANSPORT_FAILURE,
        )
        val headResult = parseDuration(ref, listOf(head))
        if (headResult is ContainerDurationParseResult.Known) return headResult.toKnownState()
        if (head.totalBytes <= head.bytes.size) return headResult.toTerminalState()
        if (headResult.isDefinitivelyInvalid()) return headResult.toTerminalState()

        val tailStart = (head.totalBytes - byteWindowLimit).coerceAtLeast(0L)
        val tailRange = SourceByteRange(tailStart, head.totalBytes - 1L)
        val tailResponse = httpClient.getBytes(
            url = location,
            headers = post.id.source.requestHeaders(),
            range = tailRange,
            maxBodyBytes = byteWindowLimit,
        )
        val tail = tailResponse.toTailWindow(tailRange, head.totalBytes) ?: return retryable(
            MediaDurationFailureReason.TRANSPORT_FAILURE,
        )
        return parseDuration(ref, listOf(head, tail)).toTerminalState()
    }

    private fun retryable(reason: MediaDurationFailureReason): MediaDurationState.RetryableFailure {
        return MediaDurationState.RetryableFailure(
            retryAtEpochMs = clock() + retryDelayMs,
            reason = reason,
        )
    }
}

private fun SourceByteResponse.toHeadWindow(requested: SourceByteRange): MediaByteWindow? {
    if (body.isEmpty()) return null
    val requestedBytes = requested.endInclusive - requested.startInclusive + 1L
    if (body.size.toLong() > requestedBytes) return null
    return when (statusCode) {
        HTTP_PARTIAL_CONTENT -> {
            val range = contentRange() ?: return null
            val expectedEnd = minOf(requested.endInclusive, range.totalBytes - 1L)
            if (
                range.startInclusive != requested.startInclusive ||
                range.endInclusive != expectedEnd ||
                range.bodyBytes != body.size.toLong()
            ) {
                return null
            }
            MediaByteWindow(body, range.startInclusive, range.totalBytes)
        }
        HTTP_OK -> {
            val declaredLength = headerValue("Content-Length")?.toLongOrNull()
            if (declaredLength != null && declaredLength != body.size.toLong()) return null
            MediaByteWindow(body, absoluteStart = 0L, totalBytes = body.size.toLong())
        }
        else -> null
    }
}

private fun SourceByteResponse.toTailWindow(
    requested: SourceByteRange,
    expectedTotalBytes: Long,
): MediaByteWindow? {
    if (statusCode != HTTP_PARTIAL_CONTENT || body.isEmpty()) return null
    val range = contentRange() ?: return null
    if (
        range.startInclusive != requested.startInclusive ||
        range.endInclusive != requested.endInclusive ||
        range.totalBytes != expectedTotalBytes ||
        range.bodyBytes != body.size.toLong()
    ) {
        return null
    }
    return MediaByteWindow(body, range.startInclusive, range.totalBytes)
}

private fun SourceByteResponse.contentRange(): ParsedContentRange? {
    val match = CONTENT_RANGE_PATTERN.matchEntire(headerValue("Content-Range")?.trim().orEmpty())
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0L || end < start || total <= end) return null
    return ParsedContentRange(start, end, total)
}

private fun SourceByteResponse.headerValue(name: String): String? {
    return headers.entries
        .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

private fun parseDuration(
    ref: ImageRef,
    windows: List<MediaByteWindow>,
): ContainerDurationParseResult {
    val mime = ref.mime?.lowercase().orEmpty()
    val path = ref.url?.substringBefore('?')?.lowercase().orEmpty()
    return when {
        "webm" in mime || path.endsWith(".webm") -> parseWebmDuration(windows)
        "mp4" in mime || path.endsWith(".mp4") || path.endsWith(".m4v") -> {
            parseMp4Duration(windows)
        }
        else -> detectContainerDuration(windows)
    }
}

private fun detectContainerDuration(windows: List<MediaByteWindow>): ContainerDurationParseResult {
    val mp4 = parseMp4Duration(windows)
    if (mp4 !is ContainerDurationParseResult.Unsupported ||
        mp4.reason != ContainerDurationUnsupportedReason.UNSUPPORTED_CONTAINER
    ) {
        return mp4
    }
    return parseWebmDuration(windows)
}

private fun ContainerDurationParseResult.isDefinitivelyInvalid(): Boolean {
    return this is ContainerDurationParseResult.Unsupported &&
        reason in setOf(
            ContainerDurationUnsupportedReason.MALFORMED,
            ContainerDurationUnsupportedReason.OVERFLOW,
        )
}

private fun ContainerDurationParseResult.toKnownState(): MediaDurationState.Known {
    val known = this as ContainerDurationParseResult.Known
    return MediaDurationState.Known(
        durationMs = known.durationMs,
        provenance = MediaDurationProvenance.CONTAINER_PROBE,
    )
}

private fun ContainerDurationParseResult.toTerminalState(): MediaDurationState {
    return when (this) {
        is ContainerDurationParseResult.Known -> toKnownState()
        ContainerDurationParseResult.NeedMoreData,
        is ContainerDurationParseResult.Unsupported,
        -> MediaDurationState.Unsupported(MediaDurationUnsupportedReason.UNSUPPORTED_CONTAINER)
    }
}

private data class ParsedContentRange(
    val startInclusive: Long,
    val endInclusive: Long,
    val totalBytes: Long,
) {
    val bodyBytes: Long
        get() = endInclusive - startInclusive + 1L
}

internal const val DEFAULT_DURATION_PROBE_WINDOW_BYTES = 256 * 1024
internal const val DEFAULT_DURATION_PROBE_TIMEOUT_MS = 12_000L
private const val DEFAULT_DURATION_PROBE_RETRY_MS = 5L * 60L * 1_000L
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
