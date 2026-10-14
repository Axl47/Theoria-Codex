package com.theoriacodex.sources.hitomi

import com.google.gson.JsonObject
import com.theoriacodex.domain.adapter.SourceFailureReason
import com.theoriacodex.domain.encoding.decodePercentEncodedUtf8Strict
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.HITOMI_ARTIST_QUERY_PREFIX
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.canonicalHitomiArtistIdentity
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ParsedHitomiGallery(
    val id: Int,
    val files: List<ParsedHitomiFile>,
    val taxonomy: List<PostTaxonomyTerm>,
    val creators: List<CreatorProfile>,
    val type: String?,
    val videoFilename: String?,
    val galleryPath: String?,
    val createdAtEpochMs: Long?,
    val title: String,
) {
    val hasPlayableVideo: Boolean get() = type == HITOMI_ANIME_TYPE && videoFilename != null
    val animated: Boolean get() = taxonomy.any { it.facet == SearchFacet.TAG && it.value.equals("animated", true) }
}

/** Pure metadata boundary. Transport, URL acquisition and sparse media publication stay in the adapter. */
internal fun parseHitomiGallery(json: JsonObject, fallbackGalleryId: Int): ParsedHitomiGallery = with(json) {
    val galleryId = stringValue("id")?.toIntOrNull() ?: fallbackGalleryId
    val files = arrayValue("files")?.mapIndexed { index, element ->
        element.asObjectOrNull()?.toHitomiMediaFile() ?: throw HitomiGalleryException(
            SourceFailureReason.PARSE, "Hitomi gallery $galleryId had invalid file metadata at index $index")
    }.orEmpty()
    val result = ParsedHitomiGallery(
        galleryId, files, parseTaxonomy(), parseCreatorProfiles(), stringValue("type")?.lowercase(Locale.ROOT),
        stringValue("videofilename"), stringValue("galleryurl"),
        parseHitomiDate(stringValue("date"), stringValue("datepublished")),
        stringValue("title") ?: stringValue("japanese_title") ?: "Hitomi #$galleryId",
    )
    if (result.files.isEmpty() && !result.hasPlayableVideo) throw HitomiGalleryException(
        SourceFailureReason.PARSE, "Hitomi gallery $galleryId did not contain usable media files")
    result
}

private fun JsonObject.parseCreatorProfiles(): List<CreatorProfile> {
    return arrayValue("artists")
        ?.mapNotNull { element ->
            val artist = element.asObjectOrNull() ?: return@mapNotNull null
            val rawDisplayName = artist.untrimmedStringValue("artist") ?: return@mapNotNull null
            val identity = canonicalHitomiArtistIdentity(rawDisplayName) ?: return@mapNotNull null
            CreatorProfile(
                source = SourceKey.HITOMI,
                displayName = rawDisplayName.trim(),
                profileId = identity,
                profileUrl = canonicalHitomiArtistUrl(
                    artistIdentity = identity,
                    providerRelativeUrl = artist.stringValue("url"),
                ),
                uploadsQuery = "$HITOMI_ARTIST_QUERY_PREFIX$identity",
            )
        }
        .orEmpty()
        .distinctBy { creator -> creator.profileId }
}

private fun canonicalHitomiArtistUrl(
    artistIdentity: String,
    providerRelativeUrl: String?,
): String {
    providerRelativeUrl
        ?.takeIf { value -> value.isSafeHitomiArtistPathFor(artistIdentity) }
        ?.let { value -> return "https://hitomi.la$value" }
    val encoded = URLEncoder.encode(artistIdentity, Charsets.UTF_8.name()).replace("+", "%20")
    return "https://hitomi.la/artist/$encoded-all.html"
}

private fun String.isSafeHitomiArtistPathFor(artistIdentity: String): Boolean {
    if ('+' in this) return false
    val parsed = runCatching { URI(this) }.getOrNull() ?: return false
    if (parsed.isAbsolute || parsed.host != null || parsed.rawQuery != null || parsed.rawFragment != null) {
        return false
    }
    val path = parsed.rawPath ?: return false
    if (!path.startsWith("/artist/") || !path.endsWith("-all.html")) return false
    val encodedSlug = path.removePrefix("/artist/").removeSuffix("-all.html")
    val decodedSlug = decodePercentEncodedUtf8Strict(encodedSlug) ?: return false
    return canonicalHitomiArtistIdentity(decodedSlug) == artistIdentity
}

