package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.repository.CodexBulkImportResult
import com.theoriacodex.data.repository.CodexBulkReorderResult
import com.theoriacodex.data.repository.CodexLikeSyncResult
import com.theoriacodex.data.repository.CodexLikesClearResult
import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.CodexProfileDeleteResult
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One Room-backed owner for Codex membership, reusable post snapshots, and profile likes.
 *
 * Keeping these tables in one database makes the user-visible Likes collection atomic: a like
 * cannot commit without its system-Codex membership, and an unlike cannot leave a stale item.
 */
class RoomCodexLikesRepository(
    private val database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    gson: Gson = Gson(),
) : CodexRepository, LikesRepository, CodexLikesTransactions {
    private val dao = database.codexLikesDao()
    private val codec = RoomPayloadCodec(gson)

    override fun observeCodices(): Flow<List<Codex>> {
        return dao.observeCodices().map { entities -> entities.map(CodexEntity::toDomain) }
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return dao.observeCodex(codexId).map { entity -> entity?.toDomain() }
    }

    override suspend fun ensureCodex(codexId: String, name: String): Codex {
        return database.withTransaction { ensureCodexInside(codexId, name) }
    }

    override suspend fun createCodex(name: String): Codex {
        return database.withTransaction {
            val codices = dao.codices()
            val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
                name,
                codices.map(CodexEntity::toDomain),
            )
            var codexId = newId()
            while (dao.codex(codexId) != null) codexId = newId()
            val created = CodexEntity(
                codexId,
                resolvedName,
                clock(),
                codices.size,
            )
            check(dao.insertCodex(created) != -1L) { "Generated Codex id already exists" }
            created.toDomain()
        }
    }

    override suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        database.withTransaction {
            val current = dao.codices()
            val sourceIndex = current.indexOfFirst { entity -> entity.codexId == codexId }
            if (sourceIndex < 0 || current.isEmpty()) return@withTransaction
            val clampedTarget = targetIndex.coerceIn(0, current.lastIndex)
            if (sourceIndex == clampedTarget) return@withTransaction
            val reordered = current.toMutableList().apply {
                add(clampedTarget, removeAt(sourceIndex))
            }
            reordered.forEachIndexed { index, entity ->
                if (entity.displayOrder != index) dao.updateCodexOrder(entity.codexId, index)
            }
        }
    }

    override suspend fun renameCodex(codexId: String, name: String) {
        database.withTransaction {
            val current = dao.codices()
            val existing = current.firstOrNull { entity -> entity.codexId == codexId }
                ?: return@withTransaction
            val resolved = CodexLikesPolicy.resolveUniqueCodexName(
                name,
                current.map(CodexEntity::toDomain),
                excludeCodexId = codexId,
            )
            if (existing.name != resolved) dao.updateCodexName(codexId, resolved)
        }
    }

    override suspend fun deleteCodex(codexId: String) {
        database.withTransaction {
            if (dao.deleteCodex(codexId) > 0) {
                normalizeCodexOrderInside()
                cleanupOrphanPostsInside()
            }
        }
    }

    override fun observeCodexItems(codexId: String): Flow<List<CodexItem>> {
        return dao.observeCodexItems(codexId).map { entities ->
            entities.mapNotNull(CodexItemEntity::toDomainOrNull)
        }
    }

    override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
        val rows = when (sort) {
            CodexSortMode.NEWEST_SAVED -> dao.observeCodexPostsNewest(codexId)
            CodexSortMode.OLDEST_SAVED -> dao.observeCodexPostsOldest(codexId)
            CodexSortMode.BY_SOURCE -> dao.observeCodexPostsBySource(codexId)
        }
        return rows.map { values -> values.map(codec::decodePostRow) }
    }

    override suspend fun getPost(postId: PostId): Post? {
        return database.withTransaction {
            dao.post(postId.source.name, postId.sourcePostId)?.let(codec::decodePost)
        }
    }

    override suspend fun addItem(codexId: String, post: Post) {
        database.withTransaction {
            if (dao.codex(codexId) == null) return@withTransaction
            upsertPostInside(post)
            dao.insertCodexItem(
                CodexItemEntity(
                    codexId,
                    post.id.source.name,
                    post.id.sourcePostId,
                    clock(),
                )
            )
        }
    }

    override suspend fun updatePost(post: Post) {
        database.withTransaction {
            if (dao.post(post.id.source.name, post.id.sourcePostId) != null) {
                dao.updatePost(post.id.source.name, post.id.sourcePostId, codec.encodePost(post))
            }
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        database.withTransaction {
            if (dao.deleteCodexItem(codexId, sourceKey.name, sourcePostId) > 0) {
                cleanupOrphanPostsInside()
            }
        }
    }

    override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
        if (postIds.isEmpty()) return
        database.withTransaction {
            var removed = false
            postIds.forEach { postId ->
                removed = dao.deleteCodexItem(codexId, postId.source.name, postId.sourcePostId) > 0 || removed
            }
            if (removed) cleanupOrphanPostsInside()
        }
    }

    override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
        if (items.isEmpty()) return
        val postsById = posts.associateBy(Post::id)
        database.withTransaction {
            items.forEach { item ->
                val post = postsById[item.postId] ?: return@forEach
                if (
                    dao.codex(item.codexId) != null &&
                    dao.codexItem(item.codexId, item.postId.source.name, item.postId.sourcePostId) == null
                ) {
                    upsertPostInside(post)
                    dao.insertCodexItem(
                        CodexItemEntity(
                            item.codexId,
                            item.postId.source.name,
                            item.postId.sourcePostId,
                            item.savedAtEpochMs,
                        )
                    )
                }
            }
        }
    }

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        return dao.observeLikes(normalized).map { entities -> entities.mapNotNull(codec::decodeLike) }
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        return dao.observeLikes(normalized).map { entities ->
            entities.mapNotNullTo(linkedSetOf()) { entity -> entity.toPostIdOrNull() }
        }
    }

    override suspend fun toggleLike(profileId: String, postId: PostId, tags: List<String>): Boolean {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) return false
        return database.withTransaction {
            val liked = toggleLikeInside(normalized, postId, tags)
            if (!liked) cleanupOrphanPostsInside()
            liked
        }
    }

    override suspend fun clearLikes(profileId: String) {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isNotBlank()) {
            database.withTransaction {
                dao.deleteLikes(normalized)
                cleanupOrphanPostsInside()
            }
        }
    }

    override suspend fun importCodex(
        codexId: String,
        name: String,
        posts: List<Post>,
    ): CodexBulkImportResult {
        return database.withTransaction {
            val codex = ensureCodexInside(codexId, name)
            val uniquePosts = posts.distinctBy(Post::id)
            var insertedMemberships = 0
            uniquePosts.forEach { post ->
                upsertPostInside(post)
                val inserted = dao.insertCodexItem(
                    CodexItemEntity(
                        codex.codexId,
                        post.id.source.name,
                        post.id.sourcePostId,
                        clock(),
                    )
                )
                if (inserted != -1L) insertedMemberships += 1
            }
            CodexBulkImportResult(
                codex = codex,
                acceptedPosts = uniquePosts.size,
                insertedMemberships = insertedMemberships,
            )
        }
    }

    override suspend fun reorderCodices(codexIdsInOrder: List<String>): CodexBulkReorderResult {
        return database.withTransaction {
            val current = dao.codices()
            val resolved = CodexLikesPolicy.resolveCompleteCodexOrder(
                currentCodices = current.map(CodexEntity::toDomain),
                codexIdsInOrder = codexIdsInOrder,
            ) ?: return@withTransaction CodexBulkReorderResult(
                applied = false,
                movedCodices = 0,
            )
            val currentById = current.associateBy { entity -> entity.codexId }
            var movedCodices = 0
            resolved.forEachIndexed { index, codex ->
                val entity = currentById.getValue(codex.codexId)
                if (entity.displayOrder != index) {
                    dao.updateCodexOrder(codex.codexId, index)
                    movedCodices += 1
                }
            }
            CodexBulkReorderResult(applied = true, movedCodices = movedCodices)
        }
    }

    override suspend fun toggleLikeAndSyncSystemCodex(
        profileId: String,
        systemCodexId: String,
        systemCodexName: String,
        post: Post,
        tags: List<String>,
    ): CodexLikeSyncResult {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) {
            return CodexLikeSyncResult(nowLiked = false, membershipChanged = false)
        }
        return database.withTransaction {
            val nowLiked = toggleLikeInside(normalized, post.id, tags)
            val membershipChanged = if (nowLiked) {
                val codex = ensureCodexInside(systemCodexId, systemCodexName)
                upsertPostInside(post)
                dao.insertCodexItem(
                    CodexItemEntity(
                        codex.codexId,
                        post.id.source.name,
                        post.id.sourcePostId,
                        clock(),
                    )
                ) != -1L
            } else {
                val removed = dao.deleteCodexItem(
                    systemCodexId,
                    post.id.source.name,
                    post.id.sourcePostId,
                ) > 0
                cleanupOrphanPostsInside()
                removed
            }
            CodexLikeSyncResult(nowLiked = nowLiked, membershipChanged = membershipChanged)
        }
    }

    override suspend fun clearLikesAndLikedMemberships(
        profileId: String,
        systemCodexId: String,
    ): CodexLikesClearResult {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) return CodexLikesClearResult(0, 0)
        return database.withTransaction {
            val likedBeforeClear = dao.likesForProfile(normalized)
            val clearedLikes = dao.deleteLikes(normalized)
            var removedMemberships = 0
            likedBeforeClear.forEach { liked ->
                removedMemberships += dao.deleteCodexItem(
                    systemCodexId,
                    liked.source,
                    liked.sourcePostId,
                )
            }
            cleanupOrphanPostsInside()
            CodexLikesClearResult(clearedLikes, removedMemberships)
        }
    }

    override suspend fun clearLikesAndDeleteSystemCodex(
        profileId: String,
        systemCodexId: String,
    ): CodexProfileDeleteResult {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) return CodexProfileDeleteResult(0, false)
        return database.withTransaction {
            val clearedLikes = dao.deleteLikes(normalized)
            val systemCodexDeleted = dao.deleteCodex(systemCodexId) > 0
            if (systemCodexDeleted) normalizeCodexOrderInside()
            cleanupOrphanPostsInside()
            CodexProfileDeleteResult(clearedLikes, systemCodexDeleted)
        }
    }

    override suspend fun clearAllContent() {
        database.withTransaction {
            dao.deleteAllLikes()
            dao.deleteAllCodices()
            dao.deleteAllPosts()
        }
    }

    private suspend fun ensureCodexInside(codexId: String, name: String): Codex {
        val current = dao.codices()
        val existing = current.firstOrNull { entity -> entity.codexId == codexId }
        val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
            name,
            current.map(CodexEntity::toDomain),
            excludeCodexId = codexId,
        )
        if (existing != null) {
            if (existing.name != resolvedName) dao.updateCodexName(codexId, resolvedName)
            return CodexEntity(
                existing.codexId,
                resolvedName,
                existing.createdAtEpochMs,
                existing.displayOrder,
            ).toDomain()
        }
        val created = CodexEntity(
            codexId,
            resolvedName,
            clock(),
            current.size,
        )
        dao.insertCodex(created)
        return created.toDomain()
    }

    private suspend fun normalizeCodexOrderInside() {
        dao.codices().forEachIndexed { index, entity ->
            if (entity.displayOrder != index) dao.updateCodexOrder(entity.codexId, index)
        }
    }

    private fun cleanupOrphanPostsInside() {
        dao.deleteOrphanPosts()
    }

    private suspend fun upsertPostInside(post: Post) {
        val entity = PostEntity(
            post.id.source.name,
            post.id.sourcePostId,
            codec.encodePost(post),
        )
        if (dao.insertPost(entity) == -1L) {
            dao.updatePost(entity.source, entity.sourcePostId, entity.payloadJson)
        }
    }

    private suspend fun toggleLikeInside(
        normalizedProfileId: String,
        postId: PostId,
        tags: List<String>,
    ): Boolean {
        val existing = dao.likedPost(normalizedProfileId, postId.source.name, postId.sourcePostId)
        if (existing != null) {
            dao.deleteLike(normalizedProfileId, postId.source.name, postId.sourcePostId)
            return false
        }
        dao.insertLike(
            LikedPostEntity(
                normalizedProfileId,
                postId.source.name,
                postId.sourcePostId,
                clock(),
                codec.encodeTags(CodexLikesPolicy.normalizeLikedTags(tags)),
            )
        )
        return true
    }
}

