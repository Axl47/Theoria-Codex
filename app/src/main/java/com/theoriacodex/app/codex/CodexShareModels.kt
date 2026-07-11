package com.theoriacodex.app.codex

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey

internal data class CodexShareFile(
    @field:SerializedName("version")
    val version: Int? = 1,
    @field:SerializedName("title")
    val title: String? = null,
    @field:SerializedName("posts")
    val posts: List<CodexSharePost>? = null,
)

internal data class CodexSharePost(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("sourcePostId")
    val sourcePostId: String? = null,
    @field:SerializedName("snapshot")
    val snapshot: CodexSharePostSnapshot? = null,
)

internal data class CodexSharePostSnapshot(
    @field:SerializedName("preview")
    val preview: CodexShareImageRef? = null,
    @field:SerializedName("full")
    val full: CodexShareImageRef? = null,
    @field:SerializedName("media")
    val media: List<CodexShareImageRef?>? = null,
    @field:SerializedName("pageUrl")
    val pageUrl: String? = null,
    @field:SerializedName("width")
    val width: Int? = null,
    @field:SerializedName("height")
    val height: Int? = null,
    @field:SerializedName("canonicalTags")
    val canonicalTags: List<String?>? = null,
    @field:SerializedName("rawTags")
    val rawTags: List<String?>? = null,
    @field:SerializedName("taxonomy")
    val taxonomy: List<CodexShareTaxonomyTerm?>? = null,
    @field:SerializedName("authorName")
    val authorName: String? = null,
    @field:SerializedName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
    @field:SerializedName("title")
    val title: String? = null,
    @field:SerializedName("creatorProfile")
    val creatorProfile: CodexShareCreatorProfile? = null,
    @field:SerializedName("creatorProfiles")
    val creatorProfiles: List<CodexShareCreatorProfile?>? = null,
    @field:SerializedName("durationMs")
    val durationMs: Long? = null,
    @field:SerializedName("mediaCount")
    val mediaCount: Int? = null,
)

internal data class CodexShareImageRef(
    @field:SerializedName("url")
    val url: String? = null,
    @Transient
    val localPath: String? = null,
    @field:SerializedName("mime")
    val mime: String? = null,
    @field:SerializedName("progressiveUrls")
    val progressiveUrls: List<String?>? = null,
    @field:SerializedName("isAnimated")
    val isAnimated: Boolean? = null,
)

internal data class CodexShareTaxonomyTerm(
    @field:SerializedName("value")
    val value: String? = null,
    @field:SerializedName("facet")
    val facet: String? = null,
    @field:SerializedName("sourceNamespace")
    val sourceNamespace: String? = null,
)

internal data class CodexShareCreatorProfile(
    @field:SerializedName("source")
    val source: String? = null,
    @field:SerializedName("displayName")
    val displayName: String? = null,
    @field:SerializedName("profileId")
    val profileId: String? = null,
    @field:SerializedName("profileUrl")
    val profileUrl: String? = null,
    @field:SerializedName("uploadsQuery")
    val uploadsQuery: String? = null,
)

internal fun buildCodexShareFile(title: String, posts: List<Post>): CodexShareFile {
    return CodexShareFile(
        version = CODEX_SHARE_VERSION,
        title = title,
        posts = posts.map { post ->
            CodexSharePost(
                source = post.id.source.name,
                sourcePostId = post.id.sourcePostId,
                snapshot = post.toCodexShareSnapshot(),
            )
        },
    )
}

internal fun parseCodexShareFile(raw: String): CodexShareFile? {
    val root = runCatching { JsonParser.parseString(raw) }
        .getOrNull()
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: return null
    return CodexShareFile(
        version = root.intOrNull("version"),
        title = root.stringOrNull("title"),
        posts = root.postListOrNull("posts"),
    )
}

