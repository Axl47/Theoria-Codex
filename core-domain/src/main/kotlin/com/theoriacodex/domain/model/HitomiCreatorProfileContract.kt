package com.theoriacodex.domain.model

import java.util.Locale

const val HITOMI_ARTIST_QUERY_PREFIX = "artist:"
const val HITOMI_ARTIST_IDENTITY_MAX_CODE_POINTS = 256

fun canonicalHitomiArtistIdentity(value: String): String? {
    if (!value.hasWellFormedUnicode()) return null
    if (value.any { character ->
            character == '/' || character == '\\' || character.isISOControl()
        }
    ) {
        return null
    }
    val normalized = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(HITOMI_ARTIST_WHITESPACE, " ")
    val codePointCount = normalized.codePointCount(0, normalized.length)
    return normalized.takeIf { identity ->
        identity.isNotBlank() &&
            codePointCount <= HITOMI_ARTIST_IDENTITY_MAX_CODE_POINTS
    }
}

fun CreatorProfile.canonicalHitomiArtistIdentity(): String? {
    if (source != SourceKey.HITOMI) return null
    val rawIdentity = profileId ?: return null
    val canonicalIdentity = canonicalHitomiArtistIdentity(rawIdentity) ?: return null
    if (rawIdentity != canonicalIdentity) return null
    if (uploadsQuery != "$HITOMI_ARTIST_QUERY_PREFIX$canonicalIdentity") return null
    return canonicalIdentity
}

private fun String.hasWellFormedUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) {
                    return false
                }
                index += 2
            }

            Character.isLowSurrogate(character) -> return false
            else -> index += 1
        }
    }
    return true
}

private val HITOMI_ARTIST_WHITESPACE = Regex("\\s+")
