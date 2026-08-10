package com.theoriacodex.app.media

import java.math.BigInteger

fun parseMp4Duration(windows: List<MediaByteWindow>): ContainerDurationParseResult {
    if (windows.isEmpty()) return ContainerDurationParseResult.NeedMoreData
    var sawMp4 = false
    var sawMoov = false
    var needsMoreData = false
    var unsupportedReason: ContainerDurationUnsupportedReason? = null
    windows.sortedBy(MediaByteWindow::absoluteStart).forEach { window ->
        sawMp4 = sawMp4 || window.hasTypeAt(4, TYPE_FTYP)
        val candidates = moovCandidateOffsets(window)
        sawMoov = sawMoov || candidates.isNotEmpty()
        candidates.forEach { offset ->
            when (val result = parseMoovDuration(window, offset)) {
                is ContainerDurationParseResult.Known -> return result
                ContainerDurationParseResult.NeedMoreData -> needsMoreData = true
                is ContainerDurationParseResult.Unsupported -> {
                    if (result.reason == ContainerDurationUnsupportedReason.OVERFLOW) return result
                    unsupportedReason = result.reason
                }
            }
        }
    }
    return when {
        needsMoreData -> ContainerDurationParseResult.NeedMoreData
        unsupportedReason != null -> ContainerDurationParseResult.Unsupported(
            requireNotNull(unsupportedReason),
        )
        sawMoov || sawMp4 -> ContainerDurationParseResult.Unsupported(
            ContainerDurationUnsupportedReason.MISSING_DURATION,
        )
        else -> ContainerDurationParseResult.Unsupported(
            ContainerDurationUnsupportedReason.UNSUPPORTED_CONTAINER,
        )
    }
}

private fun moovCandidateOffsets(window: MediaByteWindow): Set<Int> {
    val candidates = linkedSetOf<Int>()
    if (window.absoluteStart == 0L) {
        var offset = 0
        while (offset <= window.bytes.size - BOX_HEADER_BYTES) {
            val header = readBoxHeader(window, offset) ?: break
            if (header.type == TYPE_MOOV) candidates += offset
            val next = offset.toLong() + header.size
            if (next <= offset || next > window.bytes.size) break
            offset = next.toInt()
        }
    }
    for (offset in 0..(window.bytes.size - BOX_HEADER_BYTES).coerceAtLeast(-1)) {
        if (window.hasTypeAt(offset + 4, TYPE_MOOV) && readBoxHeader(window, offset)?.type == TYPE_MOOV) {
            candidates += offset
        }
    }
    return candidates
}

private fun parseMoovDuration(
    window: MediaByteWindow,
    moovOffset: Int,
): ContainerDurationParseResult {
    val moov = readBoxHeader(window, moovOffset) ?: return ContainerDurationParseResult.NeedMoreData
    if (moov.type != TYPE_MOOV) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
    }
    val declaredEnd = moovOffset.toLong() + moov.size
    if (declaredEnd > window.totalBytes - window.absoluteStart) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
    }
    val availableEnd = minOf(declaredEnd, window.bytes.size.toLong()).toInt()
    var childOffset = moovOffset + moov.headerSize
    while (childOffset < availableEnd) {
        if (availableEnd - childOffset < BOX_HEADER_BYTES) {
            return ContainerDurationParseResult.NeedMoreData
        }
        val child = readBoxHeader(window, childOffset)
            ?: return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
        if (child.type == TYPE_MVHD) return parseMovieHeader(window.bytes, childOffset, child.headerSize)
        val next = childOffset.toLong() + child.size
        if (next <= childOffset) {
            return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
        }
        if (next > availableEnd) return ContainerDurationParseResult.NeedMoreData
        childOffset = next.toInt()
    }
    return if (declaredEnd > availableEnd) {
        ContainerDurationParseResult.NeedMoreData
    } else {
        ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MISSING_DURATION)
    }
}

private fun parseMovieHeader(
    bytes: ByteArray,
    boxOffset: Int,
    headerSize: Int,
): ContainerDurationParseResult {
    val content = boxOffset + headerSize
    if (content >= bytes.size) return ContainerDurationParseResult.NeedMoreData
    return when (bytes[content].toInt() and 0xFF) {
        0 -> parseMovieHeaderVersionZero(bytes, content)
        1 -> parseMovieHeaderVersionOne(bytes, content)
        else -> ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
    }
}

private fun parseMovieHeaderVersionZero(
    bytes: ByteArray,
    content: Int,
): ContainerDurationParseResult {
    if (content > bytes.size - 20) return ContainerDurationParseResult.NeedMoreData
    val timescale = readUnsignedInt(bytes, content + 12)
    val duration = BigInteger.valueOf(readUnsignedInt(bytes, content + 16))
    return scaleDuration(duration, timescale)
}

private fun parseMovieHeaderVersionOne(
    bytes: ByteArray,
    content: Int,
): ContainerDurationParseResult {
    if (content > bytes.size - 32) return ContainerDurationParseResult.NeedMoreData
    val timescale = readUnsignedInt(bytes, content + 20)
    val duration = BigInteger(1, bytes.copyOfRange(content + 24, content + 32))
    return scaleDuration(duration, timescale)
}

private fun scaleDuration(duration: BigInteger, timescale: Long): ContainerDurationParseResult {
    if (timescale <= 0L) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
    }
    val milliseconds = duration.multiply(BigInteger.valueOf(1_000L))
        .divide(BigInteger.valueOf(timescale))
    if (milliseconds <= BigInteger.ZERO) {
        return ContainerDurationParseResult.Unsupported(
            ContainerDurationUnsupportedReason.MISSING_DURATION,
        )
    }
    if (milliseconds > BigInteger.valueOf(Long.MAX_VALUE)) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.OVERFLOW)
    }
    return ContainerDurationParseResult.Known(milliseconds.toLong())
}

private fun readBoxHeader(window: MediaByteWindow, offset: Int): BoxHeader? {
    if (offset < 0 || offset > window.bytes.size - BOX_HEADER_BYTES) return null
    val size32 = readUnsignedInt(window.bytes, offset)
    val type = window.bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
    val (size, headerSize) = when (size32) {
        0L -> (window.totalBytes - window.absoluteStart - offset) to BOX_HEADER_BYTES
        1L -> {
            if (offset > window.bytes.size - EXTENDED_BOX_HEADER_BYTES) return null
            val value = BigInteger(1, window.bytes.copyOfRange(offset + 8, offset + 16))
            if (value > BigInteger.valueOf(Long.MAX_VALUE)) return null
            value.toLong() to EXTENDED_BOX_HEADER_BYTES
        }
        else -> size32 to BOX_HEADER_BYTES
    }
    if (size < headerSize) return null
    return BoxHeader(type = type, size = size, headerSize = headerSize)
}

private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long {
    return ((bytes[offset].toLong() and 0xFFL) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
        (bytes[offset + 3].toLong() and 0xFFL)
}

private fun MediaByteWindow.hasTypeAt(offset: Int, type: String): Boolean {
    return offset >= 0 && offset <= bytes.size - 4 &&
        bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == type
}

private data class BoxHeader(
    val type: String,
    val size: Long,
    val headerSize: Int,
)

private const val BOX_HEADER_BYTES = 8
private const val EXTENDED_BOX_HEADER_BYTES = 16
private const val TYPE_FTYP = "ftyp"
private const val TYPE_MOOV = "moov"
private const val TYPE_MVHD = "mvhd"
