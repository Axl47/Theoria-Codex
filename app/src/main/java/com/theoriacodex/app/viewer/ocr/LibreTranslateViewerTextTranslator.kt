package com.theoriacodex.app.viewer.ocr

import com.google.gson.JsonParser
import com.theoriacodex.data.repository.ViewerOcrLanguage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ViewerTranslationHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface ViewerTranslationHttpTransport {
    suspend fun postForm(endpoint: String, body: String): ViewerTranslationHttpResponse
}

/** Sends only the tapped OCR phrase and its source language to the self-hosted translator. */
internal class LibreTranslateViewerTextTranslator(
    baseUrl: String = DEFAULT_TRANSLATION_BASE_URL,
    private val transport: ViewerTranslationHttpTransport = HttpUrlConnectionTranslationTransport(),
) : ViewerTextTranslator {
    private val endpoint = libreTranslateEndpoint(baseUrl)

    override suspend fun translate(
        language: ViewerOcrLanguage,
        text: String,
        onStage: (ViewerTranslationStage) -> Unit,
    ): String {
        val phrase = text.trim()
        require(phrase.isNotEmpty()) { "Nothing to translate" }
        require(phrase.length <= MAX_TRANSLATION_CHARACTERS) { "Phrase is too long to translate" }

        onStage(ViewerTranslationStage.TRANSLATING)
        val response = try {
            transport.postForm(
                endpoint = endpoint,
                body = translationRequestBody(language, phrase),
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
        return parseTranslatedText(response.body)
    }
}

internal class HttpUrlConnectionTranslationTransport(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : ViewerTranslationHttpTransport {
    override suspend fun postForm(endpoint: String, body: String): ViewerTranslationHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
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
            } finally {
                connection.disconnect()
            }
        }
}

private fun libreTranslateEndpoint(baseUrl: String): String {
    val uri = URI(baseUrl.trimEnd('/'))
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "Translation server must use HTTPS"
    }
    return "$uri/translate"
}

private fun translationRequestBody(language: ViewerOcrLanguage, text: String): String {
    return listOf(
        "q" to text,
        "source" to language.libreTranslateCode(),
        "target" to "en",
        "format" to "text",
    ).joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
}

private fun parseTranslatedText(body: String): String {
    val translated = runCatching {
        JsonParser.parseString(body)
            .asJsonObject
            .get("translatedText")
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
    return translated ?: throw IOException(
        "Translation server returned no text · Tap the phrase to retry",
    )
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

private fun ViewerOcrLanguage.libreTranslateCode(): String = when (this) {
    ViewerOcrLanguage.JAPANESE -> "ja"
    ViewerOcrLanguage.CHINESE -> "zh-Hans"
    ViewerOcrLanguage.KOREAN -> "ko"
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

internal const val MAX_TRANSLATION_CHARACTERS = 1_000
private const val DEFAULT_TRANSLATION_BASE_URL = "https://translate.axor.dev"
private const val CONNECT_TIMEOUT_MS = 4_000
private const val READ_TIMEOUT_MS = 12_000
private const val MAX_RESPONSE_BYTES = 64 * 1_024
private const val RESPONSE_BUFFER_BYTES = 4 * 1_024
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SUCCESS_RANGE = 200..299
