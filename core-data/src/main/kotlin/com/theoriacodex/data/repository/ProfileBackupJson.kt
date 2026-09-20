package com.theoriacodex.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.data.storage.RecentSearchPayloadCodec
import com.theoriacodex.domain.model.CreatorProfile
import com.theoriacodex.domain.model.ImageRef
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SearchFacet
import com.theoriacodex.domain.model.SourceKey
import java.net.URI

internal fun backupObject(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)
internal fun <T> backupArray(values: List<T>, encode: (T) -> JsonElement): JsonArray =
    JsonArray().apply { values.forEach { add(encode(it)) } }

internal fun JsonObject.backupString(name: String): String {
    val value = get(name)
    require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Invalid backup field: $name" }
    return value.asString.also { require(it.isNotBlank() && it.length <= 32_768) { "Invalid backup field: $name" } }
}

internal fun JsonObject.backupLong(name: String): Long {
    val value = get(name)
    require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber) { "Invalid backup field: $name" }
    return value.asString.toLongOrNull()?.takeIf { it >= 0L }
        ?: error("Invalid backup integer: $name")
}

internal fun JsonObject.backupInt(name: String): Int = backupLong(name).also {
    require(it <= Int.MAX_VALUE) { "Backup integer is too large: $name" }
}.toInt()

internal fun JsonObject.backupObjects(name: String, limit: Int = 100_000): List<JsonObject> {
    val value = get(name)
    require(value?.isJsonArray == true && value.asJsonArray.size() <= limit) { "Invalid backup list: $name" }
    return value.asJsonArray.map { element ->
        require(element.isJsonObject) { "Invalid backup record: $name" }
        element.asJsonObject
    }
}

internal fun JsonObject.backupStrings(name: String): List<String> {
    val value = get(name)
    require(value?.isJsonArray == true && value.asJsonArray.size() <= 10_000) { "Invalid backup list: $name" }
    return value.asJsonArray.map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length <= 32_768)
        it.asString
    }
}

internal fun JsonObject.putId(id: PostId) {
    addProperty("source", id.source.name)
    addProperty("postId", id.sourcePostId)
}

internal fun JsonObject.backupId() = PostId(SourceKey.valueOf(backupString("source")), backupString("postId"))

internal fun backupSearch(entry: RecentSearchEntry, gson: Gson): JsonObject = backupObject {
    addProperty("queryHash", entry.queryHash)
    addProperty("searchedAt", entry.searchedAtEpochMs)
    add("payload", JsonParser.parseString(RecentSearchPayloadCodec.encodeJson(entry, gson)))
}

internal fun JsonObject.readBackupSearch(gson: Gson): RecentSearchEntry {
    val payload = RecentSearchPayloadCodec.decodeJson(requireNotNull(get("payload")).toString(), gson)
    return RecentSearchEntry(payload.query, backupString("queryHash"), backupLong("searchedAt"),
        payload.kind, payload.sources, payload.sourceTags)
}

/** Device paths, credentials, and signed URLs are never portable backup data. */
internal fun portableBackupPost(post: Post): Post = post.copy(
    preview = portableBackupImage(post.preview),
    full = post.full?.let(::portableBackupImage)?.takeIf { it.url != null },
    media = post.media.map(::portableBackupImage),
    pageUrl = portableBackupPageUrl(post),
    creatorProfile = post.creatorProfile?.let(::portableBackupCreator),
    creatorProfiles = post.creatorProfiles.map(::portableBackupCreator),
)

internal fun portableBackupCreator(creator: CreatorProfile): CreatorProfile {
    val numericId = creator.profileId?.takeIf { it.matches(Regex("[0-9]+")) }
    val profileUrl = if (creator.source == SourceKey.GELBOORU && numericId != null) {
        "https://gelbooru.com/index.php?page=account&s=profile&id=$numericId"
    } else portableBackupUrl(creator.profileUrl)
    return creator.copy(profileUrl = profileUrl)
}

private fun portableBackupImage(image: ImageRef): ImageRef = image.copy(
    url = portableBackupUrl(image.url),
    localPath = null,
    progressiveUrls = image.progressiveUrls.mapNotNull(::portableBackupUrl),
    videoVariants = image.videoVariants.mapNotNull { variant ->
        portableBackupUrl(variant.url)?.let { variant.copy(url = it) }
    },
)

private fun portableBackupUrl(value: String?): String? = value?.takeIf { raw ->
    runCatching { URI(raw) }.getOrNull()?.let {
        it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
    } == true
}

private fun portableBackupPageUrl(post: Post): String? {
    val host = when (post.id.source) {
        SourceKey.GELBOORU -> "gelbooru.com"
        SourceKey.RULE34XXX -> "rule34.xxx"
        else -> null
    }
    // These public post identities use query parameters; rebuild only their known canonical form.
    return if (host != null && post.id.sourcePostId.matches(Regex("[0-9]+"))) {
        "https://$host/index.php?page=post&s=view&id=${post.id.sourcePostId}"
    } else portableBackupUrl(post.pageUrl)
}

internal fun encodeBackupPost(post: Post, gson: Gson): JsonElement =
    gson.toJsonTree(PostStorageCodec.encode(portableBackupPost(post)))

internal fun decodeBackupPost(record: JsonObject, gson: Gson): Post {
    require(record.backupInt("schemaVersion") == 1) { "Unsupported backup post version" }
    validateBackupPostRecord(record)
    val post = PostStorageCodec.decode(gson.fromJson(record, PostStorageRecord::class.java))
        ?: error("Invalid backup post")
    require(post.id.sourcePostId.isNotBlank()) { "Backup post has no identity" }
    return portableBackupPost(post)
}

private fun validateBackupPostRecord(record: JsonObject) {
    SourceKey.valueOf(record.backupString("source"))
    record.backupString("sourcePostId")
    record.backupStrings("canonicalTags")
    record.backupStrings("rawTags")
    record.backupStrings("previewProgressiveUrls")
    record.backupObjects("media", 10_000).forEach { image ->
        image.backupStrings("progressiveUrls")
        val animated = image.get("isAnimated")
        require(animated?.isJsonPrimitive == true && animated.asJsonPrimitive.isBoolean)
        image.backupObjects("videoVariants", 100).forEach { it.backupString("url") }
    }
    record.backupObjects("taxonomy", 10_000).forEach { term ->
        term.backupString("value")
        SearchFacet.valueOf(term.backupString("facet"))
    }
    record.backupObjects("creatorProfiles", 1_000).forEach(::validateBackupCreator)
    record.get("creatorProfile")?.let {
        require(it.isJsonObject)
        validateBackupCreator(it.asJsonObject)
    }
}

private fun validateBackupCreator(record: JsonObject) {
    SourceKey.valueOf(record.backupString("source"))
    val name = record.get("displayName")
    require(name?.isJsonPrimitive == true && name.asJsonPrimitive.isString)
}
