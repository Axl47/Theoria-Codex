package com.theoriacodex.sources.hitomi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.util.Locale

data class HitomiAutocompleteEntry(
    val name: String,
    val count: Int,
    val namespace: String,
)

class HitomiProtocolException(message: String) : IllegalArgumentException(message)

object HitomiProtocol {
    const val TAG_INDEX_BASE_URL: String = "https://tagindex.hitomi.la"
    const val DATA_BASE_URL: String = "https://ltn.gold-usergeneratedcontent.net"
    const val MAX_AUTOCOMPLETE_CODE_POINTS: Int = 64
    const val MAX_AUTOCOMPLETE_RESPONSE_CHARS: Int = 64 * 1024
    const val MAX_GALLERY_RESPONSE_CHARS: Int = 2 * 1024 * 1024

    val requestHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0",
        "Referer" to "https://hitomi.la/",
    )

    val autocompleteScopes: Set<String> = setOf(
        "global",
        "tag",
        "female",
        "male",
        "artist",
        "character",
        "series",
        "group",
        "type",
        "language",
    )

    val namespaces: Set<String> = setOf(
        "tag",
        "female",
        "male",
        "artist",
        "character",
        "series",
        "group",
        "type",
        "language",
    )

    fun autocompleteUrl(scope: String, term: String): String {
        val normalizedScope = scope.trim().lowercase(Locale.ROOT)
        if (normalizedScope !in autocompleteScopes) {
            throw HitomiProtocolException("Unsupported Hitomi autocomplete scope: $scope")
        }
        val normalizedTerm = term.trim().lowercase(Locale.ROOT)
        val codePoints = normalizedTerm.codePoints().toArray()
        if (codePoints.isEmpty() || codePoints.size > MAX_AUTOCOMPLETE_CODE_POINTS) {
            throw HitomiProtocolException(
                "Hitomi autocomplete terms must contain 1..$MAX_AUTOCOMPLETE_CODE_POINTS characters",
            )
        }
        val encodedPath = codePoints.joinToString("/") { codePoint ->
            encodeAutocompleteCharacter(String(Character.toChars(codePoint)))
        }
        return "$TAG_INDEX_BASE_URL/$normalizedScope/$encodedPath.json"
    }

    fun galleryUrl(galleryId: Int): String {
        if (galleryId <= 0) {
            throw HitomiProtocolException("Hitomi gallery ID must be positive")
        }
        return "$DATA_BASE_URL/galleries/$galleryId.js"
    }

    fun parseAutocomplete(body: String, maxResults: Int = 10): List<HitomiAutocompleteEntry> {
        if (maxResults !in 1..100) {
            throw HitomiProtocolException("Hitomi autocomplete maxResults must be within 1..100")
        }
        if (body.length > MAX_AUTOCOMPLETE_RESPONSE_CHARS) {
            throw HitomiProtocolException("Hitomi autocomplete response exceeded the size limit")
        }
        val rows = parseJson(body, "autocomplete response")
            .takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: throw HitomiProtocolException("Hitomi autocomplete response was not an array")

        return rows.take(maxResults).mapIndexed { index, element ->
            val row = element.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw HitomiProtocolException("Hitomi autocomplete row $index was not an array")
            if (row.size() < 3) {
                throw HitomiProtocolException("Hitomi autocomplete row $index was truncated")
            }
            val name = runCatching { row[0].asString.trim() }.getOrNull().orEmpty()
            val count = runCatching { row[1].asInt }.getOrNull()
                ?: throw HitomiProtocolException("Hitomi autocomplete row $index had an invalid count")
            val namespace = runCatching { row[2].asString.trim().lowercase(Locale.ROOT) }
                .getOrNull()
                .orEmpty()
            if (name.isBlank()) {
                throw HitomiProtocolException("Hitomi autocomplete row $index had a blank name")
            }
            if (count < 0) {
                throw HitomiProtocolException("Hitomi autocomplete row $index had a negative count")
            }
            if (namespace !in namespaces) {
                throw HitomiProtocolException(
                    "Hitomi autocomplete row $index used unsupported namespace: $namespace",
                )
            }
            HitomiAutocompleteEntry(name = name, count = count, namespace = namespace)
        }
    }

    fun parseGalleryAssignment(
        script: String,
        maxResponseChars: Int = MAX_GALLERY_RESPONSE_CHARS,
    ): JsonObject {
        if (maxResponseChars <= 0) {
            throw HitomiProtocolException("Gallery response size limit must be positive")
        }
        if (script.length > maxResponseChars) {
            throw HitomiProtocolException("Hitomi gallery response exceeded the size limit")
        }
        val match = GALLERY_ASSIGNMENT.matchEntire(script)
            ?: throw HitomiProtocolException("Hitomi gallery response was not a galleryinfo assignment")
        return parseJson(match.groupValues[1], "gallery assignment")
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw HitomiProtocolException("Hitomi gallery assignment did not contain an object")
    }

    private fun encodeAutocompleteCharacter(character: String): String = when (character) {
        " " -> "_"
        "/" -> "slash"
        "." -> "dot"
        else -> URLEncoder.encode(character, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun parseJson(body: String, label: String) = try {
        JsonParser.parseString(body)
    } catch (error: RuntimeException) {
        throw HitomiProtocolException("Hitomi $label was malformed JSON: ${error.message}")
    }

    private val GALLERY_ASSIGNMENT = Regex(
        pattern = """\s*(?:var|let|const)\s+galleryinfo\s*=\s*(\{.*})\s*;?\s*""",
        option = RegexOption.DOT_MATCHES_ALL,
    )
}
