package com.theoriacodex.sources.http

data class SourceHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
)

data class SourceByteRange(
    val startInclusive: Long,
    val endInclusive: Long,
) {
    init {
        require(startInclusive >= 0L) { "Byte range start must be non-negative" }
        require(endInclusive >= startInclusive) { "Byte range end must not precede its start" }
    }

    val headerValue: String
        get() = "bytes=$startInclusive-$endInclusive"
}

data class SourceByteResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
)

class SourceHttpBodyTooLargeException(
    val maxBodyBytes: Int,
) : IllegalStateException("Source HTTP response exceeded the $maxBodyBytes byte limit")

const val DEFAULT_MAX_SOURCE_BINARY_BODY_BYTES: Int = 8 * 1024 * 1024

interface SourceHttpClient {
    suspend fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse

    suspend fun getBytes(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        range: SourceByteRange? = null,
        maxBodyBytes: Int = DEFAULT_MAX_SOURCE_BINARY_BODY_BYTES,
    ): SourceByteResponse {
        throw UnsupportedOperationException("Binary GET is not supported by this HTTP client")
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): SourceHttpResponse {
        throw UnsupportedOperationException("JSON POST is not supported by this HTTP client")
    }
}

internal fun Map<String, String>.withByteRange(range: SourceByteRange?): Map<String, String> {
    if (range == null) return this
    require(keys.none { it.equals("Range", ignoreCase = true) }) {
        "Pass either SourceByteRange or a Range header, not both"
    }
    // Android's HttpURLConnection transparently inflates gzip responses. A bounded range can end
    // in the middle of the compressed stream, producing EOF or making Content-Range describe
    // different bytes than the decoded body. Range consumers require byte-exact identity data.
    return this + mapOf(
        "Range" to range.headerValue,
        "Accept-Encoding" to "identity",
    )
}
