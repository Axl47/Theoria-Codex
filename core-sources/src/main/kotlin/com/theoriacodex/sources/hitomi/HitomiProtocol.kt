package com.theoriacodex.sources.hitomi

import com.theoriacodex.domain.diagnostics.OffMainWork
import com.theoriacodex.sources.common.asIntOrNull
import com.theoriacodex.sources.common.asStringOrNull
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class HitomiAutocompleteEntry(
    val name: String,
    val count: Int,
    val namespace: String,
)

class HitomiProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

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

    val namespaces: Set<String> = HITOMI_FACETS.namespaces

    val autocompleteScopes: Set<String> = setOf("global") + namespaces

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
        val rows = parseJson(body, "autocomplete response") as? JsonArray
            ?: throw HitomiProtocolException("Hitomi autocomplete response was not an array")

        return rows.take(maxResults).mapIndexed { index, element ->
            val row = element as? JsonArray
                ?: throw HitomiProtocolException("Hitomi autocomplete row $index was not an array")
            if (row.size < 3) {
                throw HitomiProtocolException("Hitomi autocomplete row $index was truncated")
            }
            val name = row[0].asStringOrNull()?.trim().orEmpty()
            val count = row[1].asIntOrNull()
                ?: throw HitomiProtocolException("Hitomi autocomplete row $index had an invalid count")
            val namespace = row[2].asStringOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
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
        val assignmentBody = galleryAssignmentBody(script)
            ?: throw HitomiProtocolException("Hitomi gallery response was not a galleryinfo assignment")
        return parseJson(assignmentBody, "gallery assignment") as? JsonObject
            ?: throw HitomiProtocolException("Hitomi gallery assignment did not contain an object")
    }

    private fun encodeAutocompleteCharacter(character: String): String = when (character) {
        " " -> "_"
        "/" -> "slash"
        "." -> "dot"
        else -> URLEncoder.encode(character, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun parseJson(body: String, label: String) = try {
        OffMainWork.note(OffMainWork.SOURCE_PARSE)
        Json.parseToJsonElement(body)
    } catch (error: RuntimeException) {
        throw HitomiProtocolException("Hitomi $label was malformed JSON: ${error.message}", error)
    }

    /**
     * Extracts the provider's JavaScript assignment without relying on regex features whose
     * implementation differs between the desktop JVM and Android's ICU regex engine.
     */
    private fun galleryAssignmentBody(script: String): String? {
        val normalized = script.trim().removeSuffix(";").trimEnd()
        val equalsIndex = normalized.indexOf('=')
        if (equalsIndex <= 0) return null

        val declaration = normalized.substring(0, equalsIndex)
            .trim()
            .split(' ', '\t', '\r', '\n')
            .filter(String::isNotEmpty)
        if (
            declaration.size != 2 ||
            declaration[0] !in GALLERY_DECLARATION_KEYWORDS ||
            declaration[1] != "galleryinfo"
        ) {
            return null
        }

        return normalized.substring(equalsIndex + 1)
            .trim()
            .takeIf { body -> body.startsWith('{') && body.endsWith('}') }
    }

    private val GALLERY_DECLARATION_KEYWORDS = setOf("var", "let", "const")
}
