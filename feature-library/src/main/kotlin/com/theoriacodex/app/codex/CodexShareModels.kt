package com.theoriacodex.app.codex

import com.theoriacodex.data.repository.checkDocumentDepth
import com.theoriacodex.data.repository.portableMediaUrl
import com.theoriacodex.data.repository.portablePostSnapshot
import com.theoriacodex.data.storage.DurableJson
import com.theoriacodex.data.storage.withLegacyEquivalentCopies
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.PostTaxonomyTerm
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.model.withInferredGalleryLength
import com.theoriacodex.domain.tags.withRecoveredPixivTranslations
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class CodexShareFile(
    @SerialName("version")
    val version: Int? = 1,
    @SerialName("title")
    val title: String? = null,
    @SerialName("posts")
    val posts: List<CodexSharePost>? = null,
)

@Serializable
data class CodexSharePost(
    @SerialName("source")
    val source: String? = null,
    @SerialName("sourcePostId")
    val sourcePostId: String? = null,
    @SerialName("snapshot")
    val snapshot: CodexSharePostSnapshot? = null,
)

@Serializable
data class CodexSharePostSnapshot(
    @SerialName("preview")
    val preview: CodexShareImageRef? = null,
    @SerialName("full")
    val full: CodexShareImageRef? = null,
    @SerialName("media")
    val media: List<CodexShareImageRef?>? = null,
    @SerialName("pageUrl")
    val pageUrl: String? = null,
    @SerialName("width")
    val width: Int? = null,
    @SerialName("height")
    val height: Int? = null,
    @SerialName("canonicalTags")
    val canonicalTags: List<String?>? = null,
    @SerialName("rawTags")
    val rawTags: List<String?>? = null,
    @SerialName("taxonomy")
    val taxonomy: List<CodexShareTaxonomyTerm?>? = null,
    @SerialName("authorName")
    val authorName: String? = null,
    @SerialName("createdAtEpochMs")
    val createdAtEpochMs: Long? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("creatorProfile")
    val creatorProfile: CodexShareCreatorProfile? = null,
    @SerialName("creatorProfiles")
    val creatorProfiles: List<CodexShareCreatorProfile?>? = null,
    @SerialName("durationMs")
    val durationMs: Long? = null,
    @SerialName("mediaCount")
    val mediaCount: Int? = null,
)

@Serializable
data class CodexShareImageRef(
    @SerialName("url")
    val url: String? = null,
    @Transient
    val localPath: String? = null,
    @SerialName("mime")
    val mime: String? = null,
    @SerialName("progressiveUrls")
    val progressiveUrls: List<String?>? = null,
    @SerialName("fallbackUrls")
    val fallbackUrls: List<String?>? = null,
    @SerialName("isAnimated")
    val isAnimated: Boolean? = null,
)

@Serializable
data class CodexShareTaxonomyTerm(
    @SerialName("value")
    val value: String? = null,
    @SerialName("facet")
    val facet: String? = null,
    @SerialName("sourceNamespace")
    val sourceNamespace: String? = null,
    @SerialName("translation")
    val translation: String? = null,
)

@Serializable
data class CodexShareCreatorProfile(
    @SerialName("source")
    val source: String? = null,
    @SerialName("displayName")
    val displayName: String? = null,
    @SerialName("profileId")
    val profileId: String? = null,
    @SerialName("profileUrl")
    val profileUrl: String? = null,
    @SerialName("uploadsQuery")
    val uploadsQuery: String? = null,
)

internal fun buildCodexShareFile(title: String, posts: List<Post>): CodexShareFile {
    return CodexShareFile(
        version = CODEX_SHARE_VERSION,
        title = title,
        posts = posts.map { post ->
            CodexSharePost(
                source = post.id.source.id,
                sourcePostId = post.id.sourcePostId,
                snapshot = portablePostSnapshot(post).toCodexShareSnapshot(),
            )
        },
    )
}

fun encodeCodexShareFile(title: String, posts: List<Post>): String =
    DurableJson.encodeToString(CodexShareFile.serializer(), buildCodexShareFile(title, posts))

fun parseCodexShareFile(raw: String): CodexShareFile? {
    val root = runCatching {
        require(raw.length <= CODEX_SHARE_MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= CODEX_SHARE_MAX_BYTES)
        checkDocumentDepth(raw)
        DurableJson.parseToJsonElement(raw)
    }
        .getOrNull() as? JsonObject
        ?: return null
    val version = if ("version" in root) root.numberOrNull("version")?.toIntOrNull() else 1
    if (version !in 1..CODEX_SHARE_VERSION) return null
    val posts = root["posts"] as? JsonArray ?: return null
    if (posts.size > CODEX_SHARE_MAX_POSTS || !boundedShareValue(root, root = true)) return null
    return CodexShareFile(
        version = version,
        title = root.stringOrNull("title"),
        posts = root.postListOrNull("posts"),
    )
}

internal fun codexSharePostId(post: CodexSharePost): PostId? {
    val source = post.source
        ?.trim()
        ?.uppercase()
        ?.let { value -> SourceKey.parse(value) }
        ?: return null
    val sourcePostId = post.sourcePostId?.trim().orEmpty()
    if (sourcePostId.isBlank() || sourcePostId.length > 512 || sourcePostId.any { it.isISOControl() }) return null
    return PostId(source = source, sourcePostId = sourcePostId)
}

