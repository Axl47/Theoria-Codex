package com.theoriacodex.data.android.room

import com.theoriacodex.data.repository.mergeSparsePost
import com.theoriacodex.data.storage.PostStorageCodec
import com.theoriacodex.domain.model.Post

internal class SharedPosts(
    private val dao: CodexLikesDao,
) {
    fun upsert(post: Post) {
        val source = post.id.source.id
        val sourcePostId = post.id.sourcePostId
        val existingEntity = dao.post(source, sourcePostId)
        if (existingEntity == null) {
            val inserted = dao.insertPost(PostEntity(source, sourcePostId, LocalPostPayloadCodec.encode(post)))
            if (inserted != -1L) return
        }

        val current = requireNotNull(existingEntity ?: dao.post(source, sourcePostId)) {
            "Shared Post disappeared while merging $source:$sourcePostId"
        }
        val existing = LocalPostPayloadCodec.decode(current)
        val merged = PostStorageCodec.encode(mergeSparsePost(existing, post))
        if (PostStorageCodec.decode(merged) != existing) {
            check(dao.updatePost(source, sourcePostId, LocalPostPayloadCodec.encode(merged)) == 1) {
                "Shared Post disappeared while updating $source:$sourcePostId"
            }
        }
    }

    fun releaseIfOrphaned(postIds: List<Pair<String, String>>) {
        postIds.distinct().forEach { (source, sourcePostId) -> dao.deleteOrphanPost(source, sourcePostId) }
    }
}
