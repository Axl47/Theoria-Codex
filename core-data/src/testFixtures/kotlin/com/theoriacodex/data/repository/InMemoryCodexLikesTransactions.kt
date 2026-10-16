package com.theoriacodex.data.repository

import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.first

/** Storage-independent transaction fake for app workflow tests. */
class InMemoryCodexLikesTransactions(
    val codices: CodexRepository = InMemoryCodexRepository(),
    val likes: InMemoryLikesRepository = InMemoryLikesRepository(),
) : CodexLikesTransactions {
    override suspend fun cleanCodex(request: CodexCleaningRequest): CodexCleaningSnapshot {
        (request.comparisonIds + request.targetId).forEach { check(codices.observeCodex(it).first() != null) }
        val compared = request.comparisonIds.flatMap { codices.observeCodexItems(it).first() }.map { it.postId }.toSet()
        val items = codices.observeCodexItems(request.targetId).first().filter { it.postId in compared }
        val posts = items.map { checkNotNull(codices.getPost(it.postId)) }
        val removedLikes = if (request.targetId == ProfileLibraryIds.likes(request.profileId)) {
            val ids = items.map { it.postId }.toSet()
            likes.observeLikes(request.profileId).first().filter { it.postId in ids }
        } else emptyList()
        codices.removeItems(request.targetId, items.map { it.postId }.toSet())
        likes.removeLikes(request.profileId, removedLikes.map { it.postId }.toSet())
        return CodexCleaningSnapshot(request, items, posts, removedLikes)
    }

    override suspend fun restoreCleanedCodex(snapshot: CodexCleaningSnapshot) {
        check(codices.observeCodex(snapshot.request.targetId).first() != null)
        codices.restoreItems(snapshot.items, snapshot.posts)
        likes.restoreLikes(snapshot.likes)
    }

    override suspend fun removeProfileLibrary(profileId: String) {
        codices.observeCodices().first().filter { ProfileLibraryIds.belongsTo(it.codexId, profileId) }
            .forEach { codices.deleteCodex(it.codexId) }
        likes.clearLikes(profileId)
    }

    override suspend fun importCodex(
        codexId: String,
        name: String,
        posts: List<Post>,
    ): CodexBulkImportResult {
        val codex = codices.ensureCodex(codexId, name)
        val uniquePosts = posts.distinctBy(Post::id)
        val before = codices.observeCodexItems(codex.codexId).first().map { it.postId }.toSet()
        uniquePosts.forEach { post -> codices.addItem(codex.codexId, post) }
        return CodexBulkImportResult(
            codex = codex,
            acceptedPosts = uniquePosts.size,
            insertedMemberships = uniquePosts.count { post -> post.id !in before },
        )
    }

    override suspend fun reorderCodices(codexIdsInOrder: List<String>): CodexBulkReorderResult {
        val current = codices.observeCodices().first()
        val resolved = CodexLikesPolicy.resolveCompleteCodexOrder(current, codexIdsInOrder)
            ?: return CodexBulkReorderResult(applied = false, movedCodices = 0)
        val originalIndices = current.mapIndexed { index, codex -> codex.codexId to index }.toMap()
        resolved.forEachIndexed { index, codex -> codices.reorderCodex(codex.codexId, index) }
        return CodexBulkReorderResult(
            applied = true,
            movedCodices = resolved.count { codex -> originalIndices[codex.codexId] != resolved.indexOf(codex) },
        )
    }

    override suspend fun toggleLikeAndSyncSystemCodex(
        profileId: String,
        systemCodexId: String,
        systemCodexName: String,
        post: Post,
        tags: List<String>,
        eligibleAutomaticCodexIds: Set<String>,
    ): CodexLikeSyncResult {
        val codex = codices.ensureCodex(systemCodexId, systemCodexName)
        val before = codices.observeCodexItems(codex.codexId).first().any { it.postId == post.id }
        val automaticMembershipsBefore = eligibleAutomaticCodexIds.associateWith { automaticCodexId ->
            codices.observeCodexItems(automaticCodexId).first().any { item -> item.postId == post.id }
        }
        val nowLiked = likes.toggleLike(profileId, post.id, tags)
        if (nowLiked) {
            codices.addItem(codex.codexId, post)
            eligibleAutomaticCodexIds.forEach { automaticCodexId ->
                val automaticCodex = codices.observeCodex(automaticCodexId).first()
                if (
                    automaticCodex != null &&
                    CodexLikesPolicy.postMatchesAnyAutomaticTag(post, automaticCodex.automaticTags)
                ) {
                    codices.addItem(automaticCodexId, post)
                }
            }
        } else {
            codices.removeItem(codex.codexId, post.id.source, post.id.sourcePostId)
        }
        val after = codices.observeCodexItems(codex.codexId).first().any { it.postId == post.id }
        val automaticMembershipsAdded = if (nowLiked) {
            eligibleAutomaticCodexIds.count { automaticCodexId ->
                automaticMembershipsBefore[automaticCodexId] == false &&
                    codices.observeCodexItems(automaticCodexId).first().any { item -> item.postId == post.id }
            }
        } else {
            0
        }
        return CodexLikeSyncResult(
            nowLiked = nowLiked,
            membershipChanged = before != after,
            automaticMembershipsAdded = automaticMembershipsAdded,
        )
    }

    override suspend fun clearLikesAndLikedMemberships(
        profileId: String,
        systemCodexId: String,
    ): CodexLikesClearResult {
        val likedIds = likes.observeLikedPostIds(profileId).first()
        val before = codices.observeCodexItems(systemCodexId).first().map { it.postId }.toSet()
        likes.clearLikes(profileId)
        likedIds.forEach { postId ->
            codices.removeItem(systemCodexId, postId.source, postId.sourcePostId)
        }
        return CodexLikesClearResult(
            clearedLikes = likedIds.size,
            removedMemberships = likedIds.count(before::contains),
        )
    }

    override suspend fun clearLikesAndDeleteSystemCodex(
        profileId: String,
        systemCodexId: String,
    ): CodexProfileDeleteResult {
        val clearedLikes = likes.observeLikedPostIds(profileId).first().size
        val existed = codices.observeCodex(systemCodexId).first() != null
        likes.clearLikes(profileId)
        if (existed) codices.deleteCodex(systemCodexId)
        return CodexProfileDeleteResult(
            clearedLikes = clearedLikes,
            systemCodexDeleted = existed,
        )
    }


}