internal fun codexSharePostId(post: CodexSharePost): PostId? {
    val source = post.source
        ?.trim()
        ?.uppercase()
        ?.let { value -> runCatching { SourceKey.valueOf(value) }.getOrNull() }
        ?: return null
    val sourcePostId = post.sourcePostId?.trim().orEmpty()
    if (sourcePostId.isBlank()) return null
    return PostId(source = source, sourcePostId = sourcePostId)
}

internal fun codexSharePostSnapshot(post: CodexSharePost): Post? {
    val postId = codexSharePostId(post) ?: return null
    val snapshot = post.snapshot ?: return null
    val preview = snapshot.preview?.toImageRef() ?: return null
    val canonicalTags = snapshot.canonicalTags.normalizedStrings()
    val taxonomy = snapshot.taxonomy
        ?.mapNotNull { term -> term?.toPostTaxonomyTerm() }
        ?.distinct()
        ?: canonicalTags.map { tag -> PostTaxonomyTerm(value = tag) }
    val effectiveCanonicalTags = canonicalTags.ifEmpty {
        taxonomy.map(PostTaxonomyTerm::value).distinct()
    }
    val rawTags = snapshot.rawTags.normalizedStrings().ifEmpty { effectiveCanonicalTags }
    val primaryCreator = snapshot.creatorProfile?.toCreatorProfile(defaultSource = postId.source)
    val creatorProfiles = snapshot.creatorProfiles
        .orEmpty()
        .mapNotNull { creator -> creator?.toCreatorProfile(defaultSource = postId.source) }
        .let { creators ->
            listOfNotNull(primaryCreator)
                .plus(creators)
                .distinct()
        }
    val effectivePrimaryCreator = primaryCreator ?: creatorProfiles.firstOrNull()

    return Post(
        id = postId,
        preview = preview,
        full = snapshot.full?.toImageRef(),
        media = snapshot.media.orEmpty().mapNotNull { media -> media?.toImageRef() },
        pageUrl = snapshot.pageUrl.normalizedOptionalString(),
        width = snapshot.width?.takeIf { it > 0 },
        height = snapshot.height?.takeIf { it > 0 },
        canonicalTags = effectiveCanonicalTags,
        rawTags = rawTags,
        taxonomy = taxonomy,
        authorName = snapshot.authorName.normalizedOptionalString(),
        createdAtEpochMs = snapshot.createdAtEpochMs?.takeIf { it >= 0L },
        title = snapshot.title.normalizedOptionalString(),
        creatorProfile = effectivePrimaryCreator,
        creatorProfiles = creatorProfiles,
        durationMs = snapshot.durationMs?.takeIf { it >= 0L },
        mediaCount = snapshot.mediaCount?.takeIf { it > 0 },
    )
}

internal fun selectCodexShareEntries(
    posts: List<CodexSharePost>,
): List<Pair<CodexSharePost, PostId>> {
    val selectedById = linkedMapOf<String, Pair<CodexSharePost, PostId>>()
    posts.forEach { entry ->
        val postId = codexSharePostId(entry) ?: return@forEach
        val key = "${postId.source.name}:${postId.sourcePostId}"
        val existing = selectedById[key]
        if (
            existing == null ||
            (codexSharePostSnapshot(existing.first) == null && codexSharePostSnapshot(entry) != null)
        ) {
            selectedById[key] = entry to postId
        }
    }
    return selectedById.values.toList()
}

internal suspend fun resolveCodexShareImportPost(
    entry: CodexSharePost,
    resolvedFromSource: Post?,
    storedPost: suspend () -> Post?,
): Post? {
    return resolvedFromSource
        ?: codexSharePostSnapshot(entry)
        ?: storedPost()
}

internal fun sanitizeCodexExportName(name: String): String {
    val normalized = name
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    return normalized.ifBlank { "codex" }
}

