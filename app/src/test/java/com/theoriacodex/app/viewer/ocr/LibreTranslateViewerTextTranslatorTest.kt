package com.theoriacodex.app.viewer.ocr

import com.theoriacodex.data.repository.ViewerOcrLanguage
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreTranslateViewerTextTranslatorTest {
    @Test
    fun `translation sends only phrase and language fields to the configured HTTPS endpoint`() =
        runTest {
            val transport = RecordingTranslationTransport(
                ViewerTranslationHttpResponse(200, """{"translatedText":"Hello world"}"""),
            )
            val translator = LibreTranslateViewerTextTranslator(
                baseUrl = "https://translate.axor.dev/",
                transport = transport,
            )
            val stages = mutableListOf<ViewerTranslationStage>()

            val result = translator.translate(
                ViewerOcrLanguage.JAPANESE,
                " こんにちは世界 ",
                stages::add,
            )

            assertEquals("Hello world", result)
            assertEquals("https://translate.axor.dev/translate", transport.endpoint)
            assertEquals(
                mapOf(
                    "q" to "こんにちは世界",
                    "source" to "ja",
                    "target" to "en",
                    "format" to "text",
                ),
                decodeForm(transport.body),
            )
            assertEquals(listOf(ViewerTranslationStage.TRANSLATING), stages)
        }

    @Test
    fun `every OCR language maps to its LibreTranslate source code`() = runTest {
        val transport = RecordingTranslationTransport(
            ViewerTranslationHttpResponse(200, """{"translatedText":"translated"}"""),
        )
        val translator = LibreTranslateViewerTextTranslator(
            baseUrl = "https://translate.axor.dev",
            transport = transport,
        )
        val expectedCodes = mapOf(
            ViewerOcrLanguage.JAPANESE to "ja",
            ViewerOcrLanguage.CHINESE to "zh-Hans",
            ViewerOcrLanguage.KOREAN to "ko",
        )

        expectedCodes.forEach { (language, code) ->
            translator.translate(language, "phrase") {}
            assertEquals(code, decodeForm(transport.body)["source"])
        }
    }

    @Test
    fun `rate limits and malformed success responses become retryable card messages`() = runTest {
        val busy = LibreTranslateViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(ViewerTranslationHttpResponse(429, "")),
        )
        val malformed = LibreTranslateViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(ViewerTranslationHttpResponse(200, "{}")),
        )

        val busyFailure = runCatching {
            busy.translate(ViewerOcrLanguage.KOREAN, "안녕하세요") {}
        }.exceptionOrNull()
        val malformedFailure = runCatching {
            malformed.translate(ViewerOcrLanguage.CHINESE, "你好") {}
        }.exceptionOrNull()

        assertTrue(busyFailure is IOException)
        assertEquals(
            "Translation server is busy · Tap the phrase to retry",
            busyFailure?.message,
        )
        assertTrue(malformedFailure is IOException)
        assertEquals(
            "Translation server returned no text · Tap the phrase to retry",
            malformedFailure?.message,
        )
    }

    @Test
    fun `translator rejects cleartext endpoints and oversized phrases before network work`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibreTranslateViewerTextTranslator("http://translate.axor.dev")
        }
        val translator = LibreTranslateViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(ViewerTranslationHttpResponse(200, "{}")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                translator.translate(
                    ViewerOcrLanguage.JAPANESE,
                    "x".repeat(MAX_TRANSLATION_CHARACTERS + 1),
                ) {}
            }
        }
    }
}

private class RecordingTranslationTransport(
    private val response: ViewerTranslationHttpResponse,
) : ViewerTranslationHttpTransport {
    var endpoint: String = ""
    var body: String = ""

    override suspend fun postForm(endpoint: String, body: String): ViewerTranslationHttpResponse {
        this.endpoint = endpoint
        this.body = body
        return response
    }
}

private fun decodeForm(body: String): Map<String, String> = body
    .split('&')
    .associate { field ->
        val (key, value) = field.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
