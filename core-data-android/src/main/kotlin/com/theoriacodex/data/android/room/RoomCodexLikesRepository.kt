package com.theoriacodex.data.android.room

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theoriacodex.data.repository.CodexCleaningRequest
import com.theoriacodex.data.repository.CodexCleaningSnapshot
import com.theoriacodex.data.repository.CodexBulkImportResult
import com.theoriacodex.data.repository.CodexBulkReorderResult
import com.theoriacodex.data.repository.CodexLikeSyncResult
import com.theoriacodex.data.repository.ClearedLikesSnapshot
import com.theoriacodex.data.repository.CodexLikesClearResult
import com.theoriacodex.data.repository.DeletedCodexSnapshot
import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.CodexProfileDeleteResult
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSummary
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import com.theoriacodex.domain.tags.sourceTagKey
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

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
    private val writeGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
) : CodexRepository, LikesRepository, CodexLikesTransactions {
    private val dao = database.codexLikesDao()
    private val codec = RoomPayloadCodec(gson)
    private val postCodec = LocalPostPayloadCodec(gson)
    private val sharedPostPayloads = SharedPostPayloadWriter(dao, LocalPostPayloadCodec(gson))
    private val retainedPayloads = RetainedPostPayloadCache(postCodec::decode)
    private val summaries = RoomCodexSummaryQueries(dao, retainedPayloads)

    override suspend fun cleanCodex(request: CodexCleaningRequest): CodexCleaningSnapshot = database.withTransaction {
        writeGuard.requireProfileWritable(request.profileId)
        (request.comparisonIds + request.targetId).forEach { id ->
            check(dao.codex(id) != null) { "A selected Codex no longer exists" }
        }
        val rows = dao.overlappingItems(request.targetId, request.comparisonIds.sorted())
        val items = rows.map { checkNotNull(it.toDomainOrNull()) }
        val posts = rows.map { row ->
            codec.decodePost(checkNotNull(dao.post(row.source, row.sourcePostId)))
        }
        val likes = if (request.targetId == ProfileLibraryIds.likes(request.profileId)) {
            rows.mapNotNull { row ->
                dao.likedPost(request.profileId, row.source, row.sourcePostId)?.let { checkNotNull(codec.decodeLike(it)) }
            }
        } else emptyList()
        rows.forEach { row -> dao.deleteCodexItem(request.targetId, row.source, row.sourcePostId) }
        likes.forEach { like -> dao.deleteLike(request.profileId, like.postId.source.id, like.postId.sourcePostId) }
        // Comparison memberships still own these shared posts; no payload or offline file is removed.
        CodexCleaningSnapshot(request, items, posts, likes)
    }

    override suspend fun restoreCleanedCodex(snapshot: CodexCleaningSnapshot) {
        database.withTransaction {
            val request = snapshot.request
            writeGuard.requireProfileWritable(request.profileId)
            check(dao.codex(request.targetId) != null) { "The cleaned Codex no longer exists" }
            check(snapshot.items.all { it.codexId == request.targetId })
            restoreItems(snapshot.items, snapshot.posts)
            snapshot.likes.forEach { like ->
                check(request.targetId == ProfileLibraryIds.likes(request.profileId) && like.profileId == request.profileId)
                dao.insertLike(LikedPostEntity(
                    like.profileId, like.postId.source.id, like.postId.sourcePostId,
                    like.likedAtEpochMs, codec.encodeTags(like.tags),
                ))
            }
        }
    }

    override suspend fun removeProfileLibrary(profileId: String) {
        database.withTransaction {
            val codices = dao.codices().filter { ProfileLibraryIds.belongsTo(it.codexId, profileId) }
            val affected = codices.flatMap { dao.itemsForCodex(it.codexId) }
                .map { it.source to it.sourcePostId } + dao.likesForProfile(profileId).map { it.source to it.sourcePostId }
            dao.deleteLikes(profileId)
            codices.forEach { dao.deleteCodex(it.codexId) }
            normalizeCodexOrderInside()
            cleanupOrphanPostsInside(affected)
        }
    }

    override fun observeCodexSummaries(codexIds: Set<String>, coverLimit: Int): Flow<List<CodexSummary>> =
        summaries.observeSummaries(codexIds, coverLimit)

    override fun observeSavedPostIds(codexIds: Set<String>): Flow<Set<PostId>> =
        summaries.observeSavedPostIds(codexIds)

    override fun observeCodices(): Flow<List<Codex>> {
        return combine(dao.observeCodices(), dao.observeAutomaticTags()) { codices, automaticTags ->
            val tagsByCodex = automaticTags.groupBy(CodexAutomaticTagEntity::getCodexId)
            codices.map { entity -> entity.toDomain(tagsByCodex[entity.codexId].orEmpty()) }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override fun observeCodex(codexId: String): Flow<Codex?> {
        return combine(dao.observeCodex(codexId), dao.observeAutomaticTags()) { entity, automaticTags ->
            entity?.toDomain(automaticTags.filter { tag -> tag.codexId == codexId })
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override suspend fun ensureCodex(codexId: String, name: String): Codex {
        return database.withTransaction { ensureCodexInside(codexId, name) }
    }

    override suspend fun createCodex(name: String): Codex {
        return database.withTransaction {
            val codices = dao.codices()
            val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
                name,
                codices.map { entity -> entity.toDomain() },
            )
            var codexId = newId()
            while (dao.codex(codexId) != null) codexId = newId()
            writeGuard.requireCodexWritable(codexId)
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
                current.map { entity -> entity.toDomain() },
                excludeCodexId = codexId,
            )
            if (existing.name != resolved) dao.updateCodexName(codexId, resolved)
        }
    }

    override suspend fun setAutomaticTag(codexId: String, tag: CodexAutomaticTag, enabled: Boolean) {
        database.withTransaction {
            if (dao.codex(codexId) == null) return@withTransaction
            val current = dao.automaticTagsForCodex(codexId).mapNotNull { entity ->
                entity.toDomainOrNull()
            }
            val updated = CodexLikesPolicy.setAutomaticTag(current, tag, enabled)
            if (updated == current) return@withTransaction
            dao.deleteAutomaticTags(codexId)
            if (updated.isNotEmpty()) {
                dao.insertAutomaticTags(updated.map { automaticTag ->
                    automaticTag.toEntity(codexId)
                })
            }
        }
    }

    override suspend fun deleteCodex(codexId: String) {
        database.withTransaction {
            val removedItems = dao.itemsForCodex(codexId)
            if (dao.deleteCodex(codexId) > 0) {
                normalizeCodexOrderInside()
                cleanupOrphanPostsInside(removedItems.map { it.source to it.sourcePostId })
            }
        }
    }

    override suspend fun deleteCodexForUndo(codexId: String): DeletedCodexSnapshot? = database.withTransaction {
        val all = dao.codices()
        val position = all.indexOfFirst { it.codexId == codexId }
        if (position < 0) return@withTransaction null
        val entity = all[position]
        val rows = dao.itemsForCodex(codexId)
        val snapshot = DeletedCodexSnapshot(
            codex = entity.toDomain(dao.automaticTagsForCodex(codexId)),
            position = position,
            items = rows.mapNotNull { it.toDomainOrNull() },
            posts = rows.mapNotNull { row -> dao.post(row.source, row.sourcePostId)?.let(codec::decodePost) },
        )
        deleteCodex(codexId)
        snapshot
    }

    override suspend fun restoreDeletedCodex(snapshot: DeletedCodexSnapshot) {
        database.withTransaction {
            val codex = snapshot.codex
            writeGuard.requireCodexWritable(codex.codexId)
            val current = dao.codices()
            // A Codex created under this name during the Undo window keeps it; this one gets a free one.
            val name = CodexLikesPolicy.resolveUniqueCodexName(codex.name, current.map { it.toDomain() }, excludeCodexId = codex.codexId)
            if (dao.insertCodex(CodexEntity(codex.codexId, name, codex.createdAtEpochMs, current.size)) != -1L) {
                if (codex.automaticTags.isNotEmpty()) dao.insertAutomaticTags(codex.automaticTags.map { it.toEntity(codex.codexId) })
                reorderCodex(codex.codexId, snapshot.position)
            }
            restoreItems(snapshot.items, snapshot.posts)
        }
    }

    override fun observeCodexItems(codexId: String): Flow<List<CodexItem>> {
        return dao.observeCodexItems(codexId).map { entities ->
            entities.mapNotNull(CodexItemEntity::toDomainOrNull)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override fun observeCodexPosts(codexId: String, sort: CodexSortMode): Flow<List<Post>> {
        val rows = when (sort) {
            CodexSortMode.NEWEST_SAVED -> dao.observeCodexPostsNewest(codexId)
            CodexSortMode.OLDEST_SAVED -> dao.observeCodexPostsOldest(codexId)
            CodexSortMode.BY_SOURCE -> dao.observeCodexPostsBySource(codexId)
        }
        // Every sort projects the same rows, so sorts share one retained projection per Codex.
        return rows.map { values -> values.map { PostEntity(it.source, it.sourcePostId, it.payloadJson) } }
            .decodePostSnapshots(retainedPayloads, "codex:$codexId")
    }

    override fun observeLibraryPosts(codexIds: Set<String>): Flow<List<Post>> {
        if (codexIds.isEmpty()) return flowOf(emptyList())
        val ids = codexIds.sorted()
        return dao.observeLibraryPosts(ids).decodePostSnapshots(retainedPayloads, "library:${ids.joinToString("\u0000")}")
    }

    override suspend fun getPost(postId: PostId): Post? {
        return database.withTransaction {
            dao.post(postId.source.id, postId.sourcePostId)?.let(codec::decodePost)
        }
    }

    override suspend fun addItems(codexId: String, posts: List<Post>): Int {
        return database.withTransaction {
            writeGuard.requireCodexWritable(codexId)
            check(dao.codex(codexId) != null) { "The selected Codex no longer exists" }
            posts.distinctBy(Post::id).count { post ->
                sharedPostPayloads.upsert(post)
                dao.insertCodexItem(
                    CodexItemEntity(codexId, post.id.source.id, post.id.sourcePostId, clock()),
                ) != -1L
            }
        }
    }

    override suspend fun updatePost(post: Post) {
        database.withTransaction {
            if (dao.post(post.id.source.id, post.id.sourcePostId) != null) {
                sharedPostPayloads.upsert(post)
            }
        }
    }

    override suspend fun removeItem(codexId: String, sourceKey: SourceKey, sourcePostId: String) {
        database.withTransaction {
            if (dao.deleteCodexItem(codexId, sourceKey.id, sourcePostId) > 0) {
                dao.deleteOrphanPost(sourceKey.id, sourcePostId)
            }
        }
    }

    override suspend fun removeItems(codexId: String, postIds: Set<PostId>) {
        if (postIds.isEmpty()) return
        database.withTransaction {
            var removed = false
            postIds.forEach { postId ->
                removed = dao.deleteCodexItem(codexId, postId.source.id, postId.sourcePostId) > 0 || removed
            }
            if (removed) cleanupOrphanPostsInside(postIds.map { it.source.id to it.sourcePostId })
        }
    }

    override suspend fun restoreItems(items: List<CodexItem>, posts: List<Post>) {
        if (items.isEmpty()) return
        val postsById = posts.associateBy(Post::id)
        database.withTransaction {
            items.forEach { item ->
                writeGuard.requireCodexWritable(item.codexId)
                val post = postsById[item.postId] ?: return@forEach
                if (
                    dao.codex(item.codexId) != null &&
                    dao.codexItem(item.codexId, item.postId.source.id, item.postId.sourcePostId) == null
                ) {
                    sharedPostPayloads.upsert(post)
                    dao.insertCodexItem(
                        CodexItemEntity(
                            item.codexId,
                            item.postId.source.id,
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
            .distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    override fun observeLikedPostIds(profileId: String): Flow<Set<PostId>> {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        return dao.observeLikedPostIds(normalized).map { entities ->
            entities.mapNotNullTo(linkedSetOf()) { entity ->
                SourceKey.parse(entity.source)?.let { PostId(it, entity.sourcePostId) }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
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
                sharedPostPayloads.upsert(post)
                val inserted = dao.insertCodexItem(
                    CodexItemEntity(
                        codex.codexId,
                        post.id.source.id,
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
                currentCodices = current.map { entity -> entity.toDomain() },
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
        eligibleAutomaticCodexIds: Set<String>,
    ): CodexLikeSyncResult {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) {
            return CodexLikeSyncResult(nowLiked = false, membershipChanged = false)
        }
        return database.withTransaction {
            writeGuard.requireProfileWritable(normalized)
            val nowLiked = toggleLikeInside(normalized, post.id, tags)
            var automaticMembershipsAdded = 0
            val membershipChanged = if (nowLiked) {
                val codex = ensureCodexInside(systemCodexId, systemCodexName)
                sharedPostPayloads.upsert(post)
                val systemMembershipChanged = dao.insertCodexItem(
                    CodexItemEntity(
                        codex.codexId,
                        post.id.source.id,
                        post.id.sourcePostId,
                        clock(),
                    )
                ) != -1L
                val candidateIds = (eligibleAutomaticCodexIds - systemCodexId).sorted()
                if (candidateIds.isNotEmpty()) {
                    dao.automaticTagsForCodices(candidateIds)
                        .groupBy(CodexAutomaticTagEntity::getCodexId)
                        .filterValues { values ->
                            CodexLikesPolicy.postMatchesAnyAutomaticTag(
                                post = post,
                                automaticTags = values.mapNotNull(CodexAutomaticTagEntity::toDomainOrNull),
                            )
                        }
                        .keys
                        .forEach { codexId ->
                            val inserted = dao.insertCodexItem(
                                CodexItemEntity(
                                    codexId,
                                    post.id.source.id,
                                    post.id.sourcePostId,
                                    clock(),
                                ),
                            )
                            if (inserted != -1L) automaticMembershipsAdded += 1
                        }
                }
                systemMembershipChanged
            } else {
                val removed = dao.deleteCodexItem(
                    systemCodexId,
                    post.id.source.id,
                    post.id.sourcePostId,
                ) > 0
                dao.deleteOrphanPost(post.id.source.id, post.id.sourcePostId)
                removed
            }
            CodexLikeSyncResult(
                nowLiked = nowLiked,
                membershipChanged = membershipChanged,
                automaticMembershipsAdded = automaticMembershipsAdded,
            )
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
            val likedKeys = likedBeforeClear.mapTo(mutableSetOf()) { it.source to it.sourcePostId }
            val memberships = dao.itemsForCodex(systemCodexId).filter { (it.source to it.sourcePostId) in likedKeys }
            val undo = ClearedLikesSnapshot(
                items = memberships.mapNotNull { it.toDomainOrNull() },
                posts = likedBeforeClear.mapNotNull { dao.post(it.source, it.sourcePostId)?.let(codec::decodePost) },
                likes = likedBeforeClear.mapNotNull(codec::decodeLike),
            )
            val clearedLikes = dao.deleteLikes(normalized)
            var removedMemberships = 0
            likedBeforeClear.forEach { liked ->
                removedMemberships += dao.deleteCodexItem(
                    systemCodexId,
                    liked.source,
                    liked.sourcePostId,
                )
            }
            cleanupOrphanPostsInside(likedBeforeClear.map { it.source to it.sourcePostId })
            CodexLikesClearResult(clearedLikes, removedMemberships, undo.takeIf { clearedLikes > 0 })
        }
    }

    override suspend fun restoreClearedLikes(snapshot: ClearedLikesSnapshot) {
        database.withTransaction {
            restoreItems(snapshot.items, snapshot.posts)
            snapshot.likes.forEach { like ->
                dao.insertLike(LikedPostEntity(
                    like.profileId, like.postId.source.id, like.postId.sourcePostId,
                    like.likedAtEpochMs, codec.encodeTags(like.tags),
                ))
            }
        }
    }

    override suspend fun clearLikesAndDeleteSystemCodex(
        profileId: String,
        systemCodexId: String,
    ): CodexProfileDeleteResult {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        if (normalized.isBlank()) return CodexProfileDeleteResult(0, false)
        return database.withTransaction {
            val removedIds = dao.likesForProfile(normalized).map { it.source to it.sourcePostId } +
                dao.itemsForCodex(systemCodexId).map { it.source to it.sourcePostId }
            val clearedLikes = dao.deleteLikes(normalized)
            val systemCodexDeleted = dao.deleteCodex(systemCodexId) > 0
            if (systemCodexDeleted) normalizeCodexOrderInside()
            cleanupOrphanPostsInside(removedIds)
            CodexProfileDeleteResult(clearedLikes, systemCodexDeleted)
        }
    }

    private suspend fun ensureCodexInside(codexId: String, name: String): Codex {
        writeGuard.requireCodexWritable(codexId)
        val current = dao.codices()
        val existing = current.firstOrNull { entity -> entity.codexId == codexId }
        val resolvedName = CodexLikesPolicy.resolveUniqueCodexName(
            name,
            current.map { entity -> entity.toDomain() },
            excludeCodexId = codexId,
        )
        if (existing != null) {
            if (existing.name != resolvedName) dao.updateCodexName(codexId, resolvedName)
            return CodexEntity(
                existing.codexId,
                resolvedName,
                existing.createdAtEpochMs,
                existing.displayOrder,
            ).toDomain(dao.automaticTagsForCodex(existing.codexId))
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

    private fun cleanupOrphanPostsInside(postIds: List<Pair<String, String>>) {
        postIds.distinct().forEach { (source, postId) -> dao.deleteOrphanPost(source, postId) }
    }

    private suspend fun toggleLikeInside(
        normalizedProfileId: String,
        postId: PostId,
        tags: List<String>,
    ): Boolean {
        val existing = dao.likedPost(normalizedProfileId, postId.source.id, postId.sourcePostId)
        if (existing != null) {
            dao.deleteLike(normalizedProfileId, postId.source.id, postId.sourcePostId)
            return false
        }
        dao.insertLike(
            LikedPostEntity(
                normalizedProfileId,
                postId.source.id,
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

private fun CodexEntity.toDomain(automaticTagEntities: List<CodexAutomaticTagEntity> = emptyList()): Codex {
    return Codex(
        codexId = codexId,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        automaticTags = CodexLikesPolicy.normalizeAutomaticTags(
            automaticTagEntities.mapNotNull(CodexAutomaticTagEntity::toDomainOrNull),
        ),
    )
}

private fun CodexAutomaticTagEntity.toDomainOrNull(): CodexAutomaticTag? {
    val sourceKey = SourceKey.parse(source) ?: return null
    return CodexAutomaticTag(source = sourceKey, tag = tagDisplay, groupIndex = groupIndex)
}

private fun CodexAutomaticTag.toEntity(codexId: String): CodexAutomaticTagEntity {
    return CodexAutomaticTagEntity(
        codexId,
        source.id,
        sourceTagKey(source, tag),
        tag,
        groupIndex,
    )
}

private fun CodexItemEntity.toDomainOrNull(): CodexItem? {
    val sourceKey = SourceKey.parse(source) ?: return null
    return CodexItem(
        codexId = codexId,
        postId = PostId(sourceKey, sourcePostId),
        savedAtEpochMs = savedAtEpochMs,
    )
}

private fun LikedPostEntity.toPostIdOrNull(): PostId? {
    return SourceKey.parse(source)?.let { sourceKey -> PostId(sourceKey, sourcePostId) }
}
