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
    private val initialWindowBytes: Int = DEFAULT_DURATION_PROBE_INITIAL_BYTES,
    private val operationTimeoutMs: Long = DEFAULT_DURATION_PROBE_TIMEOUT_MS,
    private val retryDelayMs: Long = DEFAULT_DURATION_PROBE_RETRY_MS,
) {
    init {
        require(byteWindowLimit > 0) { "Duration probe byte window must be positive" }
        require(initialWindowBytes > 0) { "Duration probe initial window must be positive" }
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

    /**
     * Most media names its duration in the first bytes, so a small read settles it. MP4 box sizes
     * then point at a later `moov`; anything else falls back to full-size head and tail windows.
     */
    private suspend fun probeWithinBudget(
        post: Post,
        ref: ImageRef,
        location: String,
    ): MediaDurationState {
        val smallWindow = minOf(initialWindowBytes, byteWindowLimit)
        val smallHead = readHead(post, location, smallWindow) ?: return transportFailure()
        val smallResult = parseDuration(ref, listOf(smallHead))
        settledHeadState(smallResult, smallHead)?.let { return it }
        seekMovieBox(post, location, smallHead, smallWindow)?.let { return it }

        val head = if (smallWindow < byteWindowLimit) {
            readHead(post, location, byteWindowLimit) ?: return transportFailure()
        } else {
            smallHead
        }
        val headResult = parseDuration(ref, listOf(head))
        settledHeadState(headResult, head)?.let { return it }

        val tailStart = (head.totalBytes - byteWindowLimit).coerceAtLeast(0L)
        val tail = readRange(post, location, tailStart, byteWindowLimit, head.totalBytes)
            ?: return transportFailure()
        return parseDuration(ref, listOf(head, tail)).toTerminalState()
    }

    private fun settledHeadState(result: ContainerDurationParseResult, head: MediaByteWindow): MediaDurationState? {
        return when {
            result is ContainerDurationParseResult.Known -> result.toKnownState()
            head.totalBytes <= head.bytes.size || result.isDefinitivelyInvalid() -> result.toTerminalState()
            else -> null
        }
    }

    /** Follows top-level MP4 boxes to `moov`. Null hands over to the fallback windows. */
    private suspend fun seekMovieBox(
        post: Post,
        location: String,
        head: MediaByteWindow,
        windowBytes: Int,
    ): MediaDurationState? {
        var offset = nextMp4TopLevelBoxOffset(head) ?: return null
        repeat(MAX_DURATION_PROBE_BOX_SEEKS) {
            val window = readRange(post, location, offset, windowBytes, head.totalBytes) ?: return null
            val result = parseMp4Duration(listOf(window))
            if (result is ContainerDurationParseResult.Known) return result.toKnownState()
            offset = nextMp4TopLevelBoxOffset(window)?.takeIf { next -> next > offset } ?: return null
        }
        return null
    }

    private suspend fun readHead(post: Post, location: String, windowBytes: Int): MediaByteWindow? {
        val range = SourceByteRange(0L, windowBytes.toLong() - 1L)
        return httpClient.getBytes(
            url = location,
            headers = post.id.source.requestHeaders(),
            range = range,
            maxBodyBytes = windowBytes,
        ).toHeadWindow(range)
    }

    private suspend fun readRange(
        post: Post,
        location: String,
        start: Long,
        windowBytes: Int,
        totalBytes: Long,
    ): MediaByteWindow? {
        val range = SourceByteRange(start, minOf(start + windowBytes, totalBytes) - 1L)
        return httpClient.getBytes(
            url = location,
            headers = post.id.source.requestHeaders(),
            range = range,
            maxBodyBytes = windowBytes,
        ).toRangeWindow(range, totalBytes)
    }

    private fun transportFailure() = retryable(MediaDurationFailureReason.TRANSPORT_FAILURE)

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

private fun SourceByteResponse.toRangeWindow(
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
internal const val DEFAULT_DURATION_PROBE_INITIAL_BYTES = 16 * 1024
private const val MAX_DURATION_PROBE_BOX_SEEKS = 4
internal const val DEFAULT_DURATION_PROBE_TIMEOUT_MS = 12_000L
private const val DEFAULT_DURATION_PROBE_RETRY_MS = 5L * 60L * 1_000L
private const val HTTP_OK = 200
private const val HTTP_PARTIAL_CONTENT = 206
private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
