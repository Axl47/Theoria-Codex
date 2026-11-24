package com.theoriacodex.data.android.room

import androidx.room.withTransaction
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
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.CodexSummary
import com.theoriacodex.data.repository.ProfileLibraryIds
import com.theoriacodex.data.repository.ProfileLibraryWriteGuard
import com.theoriacodex.data.repository.CodexSortMode
import com.theoriacodex.data.repository.LikedPost
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.data.repository.SourceLibraryPresence
import com.theoriacodex.domain.model.Codex
import com.theoriacodex.domain.model.CodexAutomaticTag
import com.theoriacodex.domain.model.CodexItem
import com.theoriacodex.domain.model.Post
import com.theoriacodex.domain.model.PostId
import com.theoriacodex.domain.model.SourceKey
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

class RoomCodexLikesRepository(
    private val database: TheoriaRoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val writeGuard: ProfileLibraryWriteGuard = ProfileLibraryWriteGuard(),
) : CodexRepository, LikesRepository, CodexLikesTransactions, SourceLibraryPresence {
    private val dao = database.codexLikesDao()
    private val sharedPosts = SharedPosts(dao)
    private val retainedPayloads = RetainedPostPayloadCache(LocalPostPayloadCodec::decode)
    private val summaries = RoomCodexSummaryQueries(dao, retainedPayloads)

    override suspend fun cleanCodex(request: CodexCleaningRequest): CodexCleaningSnapshot = database.withTransaction {
        writeGuard.requireProfileWritable(request.profileId)
        (request.comparisonIds + request.targetId).forEach { id ->
            check(dao.codex(id) != null) { "A selected Codex no longer exists" }
        }
        val rows = dao.overlappingItems(request.targetId, request.comparisonIds.sorted())
        val items = rows.map { checkNotNull(it.toDomainOrNull()) }
        val posts = rows.map { row -> checkNotNull(storedPost(row.source, row.sourcePostId)) }
        val likes = if (request.targetId == ProfileLibraryIds.likes(request.profileId)) {
            rows.mapNotNull { row ->
                dao.likedPost(request.profileId, row.source, row.sourcePostId)?.let { checkNotNull(it.toLikedPostOrNull()) }
            }
        } else emptyList()
        rows.forEach { row -> dao.deleteCodexItem(request.targetId, row.source, row.sourcePostId) }
        likes.forEach { like -> dao.deleteLike(request.profileId, like.postId.source.id, like.postId.sourcePostId) }
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
                dao.insertLike(like.toEntity())
            }
        }
    }

    override suspend fun removeProfileLibrary(profileId: String) {
        database.withTransaction {
            val codices = dao.codices().filter { ProfileLibraryIds.belongsTo(it.codexId, profileId) }
            val affected = codices.flatMap { dao.itemsForCodex(it.codexId) }
                .map { it.source to it.sourcePostId } + dao.likesForProfile(profileId).map { it.source to it.sourcePostId }
            dao.deleteLikes(profileId)
            database.forYouVerdictDao().deleteProfile(profileId)
            codices.forEach { dao.deleteCodex(it.codexId) }
            normalizeCodexOrderInside()
            sharedPosts.releaseIfOrphaned(affected)
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

    private suspend fun reorderCodex(codexId: String, targetIndex: Int) {
        database.withTransaction {
            val current = dao.codices()
            val sourceIndex = current.indexOfFirst { entity -> entity.codexId == codexId }
            if (sourceIndex < 0 || current.isEmpty()) return@withTransaction
            val clampedTarget = targetIndex.coerceIn(0, current.lastIndex)
            if (sourceIndex == clampedTarget) return@withTransaction
            writeDisplayOrder(current.toMutableList().apply { add(clampedTarget, removeAt(sourceIndex)) })
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

    private suspend fun deleteCodex(codexId: String) {
        database.withTransaction {
            val removedItems = dao.itemsForCodex(codexId)
            if (dao.deleteCodex(codexId) > 0) {
                normalizeCodexOrderInside()
                sharedPosts.releaseIfOrphaned(removedItems.map { it.source to it.sourcePostId })
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
            posts = rows.mapNotNull { row -> storedPost(row.source, row.sourcePostId) },
        )
        deleteCodex(codexId)
        snapshot
    }

    override suspend fun deleteCodexIfEmpty(codexId: String): Boolean = database.withTransaction {
        if (dao.codex(codexId) == null || dao.itemsForCodex(codexId).isNotEmpty()) return@withTransaction false
        deleteCodex(codexId)
        true
    }

    override suspend fun restoreDeletedCodex(snapshot: DeletedCodexSnapshot) {
        database.withTransaction {
            val codex = snapshot.codex
            writeGuard.requireCodexWritable(codex.codexId)
            val current = dao.codices()
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
            CodexSortMode.OLDEST_SAVED -> dao.observeCodexPostsOldest(codexId)
            CodexSortMode.BY_SOURCE -> dao.observeCodexPostsBySource(codexId)
            else -> dao.observeCodexPostsNewest(codexId)
        }
        return rows.decodePostSnapshots(retainedPayloads, "codex:$codexId")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLibraryPosts(codexIds: Set<String>): Flow<List<Post>> {
        if (codexIds.isEmpty()) return flowOf(emptyList())
        val ids = codexIds.sorted()
        return dao.observeSavedPostIds(ids).flatMapLatest { dao.observeLibraryPosts(ids).take(1) }
            .decodePostSnapshots(retainedPayloads, "library:${ids.joinToString("\u0000")}")
    }

    override suspend fun hasLibraryData(source: SourceKey): Boolean =
        withContext(Dispatchers.IO) { dao.hasPosts(source.id) }

    override suspend fun getPost(postId: PostId): Post? = withContext(Dispatchers.IO) {
        storedPost(postId.source.id, postId.sourcePostId)
    }

    override suspend fun addItems(codexId: String, posts: List<Post>): Int {
        return database.withTransaction {
            writeGuard.requireCodexWritable(codexId)
            check(dao.codex(codexId) != null) { "The selected Codex no longer exists" }
            posts.distinctBy(Post::id).count { post -> saveItem(codexId, post, clock()) }
        }
    }

    override suspend fun updatePost(post: Post) {
        database.withTransaction {
            if (dao.post(post.id.source.id, post.id.sourcePostId) != null) {
                sharedPosts.upsert(post)
                rememberPicture(post)
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
            if (removed) sharedPosts.releaseIfOrphaned(postIds.map { it.source.id to it.sourcePostId })
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
                    saveItem(item.codexId, post, item.savedAtEpochMs)
                }
            }
        }
    }

    override fun observeLikes(profileId: String): Flow<List<LikedPost>> {
        val normalized = CodexLikesPolicy.normalizeProfileId(profileId)
        return dao.observeLikes(normalized).map { entities -> entities.mapNotNull(LikedPostEntity::toLikedPostOrNull) }
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
            CodexBulkImportResult(
                codex = codex,
                acceptedPosts = uniquePosts.size,
                insertedMemberships = uniquePosts.count { post -> saveItem(codex.codexId, post, clock()) },
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
            val movedCodices = writeDisplayOrder(resolved.map { codex -> currentById.getValue(codex.codexId) })
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
                val systemMembershipChanged = saveItem(codex.codexId, post, clock())
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
                            if (insertItem(codexId, post.id, clock())) automaticMembershipsAdded += 1
                        }
                }
                systemMembershipChanged
            } else {
                val removed = dao.deleteCodexItem(
                    systemCodexId,
                    post.id.source.id,
                    post.id.sourcePostId,
                ) > 0
                sharedPosts.releaseIfOrphaned(listOf(post.id.source.id to post.id.sourcePostId))
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
                posts = likedBeforeClear.mapNotNull { storedPost(it.source, it.sourcePostId) },
                likes = likedBeforeClear.mapNotNull(LikedPostEntity::toLikedPostOrNull),
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
            sharedPosts.releaseIfOrphaned(likedBeforeClear.map { it.source to it.sourcePostId })
            CodexLikesClearResult(clearedLikes, removedMemberships, undo.takeIf { clearedLikes > 0 })
        }
    }

    override suspend fun restoreClearedLikes(snapshot: ClearedLikesSnapshot) {
        database.withTransaction {
            restoreItems(snapshot.items, snapshot.posts)
            snapshot.likes.forEach { like -> dao.insertLike(like.toEntity()) }
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

    private fun normalizeCodexOrderInside() {
        writeDisplayOrder(dao.codices())
    }

    private fun writeDisplayOrder(ordered: List<CodexEntity>): Int = ordered.withIndex().count { (index, entity) ->
        (entity.displayOrder != index).also { moved -> if (moved) dao.updateCodexOrder(entity.codexId, index) }
    }

    private fun saveItem(codexId: String, post: Post, savedAtEpochMs: Long): Boolean {
        sharedPosts.upsert(post)
        rememberPicture(post)
        return insertItem(codexId, post.id, savedAtEpochMs)
    }

    private fun rememberPicture(post: Post) {
        val hash = post.pictureHash ?: return
        database.visualSignatureDao().put(post.id.source.id, post.id.sourcePostId, hash, clock())
    }

    private fun insertItem(codexId: String, postId: PostId, savedAtEpochMs: Long): Boolean =
        dao.insertCodexItem(CodexItemEntity(codexId, postId.source.id, postId.sourcePostId, savedAtEpochMs)) != -1L

    private fun storedPost(source: String, sourcePostId: String): Post? =
        dao.post(source, sourcePostId)?.let(LocalPostPayloadCodec::decode)

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
                LikedTagsCodec.encode(CodexLikesPolicy.normalizeLikedTags(tags)),
            )
        )
        return true
    }
}