private class RoomPayloadCodec(
    private val gson: Gson,
) {
    private val tagListType = object : TypeToken<List<String>>() {}.type
    private val postCodec = LocalPostPayloadCodec(gson)

    fun encodePost(post: Post): String = postCodec.encode(post)

    fun decodePost(entity: PostEntity): Post = postCodec.decode(entity)

    fun decodePostRow(row: CodexPostRow): Post {
        return decodePost(PostEntity(row.source, row.sourcePostId, row.payloadJson))
    }

    fun encodeTags(tags: List<String>): String = gson.toJson(tags, tagListType)

    fun decodeLike(entity: LikedPostEntity): LikedPost? {
        val postId = entity.toPostIdOrNull() ?: return null
        val tags = runCatching { gson.fromJson<List<String>>(entity.tagsJson, tagListType) }
            .getOrNull()
            .orEmpty()
        return LikedPost(
            profileId = entity.profileId,
            postId = postId,
            likedAtEpochMs = entity.likedAtEpochMs,
            tags = CodexLikesPolicy.normalizeLikedTags(tags),
        )
    }
}

private fun CodexEntity.toDomain(): Codex {
    return Codex(codexId = codexId, name = name, createdAtEpochMs = createdAtEpochMs)
}

private fun CodexItemEntity.toDomainOrNull(): CodexItem? {
    val sourceKey = source.toSourceKeyOrNull() ?: return null
    return CodexItem(
        codexId = codexId,
        postId = PostId(sourceKey, sourcePostId),
        savedAtEpochMs = savedAtEpochMs,
    )
}

private fun LikedPostEntity.toPostIdOrNull(): PostId? {
    return source.toSourceKeyOrNull()?.let { sourceKey -> PostId(sourceKey, sourcePostId) }
}

private fun String.toSourceKeyOrNull(): SourceKey? {
    return runCatching { SourceKey.valueOf(trim()) }.getOrNull()
}
