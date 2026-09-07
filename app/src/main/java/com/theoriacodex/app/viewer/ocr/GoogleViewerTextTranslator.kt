package com.theoriacodex.app.viewer.ocr

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.repository.ViewerOcrLanguage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import com.theoriacodex.sources.http.executeCancellableHttpConnection

internal data class ViewerTranslationHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface ViewerTranslationHttpTransport {
    suspend fun postJson(endpoint: String, body: String): ViewerTranslationHttpResponse
}

/** Sends deduplicated current-image phrases through the narrow Google translation gateway. */
internal class GoogleViewerTextTranslator(
    baseUrl: String = DEFAULT_TRANSLATION_BASE_URL,
    private val transport: ViewerTranslationHttpTransport = HttpUrlConnectionTranslationTransport(),
) : ViewerTextTranslator {
    private val endpoint = translationBatchEndpoint(baseUrl)

    override suspend fun translateBatch(
        language: ViewerOcrLanguage,
        sourceTexts: List<String>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String> {
        val phrases = sourceTexts.map(String::trim).filter(String::isNotEmpty).distinct()
        if (phrases.isEmpty()) return emptyMap()
        phrases.forEach { phrase ->
            require(phrase.length <= MAX_TRANSLATION_CHARACTERS) { "Phrase is too long to translate" }
        }
        onStage(ViewerTranslationStage.TRANSLATING)
        return buildMap {
            phrases.boundedTranslationBatches().forEach { batch ->
                putAll(translateOneBatch(language, batch))
            }
        }
    }

    private suspend fun translateOneBatch(
        language: ViewerOcrLanguage,
        phrases: List<String>,
    ): Map<String, String> {
        val response = try {
            transport.postJson(
                endpoint = endpoint,
                body = translationRequestBody(language, phrases),
            )
        } catch (failure: IOException) {
            throw IOException("Translation server unavailable · Tap the phrase to retry", failure)
        }
        if (response.statusCode !in HTTP_SUCCESS_RANGE) {
            val message = if (response.statusCode == HTTP_TOO_MANY_REQUESTS) {
                "Translation server is busy · Tap the phrase to retry"
            } else {
                "Translation server unavailable · Tap the phrase to retry"
            }
            throw IOException(message)
        }
        return parseTranslatedTexts(response.body, phrases)
    }
}

internal class HttpUrlConnectionTranslationTransport(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : ViewerTranslationHttpTransport {
    override suspend fun postJson(endpoint: String, body: String): ViewerTranslationHttpResponse {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        return executeCancellableHttpConnection(connection) {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in HTTP_SUCCESS_RANGE) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            ViewerTranslationHttpResponse(
                statusCode = statusCode,
                body = responseStream?.use(::readBoundedUtf8).orEmpty(),
            )
        }
    }
}

internal interface ViewerTextTranslator {
    suspend fun translateBatch(
        language: ViewerOcrLanguage,
        sourceTexts: List<String>,
        onStage: (ViewerTranslationStage) -> Unit,
    ): Map<String, String>
}

private fun translationBatchEndpoint(baseUrl: String): String {
    val uri = URI(baseUrl.trimEnd('/'))
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "Translation server must use HTTPS"
    }
    return "$uri/translate-batch"
}

private fun translationRequestBody(language: ViewerOcrLanguage, phrases: List<String>): String {
    return JsonObject().apply {
        add("q", JsonArray().apply { phrases.forEach(::add) })
        addProperty("source", language.gatewayCode())
        addProperty("target", "en")
        addProperty("format", "text")
    }.toString()
}

private fun parseTranslatedTexts(body: String, phrases: List<String>): Map<String, String> {
    val translations = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonArray("translations")
            ?.map { element -> element.asString.trim() }
            ?.takeIf { values -> values.size == phrases.size && values.all(String::isNotBlank) }
    }.getOrNull() ?: throw IOException(
        "Translation server returned incomplete text · Tap the phrase to retry",
    )
    return phrases.zip(translations).toMap()
}

private fun List<String>.boundedTranslationBatches(): List<List<String>> {
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var currentCharacters = 0
    forEach { phrase ->
        if (
            current.isNotEmpty() &&
            (current.size >= MAX_TRANSLATION_PHRASES_PER_BATCH ||
                currentCharacters + phrase.length > MAX_TRANSLATION_BATCH_CHARACTERS)
        ) {
            batches += current
            current = mutableListOf()
            currentCharacters = 0
        }
        current += phrase
        currentCharacters += phrase.length
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

private fun readBoundedUtf8(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
    var totalBytes = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        totalBytes += read
        if (totalBytes > MAX_RESPONSE_BYTES) {
            throw IOException("Translation server response was too large")
        }
        output.write(buffer, 0, read)
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private fun ViewerOcrLanguage.gatewayCode(): String = when (this) {
    ViewerOcrLanguage.JAPANESE -> "ja"
    ViewerOcrLanguage.CHINESE -> "zh-Hans"
    ViewerOcrLanguage.KOREAN -> "ko"
}

internal const val MAX_TRANSLATION_CHARACTERS = 1_000
internal const val MAX_TRANSLATION_PHRASES_PER_BATCH = 32
internal const val MAX_TRANSLATION_BATCH_CHARACTERS = 5_000
private const val DEFAULT_TRANSLATION_BASE_URL = "https://translate.axor.dev"
private const val CONNECT_TIMEOUT_MS = 4_000
private const val READ_TIMEOUT_MS = 12_000
private const val MAX_RESPONSE_BYTES = 64 * 1_024
private const val RESPONSE_BUFFER_BYTES = 4 * 1_024
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SUCCESS_RANGE = 200..299
