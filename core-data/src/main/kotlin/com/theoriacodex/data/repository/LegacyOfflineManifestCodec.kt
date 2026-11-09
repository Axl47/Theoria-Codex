package com.theoriacodex.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.VideoVariant

/**
 * Reads a post from a version 2 offline manifest. Those were written by reflective Gson over the domain
 * classes, so release builds used R8's renamed keys, and R8 also strips the generic types reflective
 * Gson would need to read them back. Each field therefore lists its plain name (debug builds) and the
 * name shipped release builds wrote. These names are frozen: they describe files already on devices.
 *
 * Returns null for a post without an identity or preview. Malformed values throw, like any bad manifest.
 */
internal fun decodeLegacyOfflinePost(element: JsonElement): Post? {
    val root = element.asJsonObject
    val id = root.field("id", "a")?.asJsonObject ?: return null
    val source = id.field("source", "a")?.asString?.let(SourceKey::parse) ?: return null
    val sourcePostId = id.field("sourcePostId", "b")?.asString ?: return null
    val preview = root.field("preview", "b")?.asJsonObject ?: return null
    val canonicalTags = root.strings("canonicalTags", "h")
    val creatorProfile = root.field("creatorProfile", "m")?.asJsonObject?.toCreatorProfile()
    return Post(
        id = PostId(source, sourcePostId),
        preview = preview.toImageRef(),
        full = root.field("full", "c")?.asJsonObject?.toImageRef(),
        media = root.objects("media", "d").map { it.toImageRef() },
        pageUrl = root.field("pageUrl", "e")?.asString,
        width = root.field("width", "f")?.asInt,
        height = root.field("height", "g")?.asInt,
        canonicalTags = canonicalTags,
        rawTags = root.strings("rawTags", "i"),
        authorName = root.field("authorName", "j")?.asString,
        createdAtEpochMs = root.field("createdAtEpochMs", "k")?.asLong,
        title = root.field("title", "l")?.asString,
        creatorProfile = creatorProfile,
        durationMs = root.field("durationMs", "n")?.asLong,
        mediaCount = root.field("mediaCount", "o")?.asInt,
        // Older manifests predate these lists, so they take the constructor's derived values.
        taxonomy = if (root.field("taxonomy", "p") == null) {
            canonicalTags.map { value -> PostTaxonomyTerm(value = value) }
        } else {
            root.objects("taxonomy", "p").mapNotNull { it.toTaxonomyTerm() }
        },
        creatorProfiles = if (root.field("creatorProfiles", "q") == null) {
            listOfNotNull(creatorProfile)
        } else {
            root.objects("creatorProfiles", "q").mapNotNull { it.toCreatorProfile() }
        },
    )
}

private fun JsonObject.toImageRef() = ImageRef(
    url = field("url", "a")?.asString,
    localPath = field("localPath", "b")?.asString,
    mime = field("mime", "c")?.asString,
    progressiveUrls = strings("progressiveUrls", "d"),
    isAnimated = field("isAnimated", "e")?.asBoolean ?: false,
    videoVariants = objects("videoVariants", "f").mapNotNull { it.toVideoVariant() },
)

private fun JsonObject.toVideoVariant(): VideoVariant? {
    val url = field("url", "a")?.asString ?: return null
    return VideoVariant(
        url = url,
        height = field("height", "b")?.asInt,
        original = field("original", "c")?.asBoolean ?: false,
        mime = field("mime", "d")?.asString,
    )
}

private fun JsonObject.toCreatorProfile(): CreatorProfile? {
    val source = field("source", "a")?.asString?.let(SourceKey::parse) ?: return null
    return CreatorProfile(
        source = source,
        displayName = field("displayName", "b")?.asString ?: return null,
        profileId = field("profileId", "c")?.asString,
        profileUrl = field("profileUrl", "d")?.asString,
        uploadsQuery = field("uploadsQuery", "e")?.asString,
    )
}

private fun JsonObject.toTaxonomyTerm(): PostTaxonomyTerm? {
    val value = field("value", "a")?.asString ?: return null
    return PostTaxonomyTerm(
        value = value,
        facet = field("facet", "b")?.asString
            ?.let { name -> SearchFacet.entries.firstOrNull { it.name == name } }
            ?: SearchFacet.TAG,
        sourceNamespace = field("sourceNamespace", "c")?.asString,
        count = field("count", "d")?.asInt,
        translation = field("translation", "e")?.asString,
    )
}

private fun JsonObject.field(name: String, shippedName: String): JsonElement? =
    (get(name) ?: get(shippedName))?.takeUnless(JsonElement::isJsonNull)

private fun JsonObject.strings(name: String, shippedName: String): List<String> =
    field(name, shippedName)?.asJsonArray?.map { it.asString }.orEmpty()

private fun JsonObject.objects(name: String, shippedName: String): List<JsonObject> =
    field(name, shippedName)?.asJsonArray?.map { it.asJsonObject }.orEmpty()