private fun Post.toCodexShareSnapshot(): CodexSharePostSnapshot {
    return CodexSharePostSnapshot(
        preview = preview.toCodexShareImageRef(),
        full = full?.toCodexShareImageRef(),
        media = media.map(ImageRef::toCodexShareImageRef),
        pageUrl = pageUrl,
        width = width,
        height = height,
        canonicalTags = canonicalTags,
        rawTags = rawTags,
        taxonomy = taxonomy.map(PostTaxonomyTerm::toCodexShareTaxonomyTerm),
        authorName = authorName,
        createdAtEpochMs = createdAtEpochMs,
        title = title,
        creatorProfile = creatorProfile?.toCodexShareCreatorProfile(),
        creatorProfiles = creatorProfiles.map(CreatorProfile::toCodexShareCreatorProfile),
        durationMs = durationMs,
        mediaCount = mediaCount,
    )
}

private fun ImageRef.toCodexShareImageRef(): CodexShareImageRef {
    return CodexShareImageRef(
        url = url,
        localPath = null,
        mime = mime,
        progressiveUrls = progressiveUrls,
        isAnimated = isAnimated,
    )
}

private fun CodexShareImageRef.toImageRef(): ImageRef? {
    val normalizedUrl = url.normalizedOptionalString()
    if (normalizedUrl == null) return null
    return ImageRef(
        url = normalizedUrl,
        localPath = null,
        mime = mime.normalizedOptionalString(),
        progressiveUrls = progressiveUrls.normalizedStrings(),
        isAnimated = isAnimated ?: false,
    )
}

private fun PostTaxonomyTerm.toCodexShareTaxonomyTerm(): CodexShareTaxonomyTerm {
    return CodexShareTaxonomyTerm(
        value = value,
        facet = facet.name,
        sourceNamespace = sourceNamespace,
    )
}

private fun CodexShareTaxonomyTerm.toPostTaxonomyTerm(): PostTaxonomyTerm? {
    val normalizedValue = value.normalizedOptionalString() ?: return null
    val normalizedFacet = facet.normalizedOptionalString()
        ?.uppercase()
        ?.let { value -> runCatching { SearchFacet.valueOf(value) }.getOrNull() }
        ?: if (facet.isNullOrBlank()) SearchFacet.TAG else return null
    return PostTaxonomyTerm(
        value = normalizedValue,
        facet = normalizedFacet,
        sourceNamespace = sourceNamespace.normalizedOptionalString(),
    )
}

private fun CreatorProfile.toCodexShareCreatorProfile(): CodexShareCreatorProfile {
    return CodexShareCreatorProfile(
        source = source.name,
        displayName = displayName,
        profileId = profileId,
        profileUrl = profileUrl,
        uploadsQuery = uploadsQuery,
    )
}

private fun CodexShareCreatorProfile.toCreatorProfile(defaultSource: SourceKey): CreatorProfile? {
    val normalizedDisplayName = displayName.normalizedOptionalString() ?: return null
    val sourceName = source.normalizedOptionalString()
    val normalizedSource = if (sourceName == null) {
        defaultSource
    } else {
        runCatching { SourceKey.valueOf(sourceName.uppercase()) }.getOrNull() ?: return null
    }
    return CreatorProfile(
        source = normalizedSource,
        displayName = normalizedDisplayName,
        profileId = profileId.normalizedOptionalString(),
        profileUrl = profileUrl.normalizedOptionalString(),
        uploadsQuery = uploadsQuery.normalizedOptionalString(),
    )
}

private fun List<String?>?.normalizedStrings(): List<String> {
    return orEmpty()
        .mapNotNull(String?::normalizedOptionalString)
        .distinct()
}

private fun String?.normalizedOptionalString(): String? {
    return this?.trim()?.takeIf(String::isNotBlank)
}

private fun JsonObject.postListOrNull(name: String): List<CodexSharePost>? {
    if (!has(name) || get(name).isJsonNull) return null
    val value = get(name)
    if (!value.isJsonArray) return emptyList()
    return value.asJsonArray.mapNotNull { element ->
        element.objectOrNull()?.toCodexSharePost()
    }
}

