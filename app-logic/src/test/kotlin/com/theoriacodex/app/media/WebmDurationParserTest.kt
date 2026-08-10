package com.theoriacodex.app.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WebmDurationParserTest {
    @Test
    fun `parses duration and timestamp scale from info element`() {
        val file = webmFile(durationTicks = 12_500.0, timestampScale = 1_000_000)

        assertEquals(
            ContainerDurationParseResult.Known(12_500L),
            parseWebmDuration(listOf(window(file))),
        )
    }

    @Test
    fun `duration before timestamp scale remains authoritative`() {
        val info = element(DURATION_ID, doubleBytes(5_000.0)) +
            element(TIMESTAMP_SCALE_ID, unsignedBytes(2_000_000))
        val file = EBML_HEADER + element(INFO_ID, info)

        assertEquals(
            ContainerDurationParseResult.Known(10_000L),
            parseWebmDuration(listOf(window(file))),
        )
    }

    @Test
    fun `truncated info element requests more bytes`() {
        val complete = webmFile(durationTicks = 5_000.0, timestampScale = 1_000_000)
        val truncated = complete.copyOf(complete.size - 3)

        assertEquals(
            ContainerDurationParseResult.NeedMoreData,
            parseWebmDuration(listOf(window(truncated))),
        )
    }

    @Test
    fun `missing info settles missing duration`() {
        assertEquals(
            ContainerDurationParseResult.Unsupported(
                ContainerDurationUnsupportedReason.MISSING_DURATION,
            ),
            parseWebmDuration(listOf(window(EBML_HEADER))),
        )
    }

    @Test
    fun `nan duration is malformed`() {
        val file = EBML_HEADER + element(INFO_ID, element(DURATION_ID, doubleBytes(Double.NaN)))

        assertEquals(
            ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED),
            parseWebmDuration(listOf(window(file))),
        )
    }

    @Test
    fun `overflowing duration is rejected`() {
        val file = webmFile(durationTicks = Double.MAX_VALUE, timestampScale = 1_000_000)

        assertEquals(
            ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.OVERFLOW),
            parseWebmDuration(listOf(window(file))),
        )
    }

    private fun webmFile(durationTicks: Double, timestampScale: Int): ByteArray {
        val info = element(TIMESTAMP_SCALE_ID, unsignedBytes(timestampScale)) +
            element(DURATION_ID, doubleBytes(durationTicks))
        return EBML_HEADER + element(INFO_ID, info)
    }

    private fun element(id: ByteArray, payload: ByteArray): ByteArray {
        require(payload.size < 127)
        return id + byteArrayOf((0x80 or payload.size).toByte()) + payload
    }

    private fun unsignedBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    private fun doubleBytes(value: Double): ByteArray {
        return ByteBuffer.allocate(Double.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putDouble(value)
            .array()
    }

    private fun window(bytes: ByteArray): MediaByteWindow {
        return MediaByteWindow(bytes, absoluteStart = 0L, totalBytes = bytes.size.toLong())
    }

    private companion object {
        val EBML_HEADER = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x80.toByte())
        val INFO_ID = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
        val TIMESTAMP_SCALE_ID = byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte())
        val DURATION_ID = byteArrayOf(0x44, 0x89.toByte())
    }
}
