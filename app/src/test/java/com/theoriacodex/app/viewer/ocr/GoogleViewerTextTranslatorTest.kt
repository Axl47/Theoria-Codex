package com.theoriacodex.app.viewer.ocr

import com.google.gson.JsonParser
import com.theoriacodex.data.repository.ViewerOcrLanguage
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleViewerTextTranslatorTest {
    @Test
    fun `batch sends only unique phrases and language fields to the HTTPS endpoint`() = runTest {
        val transport = RecordingTranslationTransport(
            ViewerTranslationHttpResponse(200, """{"translations":["One","Two"]}"""),
        )
        val translator = GoogleViewerTextTranslator(
            baseUrl = "https://translate.axor.dev/",
            transport = transport,
        )
        val stages = mutableListOf<ViewerTranslationStage>()

        val result = translator.translateBatch(
            ViewerOcrLanguage.JAPANESE,
            listOf(" 一 ", "二", "一"),
            stages::add,
        )

        assertEquals(mapOf("一" to "One", "二" to "Two"), result)
        assertEquals("https://translate.axor.dev/translate-batch", transport.endpoint)
        val payload = JsonParser.parseString(transport.bodies.single()).asJsonObject
        assertEquals(setOf("q", "source", "target", "format"), payload.keySet())
        assertEquals(listOf("一", "二"), payload.getAsJsonArray("q").map { it.asString })
        assertEquals("ja", payload.get("source").asString)
        assertEquals("en", payload.get("target").asString)
        assertEquals("text", payload.get("format").asString)
        assertEquals(listOf(ViewerTranslationStage.TRANSLATING), stages)
    }

    @Test
    fun `phrase and character boundaries preserve every phrase exactly once`() = runTest {
        val cases = listOf(
            List(32) { "語-$it" } to listOf(32),
            List(33) { "語-$it" } to listOf(32, 1),
            List(5) { "${it}" + "語".repeat(999) } to listOf(5),
            List(6) { "${it}" + "語".repeat(999) } to listOf(5, 1),
        )
        cases.forEach { (phrases, sizes) ->
            val transport = RecordingTranslationTransport { body ->
                val batch = JsonParser.parseString(body).asJsonObject.getAsJsonArray("q")
                ViewerTranslationHttpResponse(200, """{"translations":$batch}""")
            }
            val result = GoogleViewerTextTranslator("https://translate.axor.dev", transport)
                .translateBatch(ViewerOcrLanguage.KOREAN, phrases + phrases.first()) {}
            val batches = transport.bodies.map { body ->
                JsonParser.parseString(body).asJsonObject.getAsJsonArray("q").map { it.asString }
            }
            assertEquals(sizes, batches.map(List<String>::size))
            assertEquals(phrases, batches.flatten())
            assertTrue(batches.all { it.size <= 32 && it.sumOf(String::length) <= 5_000 })
            assertEquals(phrases.associateWith { it }, result)
        }
    }

    @Test
    fun `rate limits and incomplete batches become retryable messages`() = runTest {
        val busy = GoogleViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(ViewerTranslationHttpResponse(429, "")),
        )
        val incomplete = GoogleViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(
                ViewerTranslationHttpResponse(200, """{"translations":[]}"""),
            ),
        )

        val busyFailure = runCatching {
            busy.translateBatch(ViewerOcrLanguage.KOREAN, listOf("안녕하세요")) {}
        }.exceptionOrNull()
        val incompleteFailure = runCatching {
            incomplete.translateBatch(ViewerOcrLanguage.CHINESE, listOf("你好")) {}
        }.exceptionOrNull()

        assertTrue(busyFailure is IOException)
        assertEquals("Translation server is busy · Tap the phrase to retry", busyFailure?.message)
        assertTrue(incompleteFailure is IOException)
        assertEquals(
            "Translation server returned incomplete text · Tap the phrase to retry",
            incompleteFailure?.message,
        )
    }

    @Test
    fun `translator rejects cleartext endpoints and oversized phrases before network work`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoogleViewerTextTranslator("http://translate.axor.dev")
        }
        val translator = GoogleViewerTextTranslator(
            "https://translate.axor.dev",
            RecordingTranslationTransport(ViewerTranslationHttpResponse(200, "{}")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest {
                translator.translateBatch(
                    ViewerOcrLanguage.JAPANESE,
                    listOf("x".repeat(MAX_TRANSLATION_CHARACTERS + 1)),
                ) {}
            }
        }
    }
}

private class RecordingTranslationTransport(
    private val responder: (String) -> ViewerTranslationHttpResponse,
) : ViewerTranslationHttpTransport {
    constructor(response: ViewerTranslationHttpResponse) : this({ response })

    var endpoint: String = ""
    val bodies = mutableListOf<String>()

    override suspend fun postJson(endpoint: String, body: String): ViewerTranslationHttpResponse {
        this.endpoint = endpoint
        bodies += body
        return responder(body)
    }
}
