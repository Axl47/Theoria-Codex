package com.theoriacodex.data.android.room

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.storage.CURRENT_POST_STORAGE_SCHEMA_VERSION
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.data.storage.PostStorageRecord
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import kotlinx.coroutines.CancellationException

internal class LegacyDataPreparer(
    private val gson: Gson,
    private val clock: () -> Long,
) {
    private val postCodec = LocalPostPayloadCodec(gson)

    fun prepare(source: LegacySourcePair): PreparedLegacyData {
        val codexStore = parseSource(
            source.codex,
            RoomLegacyJsonImporter.LEGACY_CODEX_FILE_NAME,
            LegacyCodexStoreFile::class.java,
            LegacyCodexStoreFile(),
        )
        val likesStore = parseSource(
            source.likes,
            RoomLegacyJsonImporter.LEGACY_LIKES_FILE_NAME,
            LegacyLikesStoreFile::class.java,
            LegacyLikesStoreFile(),
        )
        val codicesById = prepareCodices(codexStore.codices.orEmpty())
        val postsById = preparePosts(codexStore.posts.orEmpty())
        return PreparedLegacyData(
            codices = codicesById.values.toList(),
            posts = postsById.values.map(::postEntity),
            items = prepareItems(codexStore.items.orEmpty(), codicesById, postsById),
            likes = prepareLikes(likesStore.likes.orEmpty()),
        )
    }

    private fun prepareCodices(
        records: List<LegacyCodexRecord?>,
    ): LinkedHashMap<String, CodexEntity> {
        val codicesById = linkedMapOf<String, CodexEntity>()
        records.forEachIndexed { index, record ->
            val value = record ?: migrationFailure("Codex record at index $index is null")
            val id = requireNonBlankKey(value.codexId, "Codex record at index $index has no id")
            if (id in codicesById) migrationFailure("Codex id '$id' appears more than once")
            codicesById[id] = CodexEntity(
                id,
                value.name?.trim()?.ifBlank { "Codex" } ?: "Codex",
                value.createdAtEpochMs ?: 0L,
                codicesById.size,
            )
        }
        return codicesById
    }

    private fun preparePosts(records: List<JsonObject?>): LinkedHashMap<PostId, Post> {
        val postsById = linkedMapOf<PostId, Post>()
        records.forEachIndexed { index, record ->
            val post = decodeLegacyPost(record, index)
            if (post.id in postsById) {
                migrationFailure("Post id '${post.id.source}:${post.id.sourcePostId}' appears more than once")
            }
            postsById[post.id] = post
        }
        return postsById
    }

    private fun postEntity(post: Post): PostEntity {
        return PostEntity(post.id.source.name, post.id.sourcePostId, postCodec.encode(post))
    }

    private fun prepareItems(
        groups: Map<String, List<LegacyCodexItemRecord?>?>,
        codicesById: Map<String, CodexEntity>,
        postsById: Map<PostId, Post>,
    ): List<CodexItemEntity> {
        val itemsByKey = linkedMapOf<Triple<String, SourceKey, String>, CodexItemEntity>()
        groups.forEach { (mapCodexId, records) ->
            val codexId = requireNonBlankKey(mapCodexId, "Codex item group has no Codex id")
            if (codexId !in codicesById) {
                migrationFailure("Codex item group '$codexId' references an unknown Codex")
            }
            val group = records ?: migrationFailure("Codex item group '$codexId' is null")
            group.forEachIndexed { index, record ->
                addPreparedItem(itemsByKey, postsById, codexId, index, record)
            }
        }
        return itemsByKey.values.toList()
    }

    private fun addPreparedItem(
        itemsByKey: MutableMap<Triple<String, SourceKey, String>, CodexItemEntity>,
        postsById: Map<PostId, Post>,
        codexId: String,
        index: Int,
        record: LegacyCodexItemRecord?,
    ) {
        val label = "Codex item '$codexId'[$index]"
        val value = record ?: migrationFailure("$label is null")
        val recordCodexId = requireNonBlankKey(value.codexId, "$label has no record Codex id")
        if (recordCodexId != codexId) {
            migrationFailure("$label declares mismatched Codex '$recordCodexId'")
        }
        val sourceKey = requireSource(value.source, label)
        val sourcePostId = requireNonBlankKey(value.sourcePostId, "$label has no post id")
        if (PostId(sourceKey, sourcePostId) !in postsById) {
            migrationFailure("$label references missing post '${sourceKey.name}:$sourcePostId'")
        }
        val key = Triple(codexId, sourceKey, sourcePostId)
        if (key in itemsByKey) {
            migrationFailure(
                "Codex item '${sourceKey.name}:$sourcePostId' appears more than once in '$codexId'"
            )
        }
        itemsByKey[key] = CodexItemEntity(
            codexId,
            sourceKey.name,
            sourcePostId,
            value.savedAtEpochMs ?: 0L,
        )
    }

    private fun prepareLikes(records: List<LegacyLikedPostRecord?>): List<LikedPostEntity> {
        val likesByKey = linkedMapOf<Triple<String, SourceKey, String>, LikedPostEntity>()
        records.forEachIndexed { index, record ->
            val value = record ?: migrationFailure("Like record at index $index is null")
            val sourceKey = requireSource(value.source, "Like record at index $index")
            val sourcePostId = requireNonBlankKey(
                value.sourcePostId,
                "Like record at index $index has no post id",
            )
            val profileId = parseStoredProfileId(value.profileId, value.profile)
            val key = Triple(profileId, sourceKey, sourcePostId)
            if (key in likesByKey) {
                migrationFailure(
                    "Like '${sourceKey.name}:$sourcePostId' appears more than once for profile '$profileId'"
                )
            }
            val tags = normalizedTags(value, index)
            likesByKey[key] = LikedPostEntity(
                profileId,
                sourceKey.name,
                sourcePostId,
                value.likedAtEpochMs ?: clock(),
                gson.toJson(tags),
            )
        }
        return likesByKey.values.toList()
    }

    private fun normalizedTags(value: LegacyLikedPostRecord, index: Int): List<String> {
        return try {
            CodexLikesPolicy.normalizeLikedTags(value.tags.orEmpty())
        } catch (error: RuntimeException) {
            migrationFailure("Like record at index $index contains invalid tags", error)
        }
    }

    private fun decodeLegacyPost(record: JsonObject?, index: Int): Post {
        val value = record ?: migrationFailure("Post record at index $index is null")
        val label = "Post record at index $index"
        val sourceName = requiredJsonString(value, "source", "$label has no source")
        val sourceKey = requireSource(sourceName, label)
        val sourcePostId = requiredJsonString(value, "sourcePostId", "$label has no post id")
        validatePostSchemaVersion(value, label)
        val storageRecord = decodeStorageRecord(value, label)
        val post = decodeStoredPost(storageRecord, label)
        if (post.id != PostId(sourceKey, sourcePostId)) {
            migrationFailure("$label changed identity while decoding")
        }
        return post
    }

    private fun decodeStorageRecord(value: JsonObject, label: String): PostStorageRecord {
        return try {
            gson.fromJson(value, PostStorageRecord::class.java)
                ?: migrationFailure("$label decoded to null")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            migrationFailure("$label does not match the legacy Post schema", error)
        }
    }

    private fun decodeStoredPost(record: PostStorageRecord, label: String): Post {
        return try {
            PostStorageCodec.decode(record)
                ?: migrationFailure("$label cannot be decoded by Post storage schema v1")
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            migrationFailure("$label contains invalid Post values", error)
        }
    }

    private fun validatePostSchemaVersion(record: JsonObject, label: String) {
        val element = record.get("schemaVersion") ?: return
        if (element.isJsonNull) return
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            migrationFailure("$label has a non-integer Post schema version")
        }
        val raw = element.asJsonPrimitive.asString
        if (!raw.matches(LEGACY_INTEGER_PATTERN)) {
            migrationFailure("$label has a non-integer Post schema version")
        }
        val version = raw.toIntOrNull()
            ?: migrationFailure("$label has a Post schema version outside the integer range")
        if (version != CURRENT_POST_STORAGE_SCHEMA_VERSION) {
            migrationFailure("$label uses unsupported future Post schema version $version")
        }
    }

    private fun requiredJsonString(record: JsonObject, field: String, failure: String): String {
        val element = record.get(field) ?: migrationFailure(failure)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) migrationFailure(failure)
        return requireNonBlankKey(element.asString, failure)
    }

    private fun requireSource(raw: String?, label: String): SourceKey {
        val normalized = requireNonBlankKey(raw, "$label has no source")
        return normalized.toSourceKeyOrNull()
            ?: migrationFailure("$label uses unknown source '$normalized'")
    }

    private fun requireNonBlankKey(raw: String?, failure: String): String {
        return raw?.trim()?.takeIf(String::isNotBlank) ?: migrationFailure(failure)
    }

    private fun <T> parseSource(
        source: LegacySource,
        label: String,
        type: Class<T>,
        emptyValue: T,
    ): T {
        if (!source.exists || source.bytes.isEmpty() || source.bytes.all(Byte::isWhitespaceByte)) {
            return emptyValue
        }
        return try {
            gson.fromJson(source.bytes.decodeToString(), type)
                ?: migrationFailure("$label decoded to null")
        } catch (error: JsonSyntaxException) {
            migrationFailure("$label is not valid legacy JSON", error)
        }
    }

    private fun migrationFailure(message: String, cause: Throwable? = null): Nothing {
        throw LegacyJsonMigrationException(message, cause)
    }
}

private val LEGACY_INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")

private fun Byte.isWhitespaceByte(): Boolean = toInt().toChar().isWhitespace()
