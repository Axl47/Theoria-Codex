package com.theoriacodex.app.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class Mp4DurationParserTest {
    @Test
    fun `parses version zero movie header from head window`() {
        val file = mp4File(movieHeaderVersionZero(timescale = 1_000, duration = 12_345))

        assertEquals(
            ContainerDurationParseResult.Known(12_345L),
            parseMp4Duration(listOf(window(file))),
        )
    }

    @Test
    fun `parses movie metadata contained in tail window`() {
        val head = box("ftyp", byteArrayOf(0, 0, 0, 0)) + box("mdat", ByteArray(300))
        val moov = box("moov", box("mvhd", movieHeaderVersionZero(1_000, 8_000)))
        val total = head.size + moov.size

        assertEquals(
            ContainerDurationParseResult.Known(8_000L),
            parseMp4Duration(
                listOf(
                    MediaByteWindow(head.copyOfRange(0, 64), 0L, total.toLong()),
                    MediaByteWindow(moov, head.size.toLong(), total.toLong()),
                ),
            ),
        )
    }

    @Test
    fun `truncated movie header requests more bytes`() {
        val truncated = box("ftyp", byteArrayOf()) + box("moov", box("mvhd", ByteArray(6)))

        assertEquals(
            ContainerDurationParseResult.NeedMoreData,
            parseMp4Duration(listOf(window(truncated))),
        )
    }

    @Test
    fun `missing movie header settles missing duration`() {
        val file = box("ftyp", byteArrayOf()) + box("moov", box("free", byteArrayOf()))

        assertEquals(
            ContainerDurationParseResult.Unsupported(
                ContainerDurationUnsupportedReason.MISSING_DURATION,
            ),
            parseMp4Duration(listOf(window(file))),
        )
    }

    @Test
    fun `zero timescale is malformed`() {
        val file = mp4File(movieHeaderVersionZero(timescale = 0, duration = 8_000))

        assertEquals(
            ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.MALFORMED),
            parseMp4Duration(listOf(window(file))),
        )
    }

    @Test
    fun `unsigned version one duration overflow is rejected`() {
        val payload = ByteArray(32).apply {
            this[0] = 1
            writeUInt(offset = 20, value = 1)
            for (index in 24 until 32) this[index] = 0xFF.toByte()
        }
        val file = mp4File(payload)

        assertEquals(
            ContainerDurationParseResult.Unsupported(ContainerDurationUnsupportedReason.OVERFLOW),
            parseMp4Duration(listOf(window(file))),
        )
    }

    private fun mp4File(movieHeader: ByteArray): ByteArray {
        return box("ftyp", byteArrayOf(0, 0, 0, 0)) + box("moov", box("mvhd", movieHeader))
    }

    private fun movieHeaderVersionZero(timescale: Int, duration: Int): ByteArray {
        return ByteArray(20).apply {
            writeUInt(offset = 12, value = timescale)
            writeUInt(offset = 16, value = duration)
        }
    }

    private fun ByteArray.writeUInt(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 8).array())
        output.write(type.toByteArray(Charsets.US_ASCII))
        output.write(payload)
        return output.toByteArray()
    }

    private fun window(bytes: ByteArray): MediaByteWindow {
        return MediaByteWindow(bytes, absoluteStart = 0L, totalBytes = bytes.size.toLong())
    }
}
