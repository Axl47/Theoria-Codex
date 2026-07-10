package com.theoriacodex.app.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAnimatedWebPDecoderTest {
    @Test
    fun `animated webp header reads the VP8X feature byte`() {
        val animated = extendedWebPHeader(featureFlags = 0x02, width = 1080, height = 1920)
        val static = extendedWebPHeader(featureFlags = 0x00)

        assertTrue(isAnimatedWebPHeader(animated))
        assertEquals(AnimatedWebPHeader(width = 1080, height = 1920), parseAnimatedWebPHeader(animated))
        assertFalse(isAnimatedWebPHeader(static))
    }

    @Test
    fun `animated webp header rejects truncated or unrelated data`() {
        assertFalse(isAnimatedWebPHeader(ByteArray(29)))
        assertFalse(isAnimatedWebPHeader("not a webp payload".toByteArray()))
        assertFalse(
            isAnimatedWebPHeader(
                extendedWebPHeader(featureFlags = 0x02).apply {
                    writeAscii(offset = 12, value = "VP8 ")
                },
            ),
        )
    }

    @Test
    fun `decoded canvas accepts gallery dimensions and rejects oversized frames`() {
        validateAnimatedWebPCanvas(AnimatedWebPHeader(width = 1080, height = 1920))

        assertThrows(IOException::class.java) {
            validateAnimatedWebPCanvas(AnimatedWebPHeader(width = 4097, height = 4097))
        }
    }

    @Test
    fun `bounded decoder input accepts the limit and rejects one byte more`() {
        runBlocking {
            val bytesAtLimit = byteArrayOf(1, 2, 3, 4)

            assertArrayEquals(
                bytesAtLimit,
                readBoundedAnimatedWebPBytes(Buffer().write(bytesAtLimit), maxBytes = 4L),
            )
            val overflow = try {
                readBoundedAnimatedWebPBytes(Buffer().write(ByteArray(5)), maxBytes = 4L)
                null
            } catch (error: IOException) {
                error
            }
            assertTrue(overflow is IOException)
        }
    }

    @Test(timeout = 5_000L)
    fun `cancelling a decode interrupts a blocking source read`() = runBlocking {
        val readStarted = CountDownLatch(1)
        val readInterrupted = AtomicBoolean(false)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (error: InterruptedException) {
                    readInterrupted.set(true)
                    throw error
                }
                return -1L
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        val decode = launch(start = CoroutineStart.UNDISPATCHED) {
            readBoundedAnimatedWebPBytes(source)
        }
        assertTrue(readStarted.await(2L, TimeUnit.SECONDS))
        decode.cancelAndJoin()

        assertTrue(readInterrupted.get())
    }

    @Test
    fun `byte buffer loader returns independent read only views`() {
        val loader = animatedWebPByteBufferLoader(byteArrayOf(1, 2, 3))

        val first = loader.byteBuffer
        val second = loader.byteBuffer
        first.get()

        assertNotSame(first, second)
        assertTrue(first.isReadOnly)
        assertTrue(second.isReadOnly)
        assertTrue(first.position() == 1)
        assertTrue(second.position() == 0)
    }

    private fun extendedWebPHeader(
        featureFlags: Int,
        width: Int = 2,
        height: Int = 2,
    ): ByteArray {
        return ByteArray(30).apply {
            writeAscii(offset = 0, value = "RIFF")
            writeAscii(offset = 8, value = "WEBP")
            writeAscii(offset = 12, value = "VP8X")
            this[16] = 10
            this[20] = featureFlags.toByte()
            writeUnsignedInt24LittleEndian(offset = 24, value = width - 1)
            writeUnsignedInt24LittleEndian(offset = 27, value = height - 1)
        }
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.forEachIndexed { index, character ->
            this[offset + index] = character.code.toByte()
        }
    }

    private fun ByteArray.writeUnsignedInt24LittleEndian(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
    }
}