private fun JsonObject.toCodexSharePost(): CodexSharePost {
    return CodexSharePost(
        source = stringOrNull("source"),
        sourcePostId = stringOrNull("sourcePostId"),
        snapshot = objectOrNull("snapshot")?.toCodexSharePostSnapshot(),
    )
}

private fun JsonObject.toCodexSharePostSnapshot(): CodexSharePostSnapshot {
    return CodexSharePostSnapshot(
        preview = objectOrNull("preview")?.toCodexShareImageRef(),
        full = objectOrNull("full")?.toCodexShareImageRef(),
        media = objectListOrNull("media") { value -> value.toCodexShareImageRef() },
        pageUrl = stringOrNull("pageUrl"),
        width = intOrNull("width"),
        height = intOrNull("height"),
        canonicalTags = stringListOrNull("canonicalTags"),
        rawTags = stringListOrNull("rawTags"),
        taxonomy = objectListOrNull("taxonomy") { value -> value.toCodexShareTaxonomyTerm() },
        authorName = stringOrNull("authorName"),
        createdAtEpochMs = longOrNull("createdAtEpochMs"),
        title = stringOrNull("title"),
        creatorProfile = objectOrNull("creatorProfile")?.toCodexShareCreatorProfile(),
        creatorProfiles = objectListOrNull("creatorProfiles") { value ->
            value.toCodexShareCreatorProfile()
        },
        durationMs = longOrNull("durationMs"),
        mediaCount = intOrNull("mediaCount"),
    )
}

private fun JsonObject.toCodexShareImageRef(): CodexShareImageRef {
    return CodexShareImageRef(
        url = stringOrNull("url"),
        localPath = null,
        mime = stringOrNull("mime"),
        progressiveUrls = stringListOrNull("progressiveUrls"),
        isAnimated = booleanOrNull("isAnimated"),
    )
}

private fun JsonObject.toCodexShareTaxonomyTerm(): CodexShareTaxonomyTerm {
    return CodexShareTaxonomyTerm(
        value = stringOrNull("value"),
        facet = stringOrNull("facet"),
        sourceNamespace = stringOrNull("sourceNamespace"),
    )
}

private fun JsonObject.toCodexShareCreatorProfile(): CodexShareCreatorProfile {
    return CodexShareCreatorProfile(
        source = stringOrNull("source"),
        displayName = stringOrNull("displayName"),
        profileId = stringOrNull("profileId"),
        profileUrl = stringOrNull("profileUrl"),
        uploadsQuery = stringOrNull("uploadsQuery"),
    )
}

private fun JsonObject.objectOrNull(name: String): JsonObject? {
    return get(name)?.objectOrNull()
}

private fun JsonElement.objectOrNull(): JsonObject? {
    return takeIf(JsonElement::isJsonObject)?.asJsonObject
}

private fun JsonObject.stringOrNull(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString
}

private fun JsonObject.intOrNull(name: String): Int? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return runCatching { value.asInt }.getOrNull()
}

private fun JsonObject.longOrNull(name: String): Long? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return runCatching { value.asLong }.getOrNull()
}

private fun JsonObject.booleanOrNull(name: String): Boolean? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return null
    return value.asBoolean
}

private fun JsonObject.stringListOrNull(name: String): List<String?>? {
    return listOrNull(name) { value ->
        value.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { primitive -> primitive.isString }
            ?.asString
    }
}

private fun <T> JsonObject.objectListOrNull(
    name: String,
    transform: (JsonObject) -> T,
): List<T?>? {
    return listOrNull(name) { value -> value.objectOrNull()?.let(transform) }
}

private fun <T> JsonObject.listOrNull(
    name: String,
    transform: (JsonElement) -> T?,
): List<T?>? {
    if (!has(name) || get(name).isJsonNull) return null
    val value = get(name)
    if (!value.isJsonArray) return emptyList()
    return value.asJsonArray.map(transform)
}

private const val CODEX_SHARE_VERSION = 2