private fun JsonObject.parseTaxonomy(): List<PostTaxonomyTerm> {
    val gallery = this
    return buildList {
        gallery.arrayValue("tags")?.forEach { element ->
            val tag = element.asObjectOrNull() ?: return@forEach
            val value = tag.stringValue("tag") ?: return@forEach
            val namespace = when {
                tag.truthy("female") -> HITOMI_FEMALE_NAMESPACE
                tag.truthy("male") -> HITOMI_MALE_NAMESPACE
                else -> HITOMI_TAG_NAMESPACE
            }
            add(PostTaxonomyTerm(value, SearchFacet.TAG, namespace))
        }
        gallery.addObjectTaxonomy(
            target = this,
            field = "artists",
            valueField = "artist",
            facet = SearchFacet.ARTIST,
            namespace = HITOMI_ARTIST_NAMESPACE,
        )
        gallery.addObjectTaxonomy(
            target = this,
            field = "characters",
            valueField = "character",
            facet = SearchFacet.CHARACTER,
            namespace = HITOMI_CHARACTER_NAMESPACE,
        )
        gallery.addObjectTaxonomy(
            target = this,
            field = "parodys",
            valueField = "parody",
            facet = SearchFacet.SERIES,
            namespace = HITOMI_SERIES_NAMESPACE,
        )
        gallery.addObjectTaxonomy(
            target = this,
            field = "groups",
            valueField = "group",
            facet = SearchFacet.GROUP,
            namespace = HITOMI_GROUP_NAMESPACE,
        )
        gallery.stringValue("type")?.let { value ->
            add(PostTaxonomyTerm(value, SearchFacet.TYPE, HITOMI_TYPE_NAMESPACE))
        }
        gallery.stringValue("language")?.let { value ->
            add(PostTaxonomyTerm(value, SearchFacet.LANGUAGE, HITOMI_LANGUAGE_NAMESPACE))
        }
    }.distinctBy { term -> "${term.facet.name}|${term.sourceNamespace.orEmpty().lowercase(Locale.ROOT)}|${term.value.trim().lowercase(Locale.ROOT)}" }
}

private fun JsonObject.addObjectTaxonomy(
    field: String,
    valueField: String,
    facet: SearchFacet,
    namespace: String,
    target: MutableList<PostTaxonomyTerm>,
) {
    arrayValue(field)?.forEach { element ->
        val value = element.asObjectOrNull()?.stringValue(valueField) ?: return@forEach
        target += PostTaxonomyTerm(value, facet, namespace)
    }
}

private fun parseHitomiDate(date: String?, published: String?): Long? {
    date?.let { raw ->
        listOf("yyyy-MM-dd HH:mm:ssX", "yyyy-MM-dd HH:mm:ssXX", "yyyy-MM-dd HH:mm:ssXXX")
            .firstNotNullOfOrNull { pattern ->
                runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.ofPattern(pattern)) }
                    .getOrNull()
            }
            ?.let { parsed -> return parsed.toInstant().toEpochMilli() }
    }
    return published?.let { raw ->
        runCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrNull()
    }
}

internal const val HITOMI_ANIME_TYPE = "anime"
internal const val HITOMI_TAG_NAMESPACE = "tag"
internal const val HITOMI_FEMALE_NAMESPACE = "female"
internal const val HITOMI_MALE_NAMESPACE = "male"
internal const val HITOMI_ARTIST_NAMESPACE = "artist"
internal const val HITOMI_CHARACTER_NAMESPACE = "character"
internal const val HITOMI_SERIES_NAMESPACE = "series"
internal const val HITOMI_GROUP_NAMESPACE = "group"
internal const val HITOMI_TYPE_NAMESPACE = "type"
internal const val HITOMI_LANGUAGE_NAMESPACE = "language"