fun codexSharePostSnapshot(post: CodexSharePost): Post? {
    val postId = codexSharePostId(post) ?: return null
    val snapshot = post.snapshot ?: return null
    val preview = snapshot.preview?.toImageRef(postId.source) ?: return null
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

    return portablePostSnapshot(Post(
        id = postId,
        preview = preview,
        full = snapshot.full?.toImageRef(postId.source),
        media = snapshot.media.orEmpty().mapNotNull { media -> media?.toImageRef(postId.source) },
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
    ).withRecoveredPixivTranslations().withInferredGalleryLength())
}

internal fun selectCodexShareEntries(
    posts: List<CodexSharePost>,
): List<Pair<CodexSharePost, PostId>> {
    val selectedById = linkedMapOf<String, Pair<CodexSharePost, PostId>>()
    posts.forEach { entry ->
        val postId = codexSharePostId(entry) ?: return@forEach
        val key = "${postId.source.id}:${postId.sourcePostId}"
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
    storedPost: suspend () -> Post?,
    resolveFromSource: suspend () -> Post?,
): Post? {
    return codexSharePostSnapshot(entry)
        ?: storedPost()
        ?: resolveFromSource()
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
        fallbackUrls = fallbackUrls,
        isAnimated = isAnimated,
    )
}

private fun CodexShareImageRef.toImageRef(source: SourceKey): ImageRef? {
    val normalizedUrl = portableMediaUrl(url.normalizedOptionalString())
    if (normalizedUrl == null) return null
    return ImageRef(
        url = normalizedUrl,
        localPath = null,
        mime = mime.normalizedOptionalString(),
        progressiveUrls = progressiveUrls.normalizedStrings().mapNotNull(::portableMediaUrl),
        fallbackUrls = fallbackUrls.normalizedStrings().mapNotNull(::portableMediaUrl),
        isAnimated = isAnimated ?: false,
    ).let { ref -> if (fallbackUrls == null) ref.withLegacyEquivalentCopies(source) else ref }
}

private fun PostTaxonomyTerm.toCodexShareTaxonomyTerm(): CodexShareTaxonomyTerm {
    return CodexShareTaxonomyTerm(
        value = value,
        facet = facet.name,
        sourceNamespace = sourceNamespace,
        translation = translation,
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
        translation = translation.normalizedOptionalString(),
    )
}

private fun CreatorProfile.toCodexShareCreatorProfile(): CodexShareCreatorProfile {
    return CodexShareCreatorProfile(
        source = source.id,
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
        SourceKey.parse(sourceName.uppercase()) ?: return null
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

private fun JsonObject.postListOrNull(name: String): List<CodexSharePost>? =
    listOrNull(name) { element -> (element as? JsonObject)?.toCodexSharePost() }?.filterNotNull()

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
        fallbackUrls = stringListOrNull("fallbackUrls"),
        isAnimated = booleanOrNull("isAnimated"),
    )
}

private fun JsonObject.toCodexShareTaxonomyTerm(): CodexShareTaxonomyTerm {
    return CodexShareTaxonomyTerm(
        value = stringOrNull("value"),
        facet = stringOrNull("facet"),
        sourceNamespace = stringOrNull("sourceNamespace"),
        translation = stringOrNull("translation"),
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

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.stringOrNull(name: String): String? = (get(name) as? JsonPrimitive)?.stringOrNull()

private fun JsonPrimitive.stringOrNull(): String? = content.takeIf { isString }

private fun JsonObject.numberOrNull(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull && it.booleanOrNull == null }?.content

private fun JsonObject.intOrNull(name: String): Int? = numberOrNull(name)?.toIntOrNull()

private fun JsonObject.longOrNull(name: String): Long? = numberOrNull(name)?.toLongOrNull()

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.stringListOrNull(name: String): List<String?>? =
    listOrNull(name) { value -> (value as? JsonPrimitive)?.stringOrNull() }

private fun <T> JsonObject.objectListOrNull(
    name: String,
    transform: (JsonObject) -> T,
): List<T?>? {
    return listOrNull(name) { value -> (value as? JsonObject)?.let(transform) }
}

private fun <T> JsonObject.listOrNull(
    name: String,
    transform: (JsonElement) -> T?,
): List<T?>? {
    val value = get(name)
    if (value == null || value is JsonNull) return null
    return (value as? JsonArray)?.map(transform).orEmpty()
}

private const val CODEX_SHARE_VERSION = 2

const val CODEX_SHARE_MAX_BYTES = 32 * 1024 * 1024
internal const val CODEX_SHARE_MAX_POSTS = 100_000

private fun boundedShareValue(value: JsonElement, root: Boolean = false): Boolean = when (value) {
    is JsonObject -> value.entries.all { (key, child) ->
        key.length <= 256 && if (root && key == "posts" && child is JsonArray) {
            child.all { boundedShareValue(it) }
        } else boundedShareValue(child)
    }
    is JsonArray -> value.size <= 10_000 && value.all { boundedShareValue(it) }
    is JsonPrimitive -> !value.isString || value.content.length <= 32_768
}
