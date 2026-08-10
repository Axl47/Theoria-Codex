package com.theoriacodex.app.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun parseWebmDuration(windows: List<MediaByteWindow>): ContainerDurationParseResult {
    if (windows.isEmpty()) return ContainerDurationParseResult.NeedMoreData
    var sawWebm = false
    var needsMoreData = false
    var unsupportedReason: ContainerDurationUnsupportedReason? = null
    windows.sortedBy(MediaByteWindow::absoluteStart).forEach { window ->
        sawWebm = sawWebm || window.bytes.indexOfSequence(EBML_ID) >= 0
        var infoOffset = window.bytes.indexOfSequence(INFO_ID)
        while (infoOffset >= 0) {
            when (val result = parseInfo(window.bytes, infoOffset + INFO_ID.size)) {
                is ContainerDurationParseResult.Known -> return result
                ContainerDurationParseResult.NeedMoreData -> needsMoreData = true
                is ContainerDurationParseResult.Unsupported -> {
                    if (result.reason == ContainerDurationUnsupportedReason.OVERFLOW) return result
                    unsupportedReason = result.reason
                }
            }
            infoOffset = window.bytes.indexOfSequence(INFO_ID, infoOffset + 1)
        }
    }
    return when {
        needsMoreData -> ContainerDurationParseResult.NeedMoreData
        unsupportedReason != null -> ContainerDurationParseResult.Unsupported(
            requireNotNull(unsupportedReason),
        )
        sawWebm -> ContainerDurationParseResult.Unsupported(
            ContainerDurationUnsupportedReason.MISSING_DURATION,
        )
        else -> ContainerDurationParseResult.Unsupported(
            ContainerDurationUnsupportedReason.UNSUPPORTED_CONTAINER,
        )
    }
}

private fun parseInfo(bytes: ByteArray, sizeOffset: Int): ContainerDurationParseResult {
    val infoSize = readEbmlSize(bytes, sizeOffset) ?: return ContainerDurationParseResult.NeedMoreData
    val contentStart = sizeOffset + infoSize.length
    val declaredEnd = if (infoSize.unknown) bytes.size.toLong() else contentStart + infoSize.value
    val availableEnd = minOf(bytes.size.toLong(), declaredEnd).toInt()
    var timestampScale = DEFAULT_TIMESTAMP_SCALE_NS
    var durationTicks: Double? = null
    var offset = contentStart
    while (offset < availableEnd) {
        val id = readEbmlId(bytes, offset) ?: return ContainerDurationParseResult.NeedMoreData
        val size = readEbmlSize(bytes, offset + id.length)
            ?: return ContainerDurationParseResult.NeedMoreData
        if (size.unknown) {
            return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
        }
        val dataStart = offset + id.length + size.length
        val dataEnd = dataStart.toLong() + size.value
        if (dataEnd > availableEnd) return ContainerDurationParseResult.NeedMoreData
        when (id.value) {
            TIMESTAMP_SCALE_ID -> {
                timestampScale = readUnsignedInteger(bytes, dataStart, size.value.toInt())
                    ?: return ContainerDurationParseResult.Unsupported(
                        ContainerDurationUnsupportedReason.OVERFLOW,
                    )
            }
            DURATION_ID -> {
                durationTicks = readEbmlFloat(bytes, dataStart, size.value.toInt())
                    ?: return ContainerDurationParseResult.Unsupported(
                        ContainerDurationUnsupportedReason.MALFORMED,
                    )
            }
        }
        offset = dataEnd.toInt()
    }
    return finalizeWebmDuration(
        durationTicks = durationTicks,
        timestampScale = timestampScale,
        complete = infoSize.unknown || declaredEnd <= bytes.size,
    )
}

private fun finalizeWebmDuration(
    durationTicks: Double?,
    timestampScale: Long,
    complete: Boolean,
): ContainerDurationParseResult {
    if (!complete) return ContainerDurationParseResult.NeedMoreData
    val ticks = durationTicks ?: return ContainerDurationParseResult.Unsupported(
        ContainerDurationUnsupportedReason.MISSING_DURATION,
    )
    if (!ticks.isFinite() || ticks <= 0.0 || timestampScale <= 0L) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED)
    }
    val durationMs = ticks * timestampScale.toDouble() / NANOSECONDS_PER_MILLISECOND
    if (!durationMs.isFinite() || durationMs > Long.MAX_VALUE.toDouble()) {
        return ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.OVERFLOW)
    }
    val wholeMilliseconds = durationMs.toLong()
    return if (wholeMilliseconds > 0L) {
        ContainerDurationParseResult.Known(wholeMilliseconds)
    } else {
        ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MISSING_DURATION)
    }
}

private fun readEbmlId(bytes: ByteArray, offset: Int): EbmlValue? {
    if (offset !in bytes.indices) return null
    val first = bytes[offset].toInt() and 0xFF
    val length = vintLength(first, MAX_ID_BYTES) ?: return null
    if (offset > bytes.size - length) return null
    var value = 0L
    repeat(length) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL) }
    return EbmlValue(value = value, length = length, unknown = false)
}

private fun readEbmlSize(bytes: ByteArray, offset: Int): EbmlValue? {
    if (offset !in bytes.indices) return null
    val first = bytes[offset].toInt() and 0xFF
    val length = vintLength(first, MAX_SIZE_BYTES) ?: return null
    if (offset > bytes.size - length) return null
    val marker = 1 shl (8 - length)
    var value = (first and (marker - 1)).toLong()
    repeat(length - 1) { index ->
        value = (value shl 8) or (bytes[offset + index + 1].toLong() and 0xFFL)
    }
    val unknown = value == (1L shl (7 * length)) - 1L
    return EbmlValue(value = value, length = length, unknown = unknown)
}

private fun vintLength(first: Int, maximum: Int): Int? {
    if (first == 0) return null
    for (length in 1..maximum) {
        if (first and (1 shl (8 - length)) != 0) return length
    }
    return null
}

private fun readUnsignedInteger(bytes: ByteArray, offset: Int, size: Int): Long? {
    if (size !in 1..8 || offset < 0 || offset > bytes.size - size) return null
    if (size == 8 && bytes[offset].toInt() and 0x80 != 0) return null
    var value = 0L
    repeat(size) { index ->
        if (value > (Long.MAX_VALUE ushr 8)) return null
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
    }
    return value
}

private fun readEbmlFloat(bytes: ByteArray, offset: Int, size: Int): Double? {
    if (offset < 0 || offset > bytes.size - size) return null
    val buffer = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.BIG_ENDIAN)
    return when (size) {
        4 -> buffer.float.toDouble()
        8 -> buffer.double
        else -> null
    }
}

private fun ByteArray.indexOfSequence(sequence: ByteArray, start: Int = 0): Int {
    if (sequence.isEmpty() || start > size - sequence.size) return -1
    for (offset in start.coerceAtLeast(0)..size - sequence.size) {
        if (sequence.indices.all { index -> this[offset + index] == sequence[index] }) return offset
    }
    return -1
}

private data class EbmlValue(
    val value: Long,
    val length: Int,
    val unknown: Boolean,
)

private val EBML_ID = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private val INFO_ID = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
private const val TIMESTAMP_SCALE_ID = 0x2AD7B1L
private const val DURATION_ID = 0x4489L
private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000.0
private const val MAX_ID_BYTES = 4
private const val MAX_SIZE_BYTES = 8
