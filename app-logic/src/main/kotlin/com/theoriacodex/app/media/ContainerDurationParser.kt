package com.theoriacodex.app.media

data class MediaByteWindow(
    val bytes: ByteArray,
    val absoluteStart: Long,
    val totalBytes: Long,
) {
    init {
        require(absoluteStart >= 0L) { "Media byte window start must be non-negative" }
        require(totalBytes > 0L) { "Media byte window total must be positive" }
        require(bytes.size.toLong() <= totalBytes - absoluteStart) {
            "Media byte window must fit inside its declared total"
        }
    }
}

sealed interface ContainerDurationParseResult {
    data class Known(val durationMs: Long) : ContainerDurationParseResult {
        init {
            require(durationMs > 0L) { "Parsed duration must be positive" }
        }
    }

    data object NeedMoreData : ContainerDurationParseResult

    data class Unsupported(
        val reason: ContainerDurationUnsupportedReason,
    ) : ContainerDurationParseResult
}

enum class ContainerDurationUnsupportedReason {
    MALFORMED,
    MISSING_DURATION,
    OVERFLOW,
    UNSUPPORTED_CONTAINER,
}
