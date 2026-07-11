package com.theoriacodex.app.testing

import com.theoriacodex.data.repository.CodexBulkImportResult
import com.theoriacodex.data.repository.CodexBulkReorderResult
import com.theoriacodex.data.repository.CodexLikeSyncResult
import com.theoriacodex.data.repository.CodexLikesClearResult
import com.theoriacodex.data.repository.CodexLikesPolicy
import com.theoriacodex.data.repository.CodexLikesTransactions
import com.theoriacodex.data.repository.CodexProfileDeleteResult
import com.theoriacodex.data.repository.CodexRepository
import com.theoriacodex.data.repository.InMemoryCodexRepository
import com.theoriacodex.data.repository.InMemoryLikesRepository
import com.theoriacodex.data.repository.LikesRepository
import com.theoriacodex.domain.model.Post
import kotlinx.coroutines.flow.first

/** Storage-independent transaction fake for app workflow tests. */
class InMemoryCodexLikesTransactions(
    val codices: CodexRepository = InMemoryCodexRepository(),
    val likes: LikesRepository = InMemoryLikesRepository(),
) : CodexLikesTransactions {
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
    ): CodexLikeSyncResult {
        val codex = codices.ensureCodex(systemCodexId, systemCodexName)
        val before = codices.observeCodexItems(codex.codexId).first().any { it.postId == post.id }
        val nowLiked = likes.toggleLike(profileId, post.id, tags)
        if (nowLiked) {
            codices.addItem(codex.codexId, post)
        } else {
            codices.removeItem(codex.codexId, post.id.source, post.id.sourcePostId)
        }
        val after = codices.observeCodexItems(codex.codexId).first().any { it.postId == post.id }
        return CodexLikeSyncResult(nowLiked = nowLiked, membershipChanged = before != after)
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

    override suspend fun clearAllContent() {
        codices.observeCodices().first().forEach { codex -> codices.deleteCodex(codex.codexId) }
    }
}
